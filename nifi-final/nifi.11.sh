#!/bin/bash
set -euo pipefail

########################################
# CONFIG
########################################

NIFI_USER="${SINGLE_USER_CREDENTIALS_USERNAME}"
NIFI_PASSWORD="${SINGLE_USER_CREDENTIALS_PASSWORD}"

REG_CLIENT_ID="${REGISTRY_ID}"
BUCKET_ID="${REGISTRY_BUCKET_ID}"

TARGET_RPG_URL="${TARGET_RPG_URL}"

NIFI_URL="${NIFI_URL}"
REGISTRY_URL="${REGISTRY_URL}"

START_FLOW="${START_FLOW:-false}"

POSITION_STEP=400

########################################
# Logging
########################################

timestamp() { date +"%Y-%m-%d %H:%M:%S"; }
log_info()  { echo "[INFO]  $(timestamp)  $1"; }
log_warn()  { echo "[WARN]  $(timestamp)  $1"; }

log_section() {
 echo ""
 echo "=================================================="
 echo "[SECTION] $(timestamp)  $1"
 echo "=================================================="
}

########################################
# Wait for NiFi
########################################

wait_for_nifi() {
 log_section "Waiting for NiFi API"

 until curl -k -s -X POST \
 "$NIFI_URL/nifi-api/access/token" \
 -H "Content-Type: application/x-www-form-urlencoded" \
 -d "username=$NIFI_USER&password=$NIFI_PASSWORD" > /dev/null
 do sleep 5; done

 log_info "NiFi API is ready"
}

########################################
# Authenticate
########################################

authenticate() {
 TOKEN=$(curl -k -s -X POST \
 "$NIFI_URL/nifi-api/access/token" \
 -H "Content-Type: application/x-www-form-urlencoded" \
 -d "username=$NIFI_USER&password=$NIFI_PASSWORD")

 AUTH_HEADER="Authorization: Bearer $TOKEN"
}

########################################
# Root PG
########################################

get_root_pg() {
 ROOT_PG=$(curl -k -s \
 "$NIFI_URL/nifi-api/flow/process-groups/root" \
 -H "$AUTH_HEADER" |
 jq -r '.processGroupFlow.id')
}

########################################
# Position
########################################

get_next_position() {
 MAX_X=$(curl -k -s \
 "$NIFI_URL/nifi-api/flow/process-groups/$ROOT_PG" \
 -H "$AUTH_HEADER" |
 jq '[.processGroupFlow.flow.processGroups[].position.x] | max')

 POS_X=${MAX_X:-300}
 POS_X=$((POS_X + POSITION_STEP))
 POS_Y=300
}

########################################
# Registry
########################################

cache_registry_flows() {
 REGISTRY_FLOW_META=$(curl -k -s \
 "$REGISTRY_URL/nifi-registry-api/buckets/$BUCKET_ID/flows")
}

########################################
# PARAMS
########################################

build_param_json() {
 PARAM_JSON=$(env | grep '^PARAM_' | awk -F= '
 {
   key=$1
   sub("PARAM_", "", key)
   gsub("_", "-", key)
   printf "\"%s\":\"%s\",", key, $2
 }
 END { print "" }' | sed 's/,$//')

 PARAM_JSON="{${PARAM_JSON}}"
}

inject_parameters() {
 build_param_json

 TMP="/tmp/flow.json"

 jq --argjson params "$PARAM_JSON" '
 (.. | objects | select(has("parameters")) | .parameters) |=
 map(if .parameter.name as $n | $params[$n]
     then .parameter.value=$params[$n]
     else . end)
 ' "$FLOW_FILE" > "$TMP"

 FLOW_FILE="$TMP"
}

########################################
# Flow Prep
########################################

prepare_flow() {
 TMP="/tmp/$(basename "$1")"

 jq --arg url "$TARGET_RPG_URL" '
 (.flowContents.remoteProcessGroups[]?.targetUris)=$url
 ' "$1" > "$TMP"

 FLOW_FILE="$TMP"
}

########################################
# Hash
########################################

calculate_hash() {
 FLOW_HASH=$(jq -S '.flowContents | del(..|.position?)' "$FLOW_FILE" | sha256sum | cut -d' ' -f1)
 PARAM_HASH=$(jq -S '.parameterContexts // {}' "$FLOW_FILE" | sha256sum | cut -d' ' -f1)
 LOCAL_HASH="${FLOW_HASH}_${PARAM_HASH}"
}

########################################
# Registry ops
########################################

lookup_registry_flow() {
 FLOW_ID=$(echo "$REGISTRY_FLOW_META" |
 jq -r ".[]|select(.name==\"$FLOW_NAME\")|.identifier//empty")
}

get_registry_hash() {
 REG_HASH=$(echo "$REGISTRY_FLOW_META" |
 jq -r ".[]|select(.identifier==\"$FLOW_ID\")|.description|sub(\"flow-hash:\";\"\")")
}

create_registry_flow() {
 FLOW_ID=$(curl -k -s -X POST \
 "$REGISTRY_URL/nifi-registry-api/buckets/$BUCKET_ID/flows" \
 -H "Content-Type: application/json" \
 -d "{\"name\":\"$FLOW_NAME\",\"description\":\"flow-hash:$LOCAL_HASH\"}" |
 jq -r '.identifier')
}

get_latest_version() {
 LATEST=$(curl -k -s \
 "$REGISTRY_URL/nifi-registry-api/buckets/$BUCKET_ID/flows/$FLOW_ID/versions/latest")

 REG_VERSION=$(echo "$LATEST" | jq '.snapshotMetadata.version // 0')
 NEXT_VERSION=$((REG_VERSION+1))
}

upload_registry_version() {
 PAYLOAD=$(jq \
 --arg bucket "$BUCKET_ID" \
 --arg flow "$FLOW_ID" \
 --argjson version "$NEXT_VERSION" \
 '.snapshotMetadata.bucketIdentifier=$bucket |
  .snapshotMetadata.flowIdentifier=$flow |
  .snapshotMetadata.version=$version' "$FLOW_FILE")

 curl -k -s -X POST \
 "$REGISTRY_URL/nifi-registry-api/buckets/$BUCKET_ID/flows/$FLOW_ID/versions" \
 -H "Content-Type: application/json" \
 -d "$PAYLOAD"
}

update_registry_hash() {
 curl -k -s -X PUT \
 "$REGISTRY_URL/nifi-registry-api/buckets/$BUCKET_ID/flows/$FLOW_ID" \
 -H "Content-Type: application/json" \
 -d "{\"identifier\":\"$FLOW_ID\",\"name\":\"$FLOW_NAME\",\"description\":\"flow-hash:$LOCAL_HASH\"}"
}

########################################
# PG
########################################

find_pg() {
 PG_ID=$(curl -k -s \
 "$NIFI_URL/nifi-api/flow/process-groups/$ROOT_PG" \
 -H "$AUTH_HEADER" |
 jq -r ".processGroupFlow.flow.processGroups[]
 | select(.component.name==\"$FLOW_NAME\") | .component.id // empty")
}

########################################
# Import
########################################

import_flow() {

 DEPLOY_TIME=$(date +"%Y-%m-%d %H:%M:%S")
 COMMENTS="version:$NEXT_VERSION | hash:$LOCAL_HASH | deployed:$DEPLOY_TIME"

 PAYLOAD=$(jq -n \
 --arg name "$FLOW_NAME" \
 --arg comments "$COMMENTS" \
 --arg registry "$REG_CLIENT_ID" \
 --arg bucket "$BUCKET_ID" \
 --arg flow "$FLOW_ID" \
 --argjson version "$NEXT_VERSION" \
 --argjson x "$POS_X" \
 --argjson y "$POS_Y" \
 '
 {
 revision:{version:0},
 component:{
  name:$name,
  comments:$comments,
  position:{x:$x,y:$y},
  versionControlInformation:{
   registryId:$registry,
   bucketId:$bucket,
   flowId:$flow,
   version:$version
  }
 }
 }')

 curl -k -s -X POST \
 "$NIFI_URL/nifi-api/process-groups/root/process-groups" \
 -H "$AUTH_HEADER" \
 -H "Content-Type: application/json" \
 -d "$PAYLOAD"

 POS_X=$((POS_X + POSITION_STEP))
}

########################################
# Update version
########################################

update_flow_version() {

 REVISION=$(curl -k -s \
 "$NIFI_URL/nifi-api/process-groups/$PG_ID" \
 -H "$AUTH_HEADER" | jq -r '.revision.version')

 PAYLOAD=$(jq -n \
 --argjson rev "$REVISION" \
 --arg reg "$REG_CLIENT_ID" \
 --arg bucket "$BUCKET_ID" \
 --arg flow "$FLOW_ID" \
 --argjson version "$NEXT_VERSION" \
 '
 {
 processGroupRevision:{version:$rev},
 versionControlInformation:{
  registryId:$reg,
  bucketId:$bucket,
  flowId:$flow,
  version:$version
 }
 }')

 curl -k -s -X POST \
 "$NIFI_URL/nifi-api/versions/update-requests/process-groups/$PG_ID" \
 -H "$AUTH_HEADER" \
 -H "Content-Type: application/json" \
 -d "$PAYLOAD"
}

########################################
# Update comments
########################################

update_pg_comments() {

 DEPLOY_TIME=$(date +"%Y-%m-%d %H:%M:%S")
 COMMENTS="version:$NEXT_VERSION | hash:$LOCAL_HASH | deployed:$DEPLOY_TIME"

 REVISION=$(curl -k -s \
 "$NIFI_URL/nifi-api/process-groups/$PG_ID" \
 -H "$AUTH_HEADER" | jq -r '.revision.version')

 PAYLOAD=$(jq -n \
 --argjson rev "$REVISION" \
 --arg id "$PG_ID" \
 --arg name "$FLOW_NAME" \
 --arg comments "$COMMENTS" \
 '
 {revision:{version:$rev},
  component:{id:$id,name:$name,comments:$comments}}')

 curl -k -s -X PUT \
 "$NIFI_URL/nifi-api/process-groups/$PG_ID" \
 -H "$AUTH_HEADER" \
 -H "Content-Type: application/json" \
 -d "$PAYLOAD"
}

########################################
# Start / Stop PG
########################################

control_pg_state() {

 if [ "$START_FLOW" = "true" ]; then
   STATE="RUNNING"
 else
   STATE="STOPPED"
 fi

 log_info "Setting process group state: $STATE"

 PAYLOAD=$(jq -n \
 --arg id "$PG_ID" \
 --arg state "$STATE" \
 '{id:$id,state:$state}')

 curl -k -s -X PUT \
 "$NIFI_URL/nifi-api/flow/process-groups/$PG_ID" \
 -H "$AUTH_HEADER" \
 -H "Content-Type: application/json" \
 -d "$PAYLOAD"
}

########################################
# Parameter contexts
########################################

update_parameter_contexts() {

 build_param_json

 CONTEXTS=$(curl -k -s \
 "$NIFI_URL/nifi-api/flow/parameter-contexts" \
 -H "$AUTH_HEADER")

 echo "$CONTEXTS" | jq -c '.parameterContexts[]' | while read -r ctx; do

   PC_ID=$(echo "$ctx" | jq -r '.component.id')

   CURRENT=$(curl -k -s \
   "$NIFI_URL/nifi-api/parameter-contexts/$PC_ID" \
   -H "$AUTH_HEADER")

   REVISION=$(echo "$CURRENT" | jq -r '.revision.version')

   UPDATED=$(echo "$CURRENT" | jq --argjson params "$PARAM_JSON" '
   .component.parameters |= map(
     if .parameter.name as $n | $params[$n]
     then .parameter.value=$params[$n]
     else . end)')

   PAYLOAD=$(echo "$UPDATED" | jq --argjson rev "$REVISION" '
   {revision:{version:$rev},component:.component}')

   curl -k -s -X PUT \
   "$NIFI_URL/nifi-api/parameter-contexts/$PC_ID" \
   -H "$AUTH_HEADER" \
   -H "Content-Type: application/json" \
   -d "$PAYLOAD"
 done
}

########################################
# MAIN
########################################

wait_for_nifi
authenticate
get_root_pg
get_next_position
cache_registry_flows

for ORIGINAL_FLOW in /flows/*.json; do

 [ -e "$ORIGINAL_FLOW" ] || continue

 FLOW_NAME=$(jq -r '.flowContents.name // .header.flowName' "$ORIGINAL_FLOW")

 log_section "Processing $FLOW_NAME"

 prepare_flow "$ORIGINAL_FLOW"
 inject_parameters
 calculate_hash
 lookup_registry_flow
 get_registry_hash
 find_pg

 if [ "$LOCAL_HASH" = "$REG_HASH" ] && [ -n "$PG_ID" ]; then
  log_info "No changes. Skipping"
  continue
 fi

 [ -z "$FLOW_ID" ] && create_registry_flow

 get_latest_version
 upload_registry_version
 update_registry_hash

 if [ -z "$PG_ID" ]; then
   import_flow
   find_pg
 else
   update_flow_version
   update_pg_comments
 fi

 update_parameter_contexts
 control_pg_state

 log_info "Done: $FLOW_NAME"

done

log_section "All flows deployed"

get_next_position() {

 MAX_X=$(curl -k -s \
 "$NIFI_URL/nifi-api/flow/process-groups/$ROOT_PG" \
 -H "$AUTH_HEADER" |
 jq '[.processGroupFlow.flow.processGroups[].position.x] | max')

 echo "MAX_X=$MAX_X"

 if [ "$MAX_X" = "null" ] || [ -z "$MAX_X" ]; then
   POS_X=300
 else
   POS_X=$MAX_X
 fi

 POS_X=$((POS_X + POSITION_STEP))
 POS_Y=300

 echo "POS_X=$POS_X"
}


map(
  if (.parameter? and .parameter.name? and $params[.parameter.name]? != null) then
    .parameter.value = $params[.parameter.name]
  else .
  end
)


UPDATED=$(echo "$CURRENT" | jq --argjson params "$PARAM_JSON" '
.component.parameters |= map(
  .parameter.name as $n
  | if $params[$n] != null
    then .parameter.value = $params[$n]
    else .
    end
)')
