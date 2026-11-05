#!/bin/bash
# =============================================================================
# Changes to the existing NiFi deploy script so it works with Flow Deployer.
#
# All changes are backwards compatible: if the new env vars are not set, the
# script behaves exactly as before (same as running the Job by hand).
#
#   BUCKET_NAME        find-or-create the Registry bucket by name (if REGISTRY_BUCKET_ID is empty)
#   FLOWS              comma-separated flow names to deploy (empty = all /flows/*.json)
#   PARAM_OVERRIDES    JSON {"flowName":{"param":"value"}} - non-sensitive values only
#   DEPLOY_COMMENT     used as the Registry version comment
#   PROGRESS_MARKERS   "true" -> print ::progress / ::summary lines for the UI
#   DRY_RUN            "true" -> hash + compare only, change nothing
#
# Paste the functions below into the script (e.g. after the Logging section)
# and apply the marked edits to the main loop.
# =============================================================================

# ---------------------------------------------------------------------------
# 1. Progress markers (the UI reads these; humans can ignore them)
#    Stages: PREPARE HASH REGISTRY_UPLOAD IMPORT CONTROLLER_SERVICES PARAMETERS START
#    Status: started | done | failed | skipped
# ---------------------------------------------------------------------------
progress() {
  [ "${PROGRESS_MARKERS:-false}" = "true" ] || return 0
  echo "::progress flow=$1 stage=$2 status=$3${4:+ detail=$4}"
}

summary_marker() {
  [ "${PROGRESS_MARKERS:-false}" = "true" ] || return 0
  echo "::summary total=$1 deployed=$2 skipped=$3"
}

# If anything fails mid-flow, mark the current stage failed so the UI shows where.
CURRENT_FLOW=""
CURRENT_STAGE=""
stage() {                       # usage: stage <STAGE>   (marks previous stage done, new one started)
  [ -n "$CURRENT_STAGE" ] && progress "$CURRENT_FLOW" "$CURRENT_STAGE" done
  CURRENT_STAGE="$1"
  progress "$CURRENT_FLOW" "$CURRENT_STAGE" started "${2:-}"
}
on_error() {
  [ -n "$CURRENT_FLOW" ] && [ -n "$CURRENT_STAGE" ] && progress "$CURRENT_FLOW" "$CURRENT_STAGE" failed
}
trap on_error ERR

# ---------------------------------------------------------------------------
# 2. Find or create the Registry bucket, and get its ID
#    Call right after `authenticate`.
# ---------------------------------------------------------------------------
find_or_create_bucket() {
  if [ -n "${BUCKET_ID:-}" ]; then
    log_info "Using bucket ID from environment: $BUCKET_ID"
    return 0
  fi
  if [ -z "${BUCKET_NAME:-}" ]; then
    log_warn "Neither REGISTRY_BUCKET_ID nor BUCKET_NAME is set"
    exit 1
  fi

  log_section "Resolving bucket: $BUCKET_NAME"
  BUCKET_ID=$(curl -sk "$REGISTRY_URL/nifi-registry-api/buckets" |
    jq -r --arg n "$BUCKET_NAME" '.[] | select(.name==$n) | .identifier' | head -n1)

  if [ -z "$BUCKET_ID" ]; then
    log_info "Bucket not found - creating"
    BUCKET_ID=$(curl -sk -X POST "$REGISTRY_URL/nifi-registry-api/buckets" \
      -H "Content-Type: application/json" \
      -d "$(jq -n --arg n "$BUCKET_NAME" '{name:$n, description:"Created by NiFi deploy job"}')" |
      jq -r '.identifier // empty')
  fi

  if [ -z "$BUCKET_ID" ] || [ "$BUCKET_ID" = "null" ]; then
    log_warn "Could not resolve or create bucket $BUCKET_NAME"
    exit 1
  fi
  log_info "Bucket ID: $BUCKET_ID"
}

# ---------------------------------------------------------------------------
# 3. Only deploy the flows chosen in the UI
# ---------------------------------------------------------------------------
flow_selected() {               # usage: flow_selected "$FLOW_NAME" || continue
  [ -z "${FLOWS:-}" ] && return 0
  case ",$FLOWS," in *",$1,"*) return 0 ;; *) return 1 ;; esac
}

# ---------------------------------------------------------------------------
# 4. Apply non-sensitive parameter overrides to the prepared flow file
#    Call after inject_parameters, before calculate_hash.
# ---------------------------------------------------------------------------
apply_param_overrides() {       # usage: apply_param_overrides "$FLOW_FILE" "$FLOW_NAME"
  local file="$1" flow="$2" all ovr
  all="${PARAM_OVERRIDES:-}"
  [ -z "$all" ] && all='{}'
  ovr=$(echo "$all" | jq -c --arg f "$flow" '.[$f] // {}')
  [ "$ovr" = "{}" ] && return 0

  log_info "Applying parameter overrides: $(echo "$ovr" | jq -r 'keys | join(", ")')"
  jq --argjson o "$ovr" '
    if .parameterContexts == null then . else
      .parameterContexts |= with_entries(
        .value.parameters |= map(
          if ($o[.name] != null and (.sensitive | not)) then .value = $o[.name] else . end))
    end' "$file" > "$file.tmp" && mv "$file.tmp" "$file"
}

# ---------------------------------------------------------------------------
# 5. Hash that the UI can reproduce
#    sha256 of the ORIGINAL file (+ the overrides for this flow, if any).
#    The backend computes sha256 of the same file, so NEW/CHANGED/UNCHANGED
#    in the UI matches what this script decides.
#    NOTE: after switching, every flow shows CHANGED once (old hash format).
# ---------------------------------------------------------------------------
calculate_hash() {              # needs $ORIGINAL_FLOW and $FLOW_NAME; sets LOCAL_HASH
  local ovr="" all="${PARAM_OVERRIDES:-}"
  [ -n "$all" ] && ovr=$(echo "$all" | jq -c --arg f "$FLOW_NAME" '.[$f] // empty')
  if [ -n "$ovr" ]; then
    LOCAL_HASH=$(cat "$ORIGINAL_FLOW" <(printf '%s' "$ovr") | sha256sum | cut -d' ' -f1)
  else
    LOCAL_HASH=$(sha256sum "$ORIGINAL_FLOW" | cut -d' ' -f1)
  fi
}

# ---------------------------------------------------------------------------
# 6. Main loop - existing structure, with the new calls marked  # <- NEW
# ---------------------------------------------------------------------------
main() {
  SKIPPED_FLOWS=0
  DEPLOYED_FLOWS=0
  TOTAL_FLOWS=0

  wait_for_nifi
  authenticate
  find_or_create_bucket                                          # <- NEW
  get_root_pg
  get_next_position
  cache_registry_flows

  for ORIGINAL_FLOW in /flows/*.json; do
    [ -e "$ORIGINAL_FLOW" ] || continue
    FLOW_NAME=$(jq -r '.flowContents.name // .header.flowName' "$ORIGINAL_FLOW")
    flow_selected "$FLOW_NAME" || continue                        # <- NEW
    TOTAL_FLOWS=$(( TOTAL_FLOWS + 1 ))

    log_section "Processing $FLOW_NAME"
    CURRENT_FLOW="$FLOW_NAME"; CURRENT_STAGE=""                   # <- NEW
    stage PREPARE                                                 # <- NEW
    prepare_flow "$ORIGINAL_FLOW"
    inject_parameters
    apply_param_overrides "$FLOW_FILE" "$FLOW_NAME"               # <- NEW

    stage HASH                                                    # <- NEW
    calculate_hash
    lookup_registry_flow
    get_registry_hash
    find_pg

    log_info "Local hash    : $LOCAL_HASH"
    log_info "Registry hash : $REG_HASH"

    if [ "$LOCAL_HASH" = "$REG_HASH" ] && [ -n "$PG_ID" ]; then
      log_info "No changes detected - skipping $FLOW_NAME"
      progress "$FLOW_NAME" HASH skipped "no changes"             # <- NEW
      CURRENT_STAGE=""
      SKIPPED_FLOWS=$(( SKIPPED_FLOWS + 1 ))
      continue
    fi

    if [ "${DRY_RUN:-false}" = "true" ]; then                     # <- NEW
      log_info "DRY_RUN: would deploy $FLOW_NAME"
      progress "$FLOW_NAME" HASH done "would deploy"
      CURRENT_STAGE=""
      continue
    fi

    stage REGISTRY_UPLOAD                                         # <- NEW
    [ -z "$FLOW_ID" ] && create_registry_flow
    get_latest_version
    COMMENTS="${DEPLOY_COMMENT:-$COMMENTS}"                       # <- NEW (version comment from UI)
    upload_registry_version
    update_registry_hash

    stage IMPORT "v$NEXT_VERSION"                                 # <- NEW
    if [ -z "$PG_ID" ]; then
      import_flow
      find_pg
    else
      update_flow_version
    fi

    stage CONTROLLER_SERVICES                                     # <- NEW
    enable_controller_services

    stage PARAMETERS                                              # <- NEW
    update_parameter_contexts

    stage START                                                   # <- NEW
    control_pg_state
    progress "$FLOW_NAME" START done "v$NEXT_VERSION"             # <- NEW
    CURRENT_STAGE=""

    DEPLOYED_FLOWS=$(( DEPLOYED_FLOWS + 1 ))
    log_info "Done: $FLOW_NAME"
  done

  log_section "Deployment Summary"
  log_info "Total    : $TOTAL_FLOWS"
  log_info "Deployed : $DEPLOYED_FLOWS"
  log_info "Skipped  : $SKIPPED_FLOWS"
  summary_marker "$TOTAL_FLOWS" "$DEPLOYED_FLOWS" "$SKIPPED_FLOWS"   # <- NEW
}

# Also, in the CONFIG section, make the bucket optional:
#   BUCKET_ID="${REGISTRY_BUCKET_ID:-}"
#   BUCKET_NAME="${BUCKET_NAME:-}"
#
# And inside enable_controller_services' poll loop you can surface the poll count:
#   progress "$FLOW_NAME" CONTROLLER_SERVICES started "Poll $i/30: enabled=$ENABLED total=$TOTAL"
