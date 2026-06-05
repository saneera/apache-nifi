#!/bin/bash
set -euo pipefail

####################################
# CONFIG
####################################

NIFI_USER="${SINGLE_USER_CREDENTIALS_USERNAME}"
NIFI_PASSWORD="${SINGLE_USER_CREDENTIALS_PASSWORD}"

REG_CLIENT_ID="${REGISTRY_ID}"
BUCKET_ID="${REGISTRY_BUCKET_ID}"

TARGET_RPG_URL="${TARGET_RPG_URL}"

NIFI_URL="${NIFI_URL}"
REGISTRY_URL="${REGISTRY_URL}"

START_FLOW="${START_FLOW:-false}"

POSITION_STEP=400

####################################
# Logging
####################################

timestamp() { date +"%Y-%m-%d %H:%M:%S"; }
log_info()  { echo "[INFO] $(timestamp) $1"; }
log_warn()  { echo "[WARN] $(timestamp) $1"; }

log_section() {
  echo ""
  echo "=================================================="
  echo "[SECTION] $(timestamp) $1"
  echo "=================================================="
}

####################################
# Helper — safe integer
# Forces value through arithmetic
# to strip whitespace/newlines/nulls
####################################

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

####################################
# Wait for NiFi
####################################

wait_for_nifi() {
  log_section "Waiting for NiFi API"

  until curl -k -s -X POST \
    "$NIFI_URL/nifi-api/access/token" \
    -H "Content-Type: application/x-www-form-urlencoded" \
    -d "username=$NIFI_USER&password=$NIFI_PASSWORD" > /dev/null
  do
    log_info "NiFi not ready yet — retrying in 5s..."
    sleep 5
  done

  log_info "NiFi API is ready"
}

####################################
# Authenticate
####################################

authenticate() {
  TOKEN=$(curl -k -s -X POST \
    "$NIFI_URL/nifi-api/access/token" \
    -H "Content-Type: application/x-www-form-urlencoded" \
    -d "username=$NIFI_USER&password=$NIFI_PASSWORD")

  if [ -z "$TOKEN" ] || [ "$TOKEN" = "null" ]; then
    log_warn "Authentication failed — empty token"
    exit 1
  fi

  AUTH_HEADER="Authorization: Bearer $TOKEN"
  log_info "Authenticated"
}

####################################
# Root PG
####################################

get_root_pg() {
  ROOT_PG=$(curl -k -s \
    "$NIFI_URL/nifi-api/flow/process-groups/root" \
    -H "$AUTH_HEADER" |
    jq -r '.processGroupFlow.id')

  if [ -z "$ROOT_PG" ] || [ "$ROOT_PG" = "null" ]; then
    log_warn "Could not get root PG ID"
    exit 1
  fi

  log_info "Root PG: $ROOT_PG"
}

####################################
# Position
####################################

get_next_position() {
  MAX_X=$(curl -k -s \
    "$NIFI_URL/nifi-api/flow/process-groups/$ROOT_PG" \
    -H "$AUTH_HEADER" |
    jq '[.processGroupFlow.flow.processGroups[].position.x] | max')

  log_info "MAX_X=$MAX_X"

  if [ "$MAX_X" = "null" ] || [ -z "$MAX_X" ]; then
    POS_X=300
  else
    POS_X=$(to_int "$MAX_X" 300)
  fi

  POS_X=$(( POS_X + POSITION_STEP ))
  POS_Y=300

  log_info "POS_X=$POS_X"
}

####################################
# Registry
####################################

cache_registry_flows() {
  REGISTRY_FLOW_META=$(curl -k -s \
    "$REGISTRY_URL/nifi-registry-api/buckets/$BUCKET_ID/flows")
}

####################################
# PARAMS
####################################

build_param_json() {
  PARAM_JSON=$(env | grep '^PARAM_' | awk -F= '
    {
      key=$1
      sub("PARAM_", "", key)
      gsub("_", "-", key)
      printf "\"%s\":\"%s\",", key, $2
    }
    END { print "" }' | sed 's/,$//')
  log_info "PARAM_JSON: ${PARAM_JSON}"
  PARAM_JSON="{${PARAM_JSON}}"
}

inject_parameters() {
  build_param_json
  TMP="/tmp/flow.json"

  jq --argjson params "$PARAM_JSON" \
    '(.. | objects | select(has("parameters"))) | .parameters |=
    map(
      if (.parameter? and .parameter.name? and $params[.parameter.name]? != null) then
        .parameter.value = $params[.parameter.name]
      else .
      end
    )' \
    "$FLOW_FILE" > "$TMP"
}

####################################
# Flow Prep
####################################

prepare_flow() {
  log_info "Prepare flow"
  TMP="/tmp/$(basename "$1")"

  jq --arg url "$TARGET_RPG_URL" \
    '(.flowContents.remoteProcessGroups[]?.targetUris)=$url' \
    "$1" > "$TMP"

  FLOW_FILE="$TMP"
}

####################################
# Hash
####################################

calculate_hash() {
  log_info "Calculate Hash"
  FLOW_HASH=$(jq -S '.flowContents | del(.. | .position?)' "$FLOW_FILE" | sha256sum | cut -d' ' -f1)
  log_info "FLOW_HASH: ${FLOW_HASH}"
  PARAM_HASH=$(jq -S '.parameterContexts // {}' "$FLOW_FILE" | sha256sum | cut -d' ' -f1)
  log_info "PARAM_HASH: ${PARAM_HASH}"
  LOCAL_HASH="${FLOW_HASH}_${PARAM_HASH}"
}

####################################
# Registry ops
####################################

lookup_registry_flow() {
  FLOW_ID=$(echo "$REGISTRY_FLOW_META" |
    jq -r ".[] | select(.name==\"$FLOW_NAME\") | .identifier // empty")
}

get_registry_hash() {
  REG_HASH=$(echo "$REGISTRY_FLOW_META" |
    jq -r ".[] | select(.identifier==\"$FLOW_ID\") | .description | sub(\"flow-hash:\"; \"\"; \"\")")
}

create_registry_flow() {
  log_info "Creating new registry flow: $FLOW_NAME"

  FLOW_ID=$(curl -k -s -X POST \
    "$REGISTRY_URL/nifi-registry-api/buckets/$BUCKET_ID/flows" \
    -H "Content-Type: application/json" \
    -d "{
      \"name\":\"$FLOW_NAME\",
      \"description\":\"flow-hash:$LOCAL_HASH\"
    }" | jq -r '.identifier')

  if [ -z "$FLOW_ID" ] || [ "$FLOW_ID" = "null" ]; then
    log_warn "Failed to create registry flow"
    return 1
  fi

  log_info "Created registry flow: $FLOW_ID"
}

get_latest_version() {
  LATEST=$(curl -k -s \
    "$REGISTRY_URL/nifi-registry-api/buckets/$BUCKET_ID/flows/$FLOW_ID/versions/latest")

  ERROR=$(echo "$LATEST" | jq -r '.message // empty' 2>/dev/null)
  if [ -n "$ERROR" ]; then
    log_warn "Registry version check error: $ERROR — defaulting to 0"
    REG_VERSION=0
  elif echo "$LATEST" | jq . >/dev/null 2>&1; then
    RAW=$(echo "$LATEST" | jq -r '.snapshotMetadata.version // 0')
    REG_VERSION=$(to_int "$RAW" 0)
  else
    REG_VERSION=0
  fi

  NEXT_VERSION=$(( REG_VERSION + 1 ))
  log_info "REG_VERSION=$REG_VERSION → NEXT_VERSION=$NEXT_VERSION"
}

upload_registry_version() {
  log_info "Uploading version $NEXT_VERSION to registry..."

  PAYLOAD=$(jq -n \
    --arg bucket "$BUCKET_ID" \
    --arg flow "$FLOW_ID" \
    --argjson version ${NEXT_VERSION} \
    '.snapshotMetadata.bucketIdentifier=$bucket |
     .snapshotMetadata.flowIdentifier=$flow |
     .snapshotMetadata.version=$version' "$FLOW_FILE")

  RESPONSE=$(curl -k -s -X POST \
    "$REGISTRY_URL/nifi-registry-api/buckets/$BUCKET_ID/flows/$FLOW_ID/versions" \
    -H "Content-Type: application/json" \
    -d "$PAYLOAD")

  log_info "✓ Version $NEXT_VERSION uploaded to registry"
}

update_registry_hash() {
  curl -k -s -X PUT \
    "$REGISTRY_URL/nifi-registry-api/buckets/$BUCKET_ID/flows/$FLOW_ID" \
    -H "Content-Type: application/json" \
    -d "{\"identifier\":\"$FLOW_ID\",\"name\":\"$FLOW_NAME\",\"description\":\"flow-hash:$LOCAL_HASH\"}" \
    > /dev/null
  log_info "✓ Registry hash updated"
}

####################################
# PG
####################################

find_pg() {
  PG_ID=$(curl -k -s \
    "$NIFI_URL/nifi-api/flow/process-groups/$ROOT_PG" \
    -H "$AUTH_HEADER" |
    jq -r ".processGroupFlow.flow.processGroups[]
    | select(.component.name==\"$FLOW_NAME\") | .component.id // empty")
  log_info "PG_ID: ${PG_ID:-not found}"
}

####################################
# Stop PG and wait
####################################

stop_pg_and_wait() {
  log_info "Stopping $FLOW_NAME before update..."

  curl -k -s -X PUT \
    "$NIFI_URL/nifi-api/flow/process-groups/$PG_ID" \
    -H "$AUTH_HEADER" \
    -H "Content-Type: application/json" \
    -d "{\"id\":\"$PG_ID\",\"state\":\"STOPPED\",\"disconnectedNodeAcknowledged\":false}" \
    > /dev/null

  for i in $(seq 1 20); do
    RAW_RUNNING=$(curl -k -s \
      "$NIFI_URL/nifi-api/flow/process-groups/$PG_ID" \
      -H "$AUTH_HEADER" | jq -r '.runningCount // 0')
    RUNNING=$(to_int "$RAW_RUNNING" 0)

    if [ "$RUNNING" = "0" ]; then
      log_info "✓ PG stopped"
      return 0
    fi

    log_info "Waiting for stop... running=$RUNNING ($i/20)"
    sleep 3
  done

  log_warn "PG did not stop cleanly — proceeding anyway"
}

####################################
# Import
####################################

import_flow() {
  log_info "Importing new flow: $FLOW_NAME"

  DEPLOY_TIME=$(date +"%Y-%m-%d %H:%M:%S")
  COMMENTS="version:$NEXT_VERSION | hash:$LOCAL_HASH | deployed:$DEPLOY_TIME"

  PAYLOAD=$(jq -n \
    --arg name "$FLOW_NAME" \
    --arg comments "$COMMENTS" \
    --arg registry "$REG_CLIENT_ID" \
    --arg bucket "$BUCKET_ID" \
    --arg flow "$FLOW_ID" \
    --argjson version ${NEXT_VERSION} \
    --argjson x ${POS_X} \
    --argjson y ${POS_Y} \
    '{
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
    -d "$PAYLOAD")

  log_info "✓ Flow imported: $FLOW_NAME"

  POS_X=$(( POS_X + POSITION_STEP ))
}

####################################
# Update version — async poll
####################################

update_flow_version() {
  log_info "Updating canvas to version $NEXT_VERSION..."

  # Get current PG revision
  PG_RESPONSE=$(curl -k -s \
    "$NIFI_URL/nifi-api/process-groups/$PG_ID" \
    -H "$AUTH_HEADER")

  RAW_REV=$(echo "$PG_RESPONSE" | jq -r '.revision.version // 0')
  REVISION=$(to_int "$RAW_REV" 0)
  REVISION=$(( REVISION + 0 ))

  log_info "Current revision: $REVISION  PG_ID: $PG_ID"

  if ! echo "$REVISION" | grep -qE '^[0-9]+$'; then
    log_warn "Invalid REVISION: '$REVISION'"
    return 1
  fi

  if ! echo "$NEXT_VERSION" | grep -qE '^[0-9]+$'; then
    log_warn "Invalid NEXT_VERSION: '$NEXT_VERSION'"
    return 1
  fi

  # Step 1 — fetch the versioned flow snapshot from registry
  # Fetch latest snapshot from registry
  log_info "Fetching latest snapshot from registry..."
  SNAPSHOT=$(curl -k -s \
    "$REGISTRY_URL/nifi-registry-api/buckets/$BUCKET_ID/flows/$FLOW_ID/versions/latest")

  # Check flowContents exists — that's the real indicator of a valid snapshot
  SNAPSHOT_CHECK=$(echo "$SNAPSHOT" | jq -r '.flowContents.name // empty')

  if [ -z "$SNAPSHOT_CHECK" ]; then
    log_warn "Invalid snapshot — flowContents missing"
    log_warn "Raw response: $SNAPSHOT"
    return 1
  fi

  SNAPSHOT_VER=$(echo "$SNAPSHOT" | jq -r '.snapshotMetadata.version // empty')
  log_info "✓ Snapshot fetched — flow=$SNAPSHOT_CHECK version=$SNAPSHOT_VER"

  # Step 2 — build payload with full snapshot + revision
  PAYLOAD=$(echo "$SNAPSHOT" | jq \
              --argjson rev ${REVISION} \
              --arg regid "$REG_CLIENT_ID" \
              '{
                processGroupRevision: {version: $rev},
                disconnectedNodeAcknowledged: false,
                versionedFlowSnapshot: (. + {
                  snapshotMetadata: (.snapshotMetadata + {registryId: $regid})
                })
   }' > "$PAYLOAD_FILE")

  # Step 3 — PUT to NiFi canvas to apply the snapshot
  log_info "Applying snapshot to canvas..."
  RESPONSE=$(curl -k -s -X POST \
    "$NIFI_URL/nifi-api/versions/update-requests/process-groups/$PG_ID" \
    -H "$AUTH_HEADER" \
    -H "Content-Type: application/json" \
    --data-binary "@$PAYLOAD_FILE")

  log_info "Response: $RESPONSE"

  ERROR=$(echo "$RESPONSE" | jq -r '.message // empty')
  if [ -n "$ERROR" ]; then
    log_warn "Version update failed: $ERROR"
    return 1
  fi

  UPDATED_VER=$(echo "$RESPONSE" | \
    jq -r '.versionControlInformation.version // empty')

  if [ -n "$UPDATED_VER" ]; then
    log_info "✓ Canvas updated to version $UPDATED_VER"
    return 0
  fi

  RESP_REV=$(echo "$RESPONSE" | jq -r '.processGroupRevision.version // empty')
  if [ -n "$RESP_REV" ]; then
    log_info "✓ Canvas updated successfully (revision: $RESP_REV)"
    return 0
  fi

  log_warn "Unexpected response: $RESPONSE"
  return 1
}

####################################
# Update comments
####################################

update_pg_comments() {
  DEPLOY_TIME=$(date +"%Y-%m-%d %H:%M:%S")
  COMMENTS="version:$NEXT_VERSION | hash:$LOCAL_HASH | deployed:$DEPLOY_TIME"

  PG_RESPONSE=$(curl -k -s \
    "$NIFI_URL/nifi-api/process-groups/$PG_ID" \
    -H "$AUTH_HEADER")

  RAW_REV=$(echo "$PG_RESPONSE" | jq -r '.revision.version // 0')
  REVISION=$(to_int "$RAW_REV" 0)
  REVISION=$(( REVISION + 0 ))

  if ! echo "$REVISION" | grep -qE '^[0-9]+$'; then
    log_warn "Skipping comment update — invalid revision: '$REVISION'"
    return 0
  fi

  PAYLOAD=$(jq -n \
    --argjson rev ${REVISION} \
    --arg id "$PG_ID" \
    --arg name "$FLOW_NAME" \
    --arg comments "$COMMENTS" \
    '{
      revision:{version:$rev},
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
    -d "$PAYLOAD")

  log_info "✓ PG comments updated"
}

####################################
# Enable controller services
####################################

enable_controller_services() {
  log_info "Enabling controller services for $FLOW_NAME..."

  SERVICES=$(curl -k -s \
    "$NIFI_URL/nifi-api/flow/process-groups/$PG_ID/controller-services" \
    -H "$AUTH_HEADER")

  RAW_COUNT=$(echo "$SERVICES" | jq '.controllerServices | length')
  SERVICE_COUNT=$(to_int "$RAW_COUNT" 0)
  log_info "Found $SERVICE_COUNT controller service(s)"

  if [ "$SERVICE_COUNT" = "0" ]; then
    log_info "No controller services to enable — skipping"
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

  log_info "Enable request sent — polling..."

  for i in $(seq 1 30); do
    CURRENT=$(curl -k -s \
      "$NIFI_URL/nifi-api/flow/process-groups/$PG_ID/controller-services" \
      -H "$AUTH_HEADER")

    TOTAL=$(to_int "$(echo "$CURRENT"    | jq '.controllerServices | length')" 0)
    ENABLED=$(to_int "$(echo "$CURRENT"  | jq '[.controllerServices[] | select(.component.state == "ENABLED")]  | length')" 0)
    DISABLED=$(to_int "$(echo "$CURRENT" | jq '[.controllerServices[] | select(.component.state == "DISABLED")] | length')" 0)
    ENABLING=$(to_int "$(echo "$CURRENT" | jq '[.controllerServices[] | select(.component.state == "ENABLING")] | length')" 0)

    log_info "Poll $i/30: enabled=$ENABLED enabling=$ENABLING disabled=$DISABLED total=$TOTAL"

    if [ "$ENABLED" = "$TOTAL" ]; then
      log_info "✓ All controller services enabled"
      return 0
    fi

    # Stuck — nothing is enabling but some still disabled after 5 polls
    if [ "$ENABLING" = "0" ] && [ "$DISABLED" != "0" ] && [ "$i" -gt 5 ]; then
      log_warn "Some services stuck in DISABLED — listing:"
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

####################################
# Start / Stop PG
####################################

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
    '{id:$id,state:$state,disconnectedNodeAcknowledged:false}')

  RESPONSE=$(curl -k -s -X PUT \
    "$NIFI_URL/nifi-api/flow/process-groups/$PG_ID" \
    -H "$AUTH_HEADER" \
    -H "Content-Type: application/json" \
    -d "$PAYLOAD")

  log_info "✓ PG state set to $STATE"
}

####################################
# Parameter contexts
####################################

update_parameter_contexts() {
  log_info "Updating parameter contexts..."

  build_param_json

  CONTEXTS=$(curl -k -s \
    "$NIFI_URL/nifi-api/flow/parameter-contexts" \
    -H "$AUTH_HEADER")

  echo "$CONTEXTS" | jq -c '.parameterContexts[]' | while read -r ctx; do

    PC_ID=$(echo "$ctx" | jq -r '.component.id')
    PC_NAME=$(echo "$ctx" | jq -r '.component.name')

    log_info "Updating parameter context: $PC_NAME ($PC_ID)"

    CURRENT=$(curl -k -s \
      "$NIFI_URL/nifi-api/parameter-contexts/$PC_ID" \
      -H "$AUTH_HEADER")

    RAW_REV=$(echo "$CURRENT" | jq -r '.revision.version // 0')
    REVISION=$(to_int "$RAW_REV" 0)
    REVISION=$(( REVISION + 0 ))

    if ! echo "$REVISION" | grep -qE '^[0-9]+$'; then
      log_warn "Skipping parameter context $PC_NAME — invalid revision: '$REVISION'"
      continue
    fi

    UPDATED=$(echo "$CURRENT" | jq --argjson params "$PARAM_JSON" \
      '.component.parameters |= map(
        .parameter.name as $n
        | if $params[$n] != null
          then .parameter.value = $params[$n]
          else .
          end
      )')

    PAYLOAD=$(echo "$UPDATED" | jq --argjson rev ${REVISION} \
      '{revision:{version:$rev},component:.component}')

    RESPONSE=$(curl -k -s -X PUT \
      "$NIFI_URL/nifi-api/parameter-contexts/$PC_ID" \
      -H "$AUTH_HEADER" \
      -H "Content-Type: application/json" \
      -d "$PAYLOAD")

    PC_RESULT=$(echo "$RESPONSE" | jq -r '.component.name // empty')
    if [ -n "$PC_RESULT" ]; then
      log_info "✓ Parameter context updated: $PC_NAME"
    else
      log_warn "✗ Failed to update: $PC_NAME — $(echo "$RESPONSE" | jq -r '.message // .')"
    fi

  done
}

####################################
# MAIN
####################################

log_section "NiFi Deployment Started"
log_info "NIFI_URL   : $NIFI_URL"
log_info "REGISTRY   : $REGISTRY_URL"
log_info "BUCKET     : $BUCKET_ID"
log_info "START_FLOW : $START_FLOW"

FAILED_FLOWS=""
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

  log_section "Processing: $FLOW_NAME"

  prepare_flow "$ORIGINAL_FLOW"
  inject_parameters
  calculate_hash
  lookup_registry_flow
  get_registry_hash
  find_pg

  log_info "Local hash    : $LOCAL_HASH"
  log_info "Registry hash : $REG_HASH"

  if [ "$LOCAL_HASH" = "$REG_HASH" ] && [ -n "$PG_ID" ]; then
    log_info "No changes detected — skipping $FLOW_NAME"
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
    stop_pg_and_wait
    update_flow_version
    # update_pg_comments
  fi

  enable_controller_services
  update_parameter_contexts
  control_pg_state

  DEPLOYED_FLOWS=$(( DEPLOYED_FLOWS + 1 ))
  log_info "✓ Done: $FLOW_NAME"

done

####################################
# Summary
####################################

log_section "Deployment Summary"
log_info "Total    : $TOTAL_FLOWS"
log_info "Deployed : $DEPLOYED_FLOWS"
log_info "Skipped  : $SKIPPED_FLOWS"

if [ -n "$FAILED_FLOWS" ]; then
  log_warn "Failed   : $FAILED_FLOWS"
  exit 1
fi

log_section "All flows deployed successfully"
