#!/bin/bash
set -euo pipefail
# set +e
#
# deploy.sh — NiFi flow deployment (runs as a Kubernetes Job, see ../job.yaml)
#
# Transcribed from the screen recording of the original script. Before first use, diff it against
# your original and check the lines marked "VERIFY" (characters the video was too blurry to read).
#
# Inputs (environment):
#   NIFI_URL, REGISTRY_URL, TARGET_RPG_URL, REGISTRY_ID, REGISTRY_BUCKET_ID, START_FLOW
#   SINGLE_USER_CREDENTIALS_USERNAME / SINGLE_USER_CREDENTIALS_PASSWORD   (from the Secret)
#   PARAM_<name>=<value>   parameter overrides — at least ONE must be set (see README: build_param_json)
# Inputs (files):  /flows/*.json        Scratch: /tmp

#########################################
# CONFIG
#########################################

NIFI_USER="${SINGLE_USER_CREDENTIALS_USERNAME}"
NIFI_PASSWORD="${SINGLE_USER_CREDENTIALS_PASSWORD}"

REG_CLIENT_ID="${REGISTRY_ID}"
BUCKET_ID="${REGISTRY_BUCKET_ID}"

TARGET_RPG_URL="${TARGET_RPG_URL}"

NIFI_URL="${NIFI_URL}"
REGISTRY_URL="${REGISTRY_URL}"

START_FLOW="${START_FLOW:-false}"

POSITION_STEP=400

#########################################
# Logging
#########################################

timestamp() { date +"%Y-%m-%d %H:%M:%S"; }
log_info()  { echo "[INFO]  $(timestamp)  $1"; }
log_warn()  { echo "[WARN]  $(timestamp)  $1"; }

log_section() {
  echo ""
  echo "=========================================================================="
  echo "[SECTION] $(timestamp)  $1"
  echo "=========================================================================="
}

to_int() {
  local val="$1"
  local fallback="${2:-0}"
  # Strip all whitespace
  val=$(echo "$val" | tr -d '[:space:]')
  # If empty or non-numeric, use fallback
  if ! echo "$val" | grep -qE '^[0-9]+$'; then
    echo "$fallback"
    return
  fi
  # Force through arithmetic to guarantee clean integer
  echo $(( val + 0 ))
}



#########################################
# Wait for NiFi
#########################################

wait_for_nifi() {
  log_section "Waiting for NiFi API"

  until curl -k -s -X POST \
    "$NIFI_URL/nifi-api/access/token" \
    -H "Content-Type: application/x-www-form-urlencoded" \
    -d "username=$NIFI_USER&password=$NIFI_PASSWORD" > /dev/null
  do sleep 5; done

  log_info "NiFi API is ready"
}

#########################################
# Authenticate
#########################################

authenticate() {
  TOKEN=$(curl -k -s -X POST \
    "$NIFI_URL/nifi-api/access/token" \
    -H "Content-Type: application/x-www-form-urlencoded" \
    -d "username=$NIFI_USER&password=$NIFI_PASSWORD")

  AUTH_HEADER="Authorization: Bearer $TOKEN"
}

#########################################
# Root PG
#########################################

get_root_pg() {
  ROOT_PG=$(curl -k -s \
    "$NIFI_URL/nifi-api/flow/process-groups/root" \
    -H "$AUTH_HEADER" |
    jq -r '.processGroupFlow.id')
}

#########################################
# Position
#########################################

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

#########################################
# Registry
#########################################

cache_registry_flows() {
  REGISTRY_FLOW_META=$(curl -k -s \
    "$REGISTRY_URL/nifi-registry-api/buckets/$BUCKET_ID/flows")
}

#########################################
# PARAMS
#########################################

build_param_json() {
  PARAM_JSON=$(env | grep '^PARAM_' | awk -F= '
  {
    key=$1
    sub("PARAM_", "", key)
    gsub("_", ".", key)   # VERIFY: video unclear — "_" -> "." or "_" -> "-"
    printf "\"%s\":\"%s\",", key, $2
  }
  END { print "" }' | sed 's/,$//')
  log_info "${PARAM_JSON}"
  PARAM_JSON="{${PARAM_JSON}}"
}

inject_parameters() {
  build_param_json
  TMP="/tmp/flow.json"

  jq --argjson params "$PARAM_JSON" '
  (.. | objects | select(has("parameters")) | .parameters) |=
  map(
    if (.parameter? and .parameter.name? and $params[.parameter.name]? != null) then
      .parameter.value = $params[.parameter.name]
    else .
    end
  )
  ' "$FLOW_FILE" > "$TMP"
}

#########################################
# Flow Prep
#########################################

prepare_flow() {
  log_info "Prepare flow"
  TMP="/tmp/$(basename "$1")"

  jq --arg url "$TARGET_RPG_URL" '
  (.flowContents.remoteProcessGroups[]?.targetUris)=$url
  ' "$1" > "$TMP"

  FLOW_FILE="$TMP"
}

#########################################
# Hash
#########################################

calculate_hash() {
  log_info "Calculate Hash"
  FLOW_HASH=$(jq -S '.flowContents | del(..|.position?)' "$FLOW_FILE" | sha256sum | cut -d' ' -f1)
  log_info "FLOW_HASH: ${FLOW_HASH}"
  PARAM_HASH=$(jq -S '.parameterContexts // {}' "$FLOW_FILE" | sha256sum | cut -d' ' -f1)
  log_info "PARAM_HASH: ${PARAM_HASH}"
  LOCAL_HASH="${FLOW_HASH}_${PARAM_HASH}"   # VERIFY: separator between the two hashes unclear in video ("_" or ".")
}

#########################################
# Registry ops
#########################################

lookup_registry_flow() {
  FLOW_ID=$(echo "$REGISTRY_FLOW_META" |
    jq -r ".[]|select(.name==\"$FLOW_NAME\")|.identifier//empty")
}

get_registry_hash() {
  REG_HASH=$(echo "$REGISTRY_FLOW_META" |
    jq -r ".[]|select(.identifier==\"$FLOW_ID\")|.description|sub(\"flow-hash:\";\"\")")
}

create_registry_flow() {

  log_info "Creating new registry flow"

  FLOW_ID=$(curl -k -s -X POST \
    "$REGISTRY_URL/nifi-registry-api/buckets/$BUCKET_ID/flows" \
    -H "Content-Type: application/json" \
    -d "{
    \"name\":\"$FLOW_NAME\",
    \"description\":\"flow-hash:$LOCAL_HASH\"
    }" | jq -r '.identifier')

}

get_latest_version() {
  LATEST=$(curl -k -s \
    "$REGISTRY_URL/nifi-registry-api/buckets/$BUCKET_ID/flows/$FLOW_ID/versions/latest")

  if echo "$LATEST" | jq . >/dev/null 2>&1; then
    REG_VERSION=$(echo "$LATEST" | jq '.snapshotMetadata.version')
  else
    REG_VERSION=0
  fi

  NEXT_VERSION=$((REG_VERSION+1))

  echo $NEXT_VERSION
}

upload_registry_version() {
  PAYLOAD=$(jq \
    --arg bucket "$BUCKET_ID" \
    --arg flow "$FLOW_ID" \
    --argjson version "$NEXT_VERSION" \
    '.snapshotMetadata.bucketIdentifier=$bucket |
     .snapshotMetadata.flowIdentifier=$flow |
     .snapshotMetadata.version=$version' "$FLOW_FILE")

  RESPONSE=$(curl -k -s -X POST \
    "$REGISTRY_URL/nifi-registry-api/buckets/$BUCKET_ID/flows/$FLOW_ID/versions" \
    -H "Content-Type: application/json" \
    -d "$PAYLOAD"
  )
}

update_registry_hash() {
  curl -k -s -X PUT \
    "$REGISTRY_URL/nifi-registry-api/buckets/$BUCKET_ID/flows/$FLOW_ID" \
    -H "Content-Type: application/json" \
    -d "{\"identifier\":\"$FLOW_ID\",\"name\":\"$FLOW_NAME\",\"description\":\"flow-hash:$LOCAL_HASH\"}"
}

#########################################
# PG
#########################################

find_pg() {
  PG_ID=$(curl -k -s \
    "$NIFI_URL/nifi-api/flow/process-groups/$ROOT_PG" \
    -H "$AUTH_HEADER" |
    jq -r ".processGroupFlow.flow.processGroups[]
    | select(.component.name==\"$FLOW_NAME\") | .component.id // empty")
}

#########################################
# Import
#########################################

import_flow() {

  DEPLOY_TIME=$(date +"%Y-%m-%d %H:%M:%S")
  COMMENTS="version:$NEXT_VERSION | hash:$LOCAL_HASH | deployed:$DEPLOY_TIME"

  echo "COMMENTS $COMMENTS"

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

  RESPONSE=$(curl -k -s -X POST \
    "$NIFI_URL/nifi-api/process-groups/root/process-groups" \
    -H "$AUTH_HEADER" \
    -H "Content-Type: application/json" \
    -d "$PAYLOAD"
  )

  POS_X=$((POS_X + POSITION_STEP))
}

#########################################
# Update version
#########################################

update_flow_version() {

  REVISION=$(curl -k -s \
    "$NIFI_URL/nifi-api/process-groups/$PG_ID" \
    -H "$AUTH_HEADER" | jq -r '.revision.version')

  PAYLOAD=$(jq -n \
    --argjson rev "$REVISION" \
    --arg reg "$REG_CLIENT_ID" \
    --arg bucket "$BUCKET_ID" \
    --arg flow "$FLOW_ID" \
    --arg pgId "$PG_ID" \
    --argjson version "$NEXT_VERSION" \
    '
    {
    processGroupRevision:{version:$rev},
    versionControlInformation:{
     registryId:$reg,
     bucketId:$bucket,
     groupId:$pgId,
     flowId:$flow,
     version:$version
    }
    }')

  RESPONSE=$(curl -k -s -X POST \
    "$NIFI_URL/nifi-api/versions/update-requests/process-groups/$PG_ID" \
    -H "$AUTH_HEADER" \
    -H "Content-Type: application/json" \
    -d "$PAYLOAD"
  )

  REQUEST_ID=$(echo "$RESPONSE" | jq -r '.request.requestId')

  log_info "Version update request id: $REQUEST_ID"

  while true
  do
    STATUS_RESPONSE=$(curl -k -s \
      "$NIFI_URL/nifi-api/versions/update-requests/$REQUEST_ID" \
      -H "$AUTH_HEADER")

    COMPLETE=$(echo "$STATUS_RESPONSE" | jq -r '.request.complete')
    FAILURE=$(echo "$STATUS_RESPONSE" | jq -r '.request.failureReason // empty')

    if [ "$COMPLETE" = "true" ]; then
      if [ -n "$FAILURE" ]; then
        log_error "Flow version update failed: $FAILURE"   # NOTE: log_error is not among the logging functions seen in the video
        exit 1
      fi

      log_info "Flow version update completed successfully"
      break
    fi

    log_info "Waiting for flow version update..."
    sleep 5
  done
}

#########################################
# Update comments
#########################################

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
    {
     revision:{
       version:$rev
     },
     component:{
       id:$id,
       name:$name,
       comments:$comments,
       versionControlInformation: .component.versionControlInformation
     }
    }')

  RESPONSE=$(curl -k -s -X PUT \
    "$NIFI_URL/nifi-api/process-groups/$PG_ID" \
    -H "$AUTH_HEADER" \
    -H "Content-Type: application/json" \
    -d "$PAYLOAD"
  )
}

#########################################
# Start / Stop PG
#########################################

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

  RESPONSE=$(curl -k -s -X PUT \
    "$NIFI_URL/nifi-api/flow/process-groups/$PG_ID" \
    -H "$AUTH_HEADER" \
    -H "Content-Type: application/json" \
    -d "$PAYLOAD"
  )
}

#########################################
# Parameter contexts
#########################################

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
        .parameter.name as $n
        | if $params[$n] != null
          then .parameter.value = $params[$n]
          else .
          end
      )')

    PAYLOAD=$(echo "$UPDATED" | jq --argjson rev "$REVISION" \
      '{revision:{version:$rev},component:.component}')

    RESPONSE=$(curl -k -s -X PUT \
      "$NIFI_URL/nifi-api/parameter-contexts/$PC_ID" \
      -H "$AUTH_HEADER" \
      -H "Content-Type: application/json" \
      -d "$PAYLOAD"
    )
  done
}

#########################################
# Enable controller services
#########################################
enable_controller_services() {
  log_info "Enabling controller services for $FLOW_NAME..."

  SERVICES=$(curl -k -s \
    "$NIFI_URL/nifi-api/flow/process-groups/$PG_ID/controller-services" \
    -H "$AUTH_HEADER")

  RAW_COUNT=$(echo "$SERVICES" | jq '.controllerServices | length')
  SERVICE_COUNT=$(to_int "$RAW_COUNT" 0)
  log_info "Found $SERVICE_COUNT controller service(s)"

  if [ "$SERVICE_COUNT" = "0" ]; then
    log_info "No controller services to enable - skipping"
    return 0
  fi

  # Bulk enable all services in PG
  PAYLOAD=$(jq -n \
    --arg id "$PG_ID" \
    '{
      id: $id,
      state: "ENABLED",
      disconnectedNodeAcknowledged: false
    }')

  curl -k -s -X PUT \
    "$NIFI_URL/nifi-api/flow/process-groups/$PG_ID/controller-services" \
    -H "$AUTH_HEADER" \
    -H "Content-Type: application/json" \
    -d "$PAYLOAD" > /dev/null

  log_info "Enable request sent - polling..."

  for i in $(seq 1 30); do
    CURRENT=$(curl -k -s \
      "$NIFI_URL/nifi-api/flow/process-groups/$PG_ID/controller-services" \
      -H "$AUTH_HEADER")

    # VERIFY: the ends of these four lines were cut off at the right edge of the video
    TOTAL=$(to_int "$(echo "$CURRENT"    | jq '.controllerServices | length')" 0)
    ENABLED=$(to_int "$(echo "$CURRENT"  | jq '[.controllerServices[] | select(.component.state == "ENABLED")]  | length')" 0)
    DISABLED=$(to_int "$(echo "$CURRENT" | jq '[.controllerServices[] | select(.component.state == "DISABLED")] | length')" 0)
    ENABLING=$(to_int "$(echo "$CURRENT" | jq '[.controllerServices[] | select(.component.state == "ENABLING")] | length')" 0)

    log_info "Poll $i/30: enabled=$ENABLED enabling=$ENABLING disabled=$DISABLED total=$TOTAL"

    if [ "$ENABLED" = "$TOTAL" ]; then
      log_info "All controller services enabled"
      return 0
    fi

    # Stuck - nothing is enabling but some still disabled after 3 polls
    if [ "$ENABLING" = "0" ] && [ "$DISABLED" != "0" ] && [ "$i" -gt 3 ]; then
      log_warn "Some services stuck in DISABLED - listing:"
      echo "$CURRENT" | jq -r \
        '.controllerServices[] | select(.component.state == "DISABLED") | "  - " + .component.name'
      log_warn "Check NiFi bulletin board for errors"
      return 1
    fi

    sleep 3
  done

  log_warn "Timed out waiting for controller services to enable"
  return 1
}

#########################################
# Main
#########################################

log_section "NiFi Deployment Started"
log_info "NIFI_URL   : $NIFI_URL"
log_info "REGISTRY   : $REGISTRY_URL"
log_info "BUCKET     : $BUCKET_ID"
log_info "START_FLOW : $START_FLOW"

SKIPPED_FLOWS=0
DEPLOYED_FLOWS=0
TOTAL_FLOWS=0


wait_for_nifi
authenticate
get_root_pg
get_next_position
cache_registry_flows

for ORIGINAL_FLOW in /flows/*.json; do

  [ -e "$ORIGINAL_FLOW" ] || continue

  FLOW_NAME=$(jq -r '.flowContents.name // .header.flowName' "$ORIGINAL_FLOW")

  TOTAL_FLOWS=$(( TOTAL_FLOWS + 1 ))

  log_section "Processing $FLOW_NAME"

  prepare_flow "$ORIGINAL_FLOW"
  inject_parameters
  calculate_hash
  lookup_registry_flow
  get_registry_hash
  find_pg

  log_info "Local hash    : $LOCAL_HASH"
  log_info "Registry hash : $REG_HASH"

  if [ "$LOCAL_HASH" = "$REG_HASH" ] && [ -n "$PG_ID" ]; then
    log_info "No changes detected - skipping $FLOW_NAME"
    SKIPPED_FLOWS=$(( SKIPPED_FLOWS + 1 ))
    continue
  fi

  if [ -z "$FLOW_ID" ]; then
    create_registry_flow
  fi

  get_latest_version
  upload_registry_version
  update_registry_hash

  if [ -z "$PG_ID" ]; then
    import_flow
    find_pg
  else
    update_flow_version
    # update_pg_comments
  fi

  enable_controller_services
  update_parameter_contexts
  control_pg_state

  DEPLOYED_FLOWS=$(( DEPLOYED_FLOWS + 1 ))

  log_info "Done: $FLOW_NAME"

done

# Summary
#########################################

log_section "Deployment Summary"
log_info "Total    : $TOTAL_FLOWS"
log_info "Deployed : $DEPLOYED_FLOWS"
log_info "Skipped  : $SKIPPED_FLOWS"


log_section "All flows deployed"
