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
FRAGMENT_MANAGER_URL="${FRAGMENT_MANAGER_URL}"

NIFI_URL="${NIFI_URL}"
REGISTRY_URL="${REGISTRY_URL}"

POSITION_STEP=400

########################################
# Logging
########################################

timestamp() { date +"%Y-%m-%d %H:%M:%S"; }

log_info()  { echo "[INFO]  $(timestamp)  $1"; }
log_warn()  { echo "[WARN]  $(timestamp)  $1"; }
log_error() { echo "[ERROR] $(timestamp)  $1"; }

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
 do
  sleep 5
 done

 log_info "NiFi API is ready"
}

########################################
# Authenticate
########################################

authenticate() {
 log_section "Authenticating to NiFi"

 TOKEN=$(curl -k -s -X POST \
 "$NIFI_URL/nifi-api/access/token" \
 -H "Content-Type: application/x-www-form-urlencoded" \
 -d "username=$NIFI_USER&password=$NIFI_PASSWORD")

 AUTH_HEADER="Authorization: Bearer $TOKEN"

 log_info "Authentication successful"
}

########################################
# Get Root Process Group
########################################

get_root_pg() {
 ROOT_PG=$(curl -k -s \
 "$NIFI_URL/nifi-api/flow/process-groups/root" \
 -H "$AUTH_HEADER" |
 jq -r '.processGroupFlow.id')

 log_info "Root Process Group ID: $ROOT_PG"
}

########################################
# Detect next position
########################################

get_next_position() {
 MAX_X=$(curl -k -s \
 "$NIFI_URL/nifi-api/flow/process-groups/$ROOT_PG" \
 -H "$AUTH_HEADER" |
 jq '[.processGroupFlow.flow.processGroups[].position.x] | max')

 if [ "$MAX_X" = "null" ]; then
  POS_X=300
 else
  POS_X=$((MAX_X + POSITION_STEP))
 fi

 POS_Y=300
 log_info "Next flow position: $POS_X,$POS_Y"
}

########################################
# Cache registry flows
########################################

cache_registry_flows() {
 log_section "Caching registry flows"

 REGISTRY_FLOW_META=$(curl -k -s \
 "$REGISTRY_URL/nifi-registry-api/buckets/$BUCKET_ID/flows")

 log_info "Registry flows cached"
}

########################################
# Prepare flow JSON
########################################

prepare_flow() {
 ORIGINAL_FLOW=$1
 TMP_FLOW="/tmp/$(basename "$ORIGINAL_FLOW")"

 jq \
 --arg rpgUrl "$TARGET_RPG_URL" \
 --arg fragmentUrl "$FRAGMENT_MANAGER_URL" \
 '

(.flowContents.remoteProcessGroups[]?.targetUris) = $rpgUrl |

(.flowContents.processGroups[]?.remoteProcessGroups[]?.targetUris) = $rpgUrl |

(.. | objects | select(has("parameters")) | .parameters) |=
map(
  if .name == "fragment-manager-url"
  then .value = $fragmentUrl
  else .
  end
)

' "$ORIGINAL_FLOW" > "$TMP_FLOW"

 FLOW_FILE="$TMP_FLOW"
}

########################################
# Calculate hashes
########################################

calculate_hash() {
 FLOW_HASH=$(jq -S '.flowContents | del(.. | .position?)' "$FLOW_FILE" | sha256sum | cut -d' ' -f1)

 PARAM_HASH=$(jq -S '.parameterContexts // {}' "$FLOW_FILE" | sha256sum | cut -d' ' -f1)

 LOCAL_HASH="${FLOW_HASH}_${PARAM_HASH}"
}

########################################
# Registry lookup
########################################

lookup_registry_flow() {
 FLOW_ID=$(echo "$REGISTRY_FLOW_META" |
 jq -r ".[] | select(.name==\"$FLOW_NAME\") | .identifier // empty")
}

########################################
# Get registry hash
########################################

get_registry_hash() {
 if [ -z "$FLOW_ID" ]; then
  REG_HASH=""
 else
  REG_HASH=$(echo "$REGISTRY_FLOW_META" |
  jq -r ".[] | select(.identifier==\"$FLOW_ID\") | .description | sub(\"flow-hash:\";\"\")")
 fi
}

########################################
# Create registry flow
########################################

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

########################################
# Get latest version
########################################

get_latest_version() {
 LATEST=$(curl -k -s \
 "$REGISTRY_URL/nifi-registry-api/buckets/$BUCKET_ID/flows/$FLOW_ID/versions/latest")

 if echo "$LATEST" | jq . >/dev/null 2>&1; then
  REG_VERSION=$(echo "$LATEST" | jq '.snapshotMetadata.version')
 else
  REG_VERSION=0
 fi

 NEXT_VERSION=$((REG_VERSION+1))
 log_info "Creating registry version $NEXT_VERSION"
}

########################################
# Upload registry version
########################################

upload_registry_version() {
 PAYLOAD=$(jq \
 --arg bucket "$BUCKET_ID" \
 --arg flow "$FLOW_ID" \
 --argjson version "$NEXT_VERSION" \
 '
 .snapshotMetadata.bucketIdentifier=$bucket |
 .snapshotMetadata.flowIdentifier=$flow |
 .snapshotMetadata.version=$version
 ' "$FLOW_FILE")

 curl -k -s -X POST \
 "$REGISTRY_URL/nifi-registry-api/buckets/$BUCKET_ID/flows/$FLOW_ID/versions" \
 -H "Content-Type: application/json" \
 -d "$PAYLOAD"
}

########################################
# Update stored hash
########################################

update_registry_hash() {
 curl -k -s -X PUT \
 "$REGISTRY_URL/nifi-registry-api/buckets/$BUCKET_ID/flows/$FLOW_ID" \
 -H "Content-Type: application/json" \
 -d "{
 \"identifier\":\"$FLOW_ID\",
 \"name\":\"$FLOW_NAME\",
 \"description\":\"flow-hash:$LOCAL_HASH\"
 }"
}

########################################
# Find process group
########################################

find_pg() {
 PG_ID=$(curl -k -s \
 "$NIFI_URL/nifi-api/flow/process-groups/$ROOT_PG" \
 -H "$AUTH_HEADER" |
 jq -r ".processGroupFlow.flow.processGroups[]
 | select(.component.name==\"$FLOW_NAME\")
 | .component.id // empty")
}

########################################
# Import flow
########################################

import_flow() {
 log_info "Importing flow at position ($POS_X,$POS_Y)"

 IMPORT_PAYLOAD=$(jq -n \
 --arg name "$FLOW_NAME" \
 --arg registryId "$REG_CLIENT_ID" \
 --arg bucketId "$BUCKET_ID" \
 --arg flowId "$FLOW_ID" \
 --argjson version "$NEXT_VERSION" \
 --argjson posX "$POS_X" \
 --argjson posY "$POS_Y" \
 '
 {
 revision:{version:0},
 component:{
 name:$name,
 position:{x:$posX,y:$posY},
 versionControlInformation:{
 registryId:$registryId,
 bucketId:$bucketId,
 flowId:$flowId,
 version:$version
 }
 }
 }')

 curl -k -s -X POST \
 "$NIFI_URL/nifi-api/process-groups/root/process-groups" \
 -H "$AUTH_HEADER" \
 -H "Content-Type: application/json" \
 -d "$IMPORT_PAYLOAD"

 POS_X=$((POS_X + POSITION_STEP))
}

########################################
# Update flow version
########################################

update_flow_version() {
 log_info "Updating flow version"

 PG_JSON=$(curl -k -s \
 "$NIFI_URL/nifi-api/process-groups/$PG_ID" \
 -H "$AUTH_HEADER")

 REVISION=$(echo "$PG_JSON" | jq -r '.revision.version')

 UPDATE_PAYLOAD=$(jq -n \
 --argjson rev "$REVISION" \
 --arg registryId "$REG_CLIENT_ID" \
 --arg bucketId "$BUCKET_ID" \
 --arg groupId "$PG_ID" \
 --arg flowId "$FLOW_ID" \
 --argjson regVersion "$NEXT_VERSION" \
 '
 {
 disconnectedNodeAcknowledged:false,
 processGroupRevision:{version:$rev},
 versionControlInformation:{
 version:$regVersion,
 flowId:$flowId,
 bucketId:$bucketId,
 registryId:$registryId,
 groupId:$groupId
 }
 }')

 curl -k -s -X POST \
 "$NIFI_URL/nifi-api/versions/update-requests/process-groups/$PG_ID" \
 -H "$AUTH_HEADER" \
 -H "Content-Type: application/json" \
 -d "$UPDATE_PAYLOAD"
}

########################################
# Update Parameter Context
########################################

update_parameter_context() {

 PC_ID=$(curl -k -s \
 "$NIFI_URL/nifi-api/flow/parameter-contexts" \
 -H "$AUTH_HEADER" |
 jq -r '.parameterContexts[]
 | select(.component.name=="circuit-manager-params")
 | .component.id // empty')

 if [ -z "$PC_ID" ]; then
  log_warn "Parameter context not found"
  return
 fi

 CURRENT=$(curl -k -s \
 "$NIFI_URL/nifi-api/parameter-contexts/$PC_ID" \
 -H "$AUTH_HEADER")

 REVISION=$(echo "$CURRENT" | jq -r '.revision.version')

 UPDATED=$(echo "$CURRENT" | jq \
 --arg url "$FRAGMENT_MANAGER_URL" '
 .component.parameters |= map(
   if .parameter.name=="fragment-manager-url"
   then .parameter.value=$url
   else .
   end
 )')

 PAYLOAD=$(echo "$UPDATED" | jq \
 --argjson rev "$REVISION" '
 { revision:{version:$rev}, component:.component }')

 curl -k -s -X PUT \
 "$NIFI_URL/nifi-api/parameter-contexts/$PC_ID" \
 -H "$AUTH_HEADER" \
 -H "Content-Type: application/json" \
 -d "$PAYLOAD"

 log_info "Parameter context updated"
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

 log_section "Processing flow: $FLOW_NAME"

 prepare_flow "$ORIGINAL_FLOW"
 calculate_hash
 lookup_registry_flow
 get_registry_hash
 find_pg

 if [ "$LOCAL_HASH" = "$REG_HASH" ] && [ -n "$PG_ID" ]; then
  log_info "Flow unchanged. Skipping."
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
 else
  update_flow_version
 fi

 update_parameter_context

 log_info "Deployment complete for $FLOW_NAME"

done

log_section "All flows processed"


