#!/usr/bin/env bash
set -e

NIFI_URL=${NIFI_URL}
REGISTRY_ID=${REGISTRY_ID}
TOKEN=${TOKEN}
FLOW_DIR=${FLOW_DIR:-flows}
ROOT_PG_ID=${ROOT_PG_ID}

########################################
# Generic API wrapper
########################################
api() {
  curl -s -k \
   -H "Authorization: Bearer $TOKEN" \
   -H "Content-Type: application/json" \
   "$@"
}

########################################
# Calculate flow hash
########################################
calculate_local_hash() {
  jq -S '.' "$1" | sha256sum | awk '{print $1}'
}

########################################
# Get PG revision
########################################
get_pg_revision() {
  api "$NIFI_URL/nifi-api/process-groups/$1" \
  | jq '.revision.version'
}

########################################
# Get PG by name
########################################
find_pg_by_name() {

  local name=$1

  api "$NIFI_URL/nifi-api/process-groups/$ROOT_PG_ID/process-groups" \
  | jq -r --arg NAME "$name" '
      .processGroups[]
      | select(.component.name==$NAME)
      | .component.id'
}

########################################
# Create new process group
########################################
create_pg() {

  local name=$1

  echo "Creating process group: $name"

  local payload=$(jq -n \
   --arg name "$name" \
  '{
     revision:{version:0},
     component:{
        name:$name,
        position:{x:0,y:0}
     }
  }')

  api -X POST \
   "$NIFI_URL/nifi-api/process-groups/$ROOT_PG_ID/process-groups" \
   -d "$payload" \
   | jq -r '.id'
}

########################################
# Get version control info
########################################
get_version_info() {

  api "$NIFI_URL/nifi-api/versions/process-groups/$1"
}

########################################
# Check if PG is versioned
########################################
is_versioned() {

  get_version_info "$1" \
  | jq '.versionControlInformation != null'
}

########################################
# Start version control
########################################
start_version_control() {

  PG_ID=$1
  FLOW_NAME=$2
  BUCKET_ID=$3

  REV=$(get_pg_revision "$PG_ID")

  echo "Starting version control for $FLOW_NAME"

  payload=$(jq -n \
   --arg bucket "$BUCKET_ID" \
   --arg flowName "$FLOW_NAME" \
   --arg registry "$REGISTRY_ID" \
   --argjson rev "$REV" \
  '{
    processGroupRevision:{version:$rev},
    versionControlInformation:{
       registryId:$registry,
       bucketId:$bucket,
       flowName:$flowName
    }
  }')

  api -X POST \
   "$NIFI_URL/nifi-api/versions/process-groups/$PG_ID" \
   -d "$payload"
}

########################################
# Commit registry version
########################################
commit_registry_version() {

  PG_ID=$1
  COMMENT=$2

  INFO=$(get_version_info "$PG_ID")

  BUCKET=$(echo "$INFO" | jq -r '.versionControlInformation.bucketId')
  FLOW=$(echo "$INFO" | jq -r '.versionControlInformation.flowId')

  REV=$(get_pg_revision "$PG_ID")

  echo "Committing registry version..."

  payload=$(jq -n \
   --arg bucket "$BUCKET" \
   --arg flow "$FLOW" \
   --arg comment "$COMMENT" \
   --argjson rev "$REV" \
  '{
     processGroupRevision:{version:$rev},
     versionedFlow:{
        bucketId:$bucket,
        flowId:$flow,
        action:"COMMIT",
        comment:$comment
     }
  }')

  api -X POST \
   "$NIFI_URL/nifi-api/versions/process-groups/$PG_ID" \
   -d "$payload"
}

########################################
# Update PG to latest registry version
########################################
update_pg_to_latest() {

  PG_ID=$1

  INFO=$(get_version_info "$PG_ID")

  BUCKET=$(echo "$INFO" | jq -r '.versionControlInformation.bucketId')
  FLOW=$(echo "$INFO" | jq -r '.versionControlInformation.flowId')
  VERSION=$(echo "$INFO" | jq -r '.versionControlInformation.version')

  REV=$(get_pg_revision "$PG_ID")

  echo "Updating PG to version $VERSION"

  payload=$(jq -n \
   --arg bucket "$BUCKET" \
   --arg flow "$FLOW" \
   --argjson version "$VERSION" \
   --argjson rev "$REV" \
  '{
     processGroupRevision:{version:$rev},
     versionControlInformation:{
        bucketId:$bucket,
        flowId:$flow,
        version:$version
     }
  }')

  api -X POST \
   "$NIFI_URL/nifi-api/versions/update-requests/process-groups/$PG_ID" \
   -d "$payload"
}

########################################
# Deploy flows
########################################
deploy_flows() {

for FLOW_FILE in $FLOW_DIR/*.json
do

  FLOW_NAME=$(basename "$FLOW_FILE" .json)

  echo "----------------------------------"
  echo "Processing flow: $FLOW_NAME"

  PG_ID=$(find_pg_by_name "$FLOW_NAME")

  if [ -z "$PG_ID" ]; then

      PG_ID=$(create_pg "$FLOW_NAME")

      start_version_control "$PG_ID" "$FLOW_NAME" "$BUCKET_ID"

      continue
  fi

  LOCAL_HASH=$(calculate_local_hash "$FLOW_FILE")
  STORED_HASH=$(get_pg_hash_variable "$PG_ID")

  echo "Local hash: $LOCAL_HASH"
  echo "Stored hash: $STORED_HASH"

  if [ "$LOCAL_HASH" = "$STORED_HASH" ]; then
      echo "No changes detected."
      continue
  fi

  echo "Change detected."

  if is_versioned "$PG_ID"; then
      commit_registry_version "$PG_ID" "GitOps commit"
      update_pg_to_latest "$PG_ID"
  else
      start_version_control "$PG_ID" "$FLOW_NAME" "$BUCKET_ID"
  fi

  set_pg_hash_variable "$PG_ID" "$LOCAL_HASH"

done

}

########################################
# Start deployment
########################################

echo "NiFi GitOps Deployment Starting"

deploy_flows

echo "Deployment Completed"
