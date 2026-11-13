#!/bin/bash
set -euo pipefail
# set +e

#########################################
# deployer.sh — NiFi flow deployment (runs as the flow-deployer Kubernetes Job)
#
# Changes from the previous version:
#  1. PARAM_ handling: works with zero PARAM_ variables, keeps values that contain "=",
#     escapes values properly (built with jq). Names are used as-is: PARAM_mq-exchange-name -> mq-exchange-name
#  2. inject_parameters: values are now really written into the flow that is uploaded
#     (handles the flow-definition format {name,value} as well as {parameter:{name,value}}; never sensitive ones)
#  3. update_parameter_contexts: only the parameter context bound to the deployed process group,
#     only changed non-sensitive values, through NiFi's update-request API (waits for completion).
#     Also runs for UNCHANGED flows, so a PARAM_ change is applied without a new flow version.
#  4. log_error is defined; every NiFi/Registry call fails the job on HTTP >= 400 (with the response shown)
#  5. Hash = sha256 of the flow file — the same as the Flow Deployer wizard, so both agree on "unchanged".
#     (Parameters are no longer part of the hash; they are applied by step 3.)
#     NOTE: the first run after this change re-uploads each flow once (old hash format in the Registry).
#  6. find_or_create_bucket: REGISTRY_BUCKET_ID, or BUCKET_NAME (found or created in the Registry)
#  7. Process-group positions with decimals no longer break the arithmetic
#  8. ensure_registry_client: works on a fresh NiFi — REGISTRY_ID is optional; if it is empty or no longer
#     exists, an existing NiFi Registry client for the same URL is used, or one is created
#  9. PARAM_ names match NiFi parameter names ignoring -/_/. differences
#########################################

#########################################
# CONFIG
#########################################

NIFI_USER="${SINGLE_USER_CREDENTIALS_USERNAME}"
NIFI_PASSWORD="${SINGLE_USER_CREDENTIALS_PASSWORD}"

REG_CLIENT_ID="${REGISTRY_ID:-}"             # optional: found or created when empty/unknown (ensure_registry_client)
REGISTRY_CLIENT_NAME="${REGISTRY_CLIENT_NAME:-NiFi Registry}"
REGISTRY_CLIENT_URL="${REGISTRY_CLIENT_URL:-}"   # Registry URL as NiFi reaches it (default: REGISTRY_URL)
REGISTRY_SSL_CONTEXT_SERVICE_ID="${REGISTRY_SSL_CONTEXT_SERVICE_ID:-}"   # only for an https Registry
BUCKET_ID="${REGISTRY_BUCKET_ID:-}"          # set this, or BUCKET_NAME
BUCKET_NAME="${BUCKET_NAME:-}"               # used when REGISTRY_BUCKET_ID is empty (found or created)
BUCKET_DESC="${BUCKET_DESC:-Created by NiFi flow deployer}"

TARGET_RPG_URL="${TARGET_RPG_URL}"

NIFI_URL="${NIFI_URL}"
REGISTRY_URL="${REGISTRY_URL}"

START_FLOW="${START_FLOW:-false}"

POSITION_STEP=400

FLOWS_DIR="${FLOWS_DIR:-/flows}"
POLL_SECONDS="${POLL_SECONDS:-5}"

#########################################
# Logging
#########################################

timestamp() { date +"%Y-%m-%d %H:%M:%S"; }
log_info()  { echo "[INFO]  $(timestamp)  $1"; }
log_warn()  { echo "[WARN]  $(timestamp)  $1"; }
log_error() { echo "[ERROR] $(timestamp)  $1" >&2; }

log_section() {
  echo ""
  echo "=========================================================================="
  echo "[SECTION] $(timestamp)  $1"
  echo "=========================================================================="
}

# Which flow failed, if the script stops because of an error (the cause is logged just above it)
CURRENT_FLOW=""
on_exit() {
  local rc=$?
  if [ "$rc" -ne 0 ]; then
    log_error "Deployment FAILED${CURRENT_FLOW:+ while processing $CURRENT_FLOW} (exit code $rc)"
  fi
}
trap on_exit EXIT

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
# HTTP helper
#########################################

# api METHOD URL [extra curl args...]
# Prints the response body. Fails (returns 1, logs method/URL/status/body) on connection errors or HTTP >= 400.
api() {
  local method="$1" url="$2"
  shift 2
  local out code
  if ! out=$(curl -k -sS -X "$method" -w $'\n%{http_code}' "$url" "$@"); then
    log_error "$method $url - connection failed"
    return 1
  fi
  code="${out##*$'\n'}"
  out="${out%$'\n'*}"
  if [ "$code" -ge 400 ]; then
    log_error "$method $url - HTTP $code: ${out:0:500}"
    return 1
  fi
  printf '%s' "$out"
}

#########################################
# Wait for NiFi
#########################################

wait_for_nifi() {
  log_section "Waiting for NiFi API"

  until curl -k -s -f -o /dev/null -X POST \
    "$NIFI_URL/nifi-api/access/token" \
    -H "Content-Type: application/x-www-form-urlencoded" \
    --data-urlencode "username=$NIFI_USER" \
    --data-urlencode "password=$NIFI_PASSWORD"
  do sleep 5; done

  log_info "NiFi API is ready"
}

#########################################
# Authenticate
#########################################

authenticate() {
  TOKEN=$(api POST "$NIFI_URL/nifi-api/access/token" \
    -H "Content-Type: application/x-www-form-urlencoded" \
    --data-urlencode "username=$NIFI_USER" \
    --data-urlencode "password=$NIFI_PASSWORD")

  if [ -z "$TOKEN" ]; then
    log_error "NiFi returned an empty access token"
    exit 1
  fi

  AUTH_HEADER="Authorization: Bearer $TOKEN"
}

#########################################
# Root PG
#########################################

get_root_pg() {
  ROOT_PG=$(api GET "$NIFI_URL/nifi-api/flow/process-groups/root" \
    -H "$AUTH_HEADER" |
    jq -r '.processGroupFlow.id')
}

#########################################
# Position
#########################################

get_next_position() {
  # floor: NiFi positions can have decimals, bash arithmetic can't
  MAX_X=$(api GET "$NIFI_URL/nifi-api/flow/process-groups/$ROOT_PG" \
    -H "$AUTH_HEADER" |
    jq '[.processGroupFlow.flow.processGroups[].position.x] | max | if . == null then null else floor end')

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
# Registry client (NiFi -> NiFi Registry connection)
#########################################

# Makes sure NiFi has a working NiFi Registry client and sets REG_CLIENT_ID:
#   1. REGISTRY_ID given and it exists in this NiFi -> use it
#   2. else an existing NiFi Registry client pointing at the same URL (or with REGISTRY_CLIENT_NAME) -> use it
#   3. else create one (fresh NiFi). An https Registry also needs a controller-level SSL Context Service.
ensure_registry_client() {
  log_section "Registry client"
  local client_url="${REGISTRY_CLIENT_URL:-$REGISTRY_URL}"
  client_url="${client_url%/}"

  if [ -n "$REG_CLIENT_ID" ]; then
    local code
    code=$(curl -k -s -o /dev/null -w '%{http_code}' \
      "$NIFI_URL/nifi-api/controller/registry-clients/$REG_CLIENT_ID" -H "$AUTH_HEADER")
    if [ "$code" = "200" ]; then
      log_info "Using registry client from REGISTRY_ID: $REG_CLIENT_ID"
      return 0
    fi
    log_warn "REGISTRY_ID $REG_CLIENT_ID not found in this NiFi (HTTP $code) - looking for / creating one"
    REG_CLIENT_ID=""
  fi

  CLIENTS=$(api GET "$NIFI_URL/nifi-api/controller/registry-clients" -H "$AUTH_HEADER")
  REG_CLIENT_ID=$(echo "$CLIENTS" | jq -r --arg url "$client_url" --arg name "$REGISTRY_CLIENT_NAME" '
    [ .registries[]
      | select(.component.type | endswith("NifiRegistryFlowRegistryClient"))
      | select(((.component.properties.url // "") | rtrimstr("/")) == $url or .component.name == $name)
      | .id ][0] // empty')

  if [ -n "$REG_CLIENT_ID" ]; then
    log_info "Found existing NiFi Registry client: $REG_CLIENT_ID"
  else
    log_info "No NiFi Registry client for $client_url - creating '$REGISTRY_CLIENT_NAME'"

    TYPE=$(api GET "$NIFI_URL/nifi-api/controller/registry-types" -H "$AUTH_HEADER" |
      jq -c '[.flowRegistryClientTypes[] | select(.type | endswith("NifiRegistryFlowRegistryClient"))][0] // empty')
    if [ -z "$TYPE" ]; then
      log_error "This NiFi has no NiFi Registry client type available"
      exit 1
    fi

    local ssl_id=""
    case "$client_url" in
      https://*)
        ssl_id="$REGISTRY_SSL_CONTEXT_SERVICE_ID"
        if [ -z "$ssl_id" ]; then
          ssl_id=$(api GET "$NIFI_URL/nifi-api/flow/controller/controller-services" -H "$AUTH_HEADER" |
            jq -r '[.controllerServices[] | select(.component.type | test("SSLContextService")) | .id][0] // empty')
        fi
        if [ -z "$ssl_id" ]; then
          log_error "Registry is https but NiFi has no controller-level SSL Context Service. Create one (Controller Settings > Management Controller Services) or set REGISTRY_SSL_CONTEXT_SERVICE_ID"
          exit 1
        fi
        ;;
    esac

    PAYLOAD=$(jq -n --argjson t "$TYPE" --arg name "$REGISTRY_CLIENT_NAME" --arg url "$client_url" --arg ssl "$ssl_id" '
      {revision: {version: 0},
       component: {name: $name, type: $t.type, bundle: $t.bundle,
                   properties: ({url: $url} + (if $ssl == "" then {} else {"ssl-context-service": $ssl} end))}}')

    REG_CLIENT_ID=$(api POST "$NIFI_URL/nifi-api/controller/registry-clients" \
      -H "$AUTH_HEADER" -H "Content-Type: application/json" -d "$PAYLOAD" | jq -r '.id')
    log_info "Created NiFi Registry client: $REG_CLIENT_ID -> $client_url"
  fi

  ERRORS=$(api GET "$NIFI_URL/nifi-api/controller/registry-clients/$REG_CLIENT_ID" -H "$AUTH_HEADER" |
    jq -r '(.component.validationErrors // []) | join("; ")')
  if [ -n "$ERRORS" ]; then
    log_error "Registry client $REG_CLIENT_ID is invalid: $ERRORS"
    exit 1
  fi
  log_info "Registry client ready: $REG_CLIENT_ID"
}

#########################################
# Registry
#########################################

find_or_create_bucket() {
  if [ -n "$BUCKET_ID" ]; then
    log_info "Bucket ID (REGISTRY_BUCKET_ID): $BUCKET_ID"
    return 0
  fi
  if [ -z "$BUCKET_NAME" ]; then
    log_error "Set REGISTRY_BUCKET_ID or BUCKET_NAME"
    exit 1
  fi

  log_section "Resolving bucket: $BUCKET_NAME"
  BUCKET_ID=$(api GET "$REGISTRY_URL/nifi-registry-api/buckets" |
    jq -r --arg n "$BUCKET_NAME" '[.[] | select(.name==$n) | .identifier][0] // empty')

  if [ -z "$BUCKET_ID" ]; then
    log_info "Bucket not found - creating"
    BUCKET_ID=$(api POST "$REGISTRY_URL/nifi-registry-api/buckets" \
      -H "Content-Type: application/json" \
      -d "$(jq -n --arg n "$BUCKET_NAME" --arg d "$BUCKET_DESC" '{name:$n, description:$d}')" |
      jq -r '.identifier // empty')
  fi

  [ -n "$BUCKET_ID" ] && [ "$BUCKET_ID" != "null" ] || { log_error "Bucket resolve failed"; exit 1; }
  log_info "Bucket ID: $BUCKET_ID"
}

cache_registry_flows() {
  REGISTRY_FLOW_META=$(api GET "$REGISTRY_URL/nifi-registry-api/buckets/$BUCKET_ID/flows")
}

#########################################
# PARAMS
#########################################

# PARAM_<name>=<value> environment variables -> {"<name>":"<value>",...}
# Works with no PARAM_ variables ({}), values containing "=", quotes etc.
# Names are matched to NiFi parameters exactly, or ignoring -/_/. differences.
build_param_json() {
  PARAM_JSON=$(jq -nc 'env | with_entries(select(.key | startswith("PARAM_")) | .key |= ltrimstr("PARAM_"))')
  log_info "PARAM overrides: $(echo "$PARAM_JSON" | jq -r 'keys | if length == 0 then "(none)" else join(", ") end')"
}

# Writes the PARAM_ values into the flow file that will be uploaded.
inject_parameters() {
  TMP="${FLOW_FILE%.json}.params.json"

  jq --argjson params "$PARAM_JSON" '
  # exact name first, then ignoring -/_/. differences (PARAM_fragment_manager_url matches fragment-manager-url)
  def norm: gsub("[-._]"; "_");
  ($params | with_entries(.key |= norm)) as $pn
  | def val($n): ($params[$n] // $pn[$n | norm]);
  (.. | objects | select(has("parameters") and (.parameters | type) == "array") | .parameters) |=
  map(
    if (.parameter? | type) == "object" then
      # NiFi API shape: {parameter:{name,value,sensitive}}
      if ((.parameter.sensitive // false) | not) and val(.parameter.name) != null
      then .parameter.value = val(.parameter.name) else . end
    elif (.name? | type) == "string" then
      # flow-definition export shape: {name,value,sensitive}
      if ((.sensitive // false) | not) and val(.name) != null
      then .value = val(.name) else . end
    else . end
  )
  ' "$FLOW_FILE" > "$TMP"

  FLOW_FILE="$TMP"
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

# sha256 of the flow file as committed — identical to the Flow Deployer wizard's hash.
calculate_hash() {
  log_info "Calculate Hash"
  LOCAL_HASH=$(sha256sum "$ORIGINAL_FLOW" | cut -d' ' -f1)
}

#########################################
# Registry ops
#########################################

lookup_registry_flow() {
  FLOW_ID=$(echo "$REGISTRY_FLOW_META" |
    jq -r --arg n "$FLOW_NAME" '[.[] | select(.name==$n) | .identifier][0] // empty')
}

get_registry_hash() {
  REG_HASH=$(echo "$REGISTRY_FLOW_META" |
    jq -r --arg id "$FLOW_ID" '[.[] | select(.identifier==$id) | (.description // "") | sub("flow-hash:";"")][0] // empty')
}

create_registry_flow() {

  log_info "Creating new registry flow"

  FLOW_ID=$(api POST "$REGISTRY_URL/nifi-registry-api/buckets/$BUCKET_ID/flows" \
    -H "Content-Type: application/json" \
    -d "$(jq -n --arg n "$FLOW_NAME" --arg d "flow-hash:$LOCAL_HASH" '{name:$n, description:$d}')" |
    jq -r '.identifier')

}

get_latest_version() {
  # 404 = the flow has no versions yet
  local out code
  out=$(curl -k -sS -w $'\n%{http_code}' \
    "$REGISTRY_URL/nifi-registry-api/buckets/$BUCKET_ID/flows/$FLOW_ID/versions/latest")
  code="${out##*$'\n'}"
  out="${out%$'\n'*}"

  if [ "$code" = "404" ]; then
    REG_VERSION=0
  elif [ "$code" -ge 400 ]; then
    log_error "GET versions/latest - HTTP $code: ${out:0:500}"
    return 1
  else
    REG_VERSION=$(to_int "$(echo "$out" | jq '.snapshotMetadata.version')" 0)
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

  RESPONSE=$(api POST "$REGISTRY_URL/nifi-registry-api/buckets/$BUCKET_ID/flows/$FLOW_ID/versions" \
    -H "Content-Type: application/json" \
    -d "$PAYLOAD")
}

update_registry_hash() {
  api PUT "$REGISTRY_URL/nifi-registry-api/buckets/$BUCKET_ID/flows/$FLOW_ID" \
    -H "Content-Type: application/json" \
    -d "$(jq -n --arg id "$FLOW_ID" --arg n "$FLOW_NAME" --arg d "flow-hash:$LOCAL_HASH" \
          '{identifier:$id, name:$n, description:$d}')" > /dev/null
}

#########################################
# PG
#########################################

find_pg() {
  PG_ID=$(api GET "$NIFI_URL/nifi-api/flow/process-groups/$ROOT_PG" \
    -H "$AUTH_HEADER" |
    jq -r --arg n "$FLOW_NAME" '[.processGroupFlow.flow.processGroups[]
    | select(.component.name==$n) | .component.id][0] // empty')
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

  RESPONSE=$(api POST "$NIFI_URL/nifi-api/process-groups/root/process-groups" \
    -H "$AUTH_HEADER" \
    -H "Content-Type: application/json" \
    -d "$PAYLOAD")

  POS_X=$((POS_X + POSITION_STEP))
}

#########################################
# Update version
#########################################

update_flow_version() {

  REVISION=$(api GET "$NIFI_URL/nifi-api/process-groups/$PG_ID" \
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

  RESPONSE=$(api POST "$NIFI_URL/nifi-api/versions/update-requests/process-groups/$PG_ID" \
    -H "$AUTH_HEADER" \
    -H "Content-Type: application/json" \
    -d "$PAYLOAD")

  REQUEST_ID=$(echo "$RESPONSE" | jq -r '.request.requestId')

  log_info "Version update request id: $REQUEST_ID"

  while true
  do
    STATUS_RESPONSE=$(api GET "$NIFI_URL/nifi-api/versions/update-requests/$REQUEST_ID" \
      -H "$AUTH_HEADER")

    COMPLETE=$(echo "$STATUS_RESPONSE" | jq -r '.request.complete')
    FAILURE=$(echo "$STATUS_RESPONSE" | jq -r '.request.failureReason // empty')

    if [ "$COMPLETE" = "true" ]; then
      api DELETE "$NIFI_URL/nifi-api/versions/update-requests/$REQUEST_ID" -H "$AUTH_HEADER" > /dev/null || true
      if [ -n "$FAILURE" ]; then
        log_error "Flow version update failed: $FAILURE"
        exit 1
      fi

      log_info "Flow version update completed successfully"
      break
    fi

    log_info "Waiting for flow version update..."
    sleep "$POLL_SECONDS"
  done
}

#########################################
# Update comments (not called — kept for reference)
#########################################

update_pg_comments() {

  DEPLOY_TIME=$(date +"%Y-%m-%d %H:%M:%S")
  COMMENTS="version:$NEXT_VERSION | hash:$LOCAL_HASH | deployed:$DEPLOY_TIME"

  REVISION=$(api GET "$NIFI_URL/nifi-api/process-groups/$PG_ID" \
    -H "$AUTH_HEADER" | jq -r '.revision.version')

  PAYLOAD=$(jq -n \
    --argjson rev "$REVISION" \
    --arg id "$PG_ID" \
    --arg name "$FLOW_NAME" \
    --arg comments "$COMMENTS" \
    '{ revision:{ version:$rev }, component:{ id:$id, name:$name, comments:$comments } }')

  RESPONSE=$(api PUT "$NIFI_URL/nifi-api/process-groups/$PG_ID" \
    -H "$AUTH_HEADER" \
    -H "Content-Type: application/json" \
    -d "$PAYLOAD")
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

  RESPONSE=$(api PUT "$NIFI_URL/nifi-api/flow/process-groups/$PG_ID" \
    -H "$AUTH_HEADER" \
    -H "Content-Type: application/json" \
    -d "$PAYLOAD")
}

#########################################
# Parameter contexts
#########################################

# Applies PARAM_ values to the parameter context bound to THIS flow's process group only.
# Only existing, non-sensitive parameters whose value differs are sent (update-request API).
update_parameter_contexts() {

  if [ "$PARAM_JSON" = "{}" ]; then
    log_info "No PARAM_ overrides - parameter context unchanged"
    return 0
  fi

  PC_ID=$(api GET "$NIFI_URL/nifi-api/process-groups/$PG_ID" \
    -H "$AUTH_HEADER" | jq -r '.component.parameterContext.id // empty')

  if [ -z "$PC_ID" ]; then
    log_info "$FLOW_NAME has no parameter context - skipping"
    return 0
  fi

  CURRENT=$(api GET "$NIFI_URL/nifi-api/parameter-contexts/$PC_ID" \
    -H "$AUTH_HEADER")

  CHANGES=$(echo "$CURRENT" | jq -c --argjson p "$PARAM_JSON" '
    def norm: gsub("[-._]"; "_");
    ($p | with_entries(.key |= norm)) as $pn
    | [ .component.parameters[]?.parameter
        | select((.sensitive // false) | not)
        | (($p[.name] // $pn[.name | norm])) as $v
        | select($v != null and .value != $v)
        | {parameter: {name: .name, value: $v, sensitive: false}} ]')

  if [ "$CHANGES" = "[]" ]; then
    log_info "Parameter context $(echo "$CURRENT" | jq -r '.component.name') already up to date"
    return 0
  fi

  log_info "Updating parameters: $(echo "$CHANGES" | jq -r '[.[].parameter.name] | join(", ")')"

  PAYLOAD=$(echo "$CURRENT" | jq -c --argjson changes "$CHANGES" \
    '{id: .id, revision: .revision, component: {id: .id, parameters: $changes}}')

  REQ=$(api POST "$NIFI_URL/nifi-api/parameter-contexts/$PC_ID/update-requests" \
    -H "$AUTH_HEADER" \
    -H "Content-Type: application/json" \
    -d "$PAYLOAD")
  REQ_ID=$(echo "$REQ" | jq -r '.request.requestId')

  for i in $(seq 1 120); do
    ST=$(api GET "$NIFI_URL/nifi-api/parameter-contexts/$PC_ID/update-requests/$REQ_ID" \
      -H "$AUTH_HEADER")
    if [ "$(echo "$ST" | jq -r '.request.complete')" = "true" ]; then
      FAILURE=$(echo "$ST" | jq -r '.request.failureReason // empty')
      api DELETE "$NIFI_URL/nifi-api/parameter-contexts/$PC_ID/update-requests/$REQ_ID" \
        -H "$AUTH_HEADER" > /dev/null || true
      if [ -n "$FAILURE" ]; then
        log_error "Parameter context update failed: $FAILURE"
        return 1
      fi
      log_info "Parameter context updated"
      return 0
    fi
    log_info "Waiting for parameter context update... ($i)"
    sleep "$POLL_SECONDS"
  done

  log_error "Timed out waiting for parameter context update"
  return 1
}

#########################################
# Enable controller services
#########################################
enable_controller_services() {
  log_info "Enabling controller services for $FLOW_NAME..."

  SERVICES=$(api GET "$NIFI_URL/nifi-api/flow/process-groups/$PG_ID/controller-services" \
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

  api PUT "$NIFI_URL/nifi-api/flow/process-groups/$PG_ID/controller-services" \
    -H "$AUTH_HEADER" \
    -H "Content-Type: application/json" \
    -d "$PAYLOAD" > /dev/null

  log_info "Enable request sent - polling..."

  for i in $(seq 1 30); do
    CURRENT=$(api GET "$NIFI_URL/nifi-api/flow/process-groups/$PG_ID/controller-services" \
      -H "$AUTH_HEADER")

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
log_info "BUCKET     : ${BUCKET_ID:-$BUCKET_NAME}"
log_info "START_FLOW : $START_FLOW"

SKIPPED_FLOWS=0
DEPLOYED_FLOWS=0
TOTAL_FLOWS=0


wait_for_nifi
authenticate
ensure_registry_client
find_or_create_bucket
get_root_pg
get_next_position
cache_registry_flows
build_param_json

for ORIGINAL_FLOW in "$FLOWS_DIR"/*.json; do

  [ -e "$ORIGINAL_FLOW" ] || continue

  FLOW_NAME=$(jq -r '.flowContents.name // .header.flowName' "$ORIGINAL_FLOW")
  CURRENT_FLOW="$FLOW_NAME"

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
    log_info "No changes detected - skipping new version of $FLOW_NAME"
    update_parameter_contexts          # PARAM_ changes still applied without a new version
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

CURRENT_FLOW=""

# Summary
#########################################

log_section "Deployment Summary"
log_info "Total    : $TOTAL_FLOWS"
log_info "Deployed : $DEPLOYED_FLOWS"
log_info "Skipped  : $SKIPPED_FLOWS"


log_section "All flows deployed"
