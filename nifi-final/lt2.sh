#!/bin/bash
set -e

echo "Starting NiFi Flow Deployment"

############################################
# Required environment variables
############################################

NIFI_URL=${NIFI_URL}
REGISTRY_URL=${REGISTRY_URL}
REGISTRY_ID=${REGISTRY_ID}
BUCKET_ID=${BUCKET_ID}
FLOW_DIR=${FLOW_DIR:-/flows}

############################################
# helpers
############################################

get_root_pg() {

  curl -k -s \
  "$NIFI_URL/nifi-api/flow/process-groups/root" \
  | jq -r '.processGroupFlow.id'
}

get_pg_revision() {

  curl -k -s \
  "$NIFI_URL/nifi-api/process-groups/$1" \
  | jq '.revision.version'
}

find_pg() {

  curl -k -s \
  "$NIFI_URL/nifi-api/flow/process-groups/$ROOT_PG" \
  | jq -r ".processGroupFlow.flow.processGroups[]
  | select(.component.name==\"$1\")
  | .component.id"
}

create_pg() {

  echo "Creating process group $1"

  curl -k -s -X POST \
  "$NIFI_URL/nifi-api/process-groups/$ROOT_PG/process-groups" \
  -H "Content-Type: application/json" \
  -d "{
    \"revision\": {\"version\":0},
    \"component\": {
      \"name\":\"$1\"
    }
  }" | jq -r '.component.id'
}

############################################
# upload flow snapshot
############################################

upload_flow() {

  PG_ID=$1
  FILE=$2

  echo "Uploading flow snapshot $FILE"

  curl -k -s -X POST \
  "$NIFI_URL/nifi-api/process-groups/$PG_ID/flow-contents?disconnectedNodeAcknowledged=true" \
  -H "Content-Type: application/json" \
  -d @"$FILE"
}

############################################
# version control checks
############################################

is_versioned() {

  curl -k -s \
  "$NIFI_URL/nifi-api/versions/process-groups/$1" \
  | jq '.versionedFlow.flowId'
}

############################################
# start version control
############################################

start_version_control() {

  PG_ID=$1
  FLOW_NAME=$2
  REVISION=$(get_pg_revision "$PG_ID")

  echo "Starting version control for $FLOW_NAME"

  curl -k -s -X POST \
  "$NIFI_URL/nifi-api/versions/process-groups/$PG_ID" \
  -H "Content-Type: application/json" \
  -d "{
    \"processGroupRevision\": {
      \"version\": $REVISION
    },
    \"versionedFlow\": {
      \"registryId\": \"$REGISTRY_ID\",
      \"bucketId\": \"$BUCKET_ID\",
      \"flowName\": \"$FLOW_NAME\",
      \"comments\": \"Initial version\",
      \"branch\": \"main\"
    }
  }"
}

############################################
# commit flow changes
############################################

commit_flow() {

  PG_ID=$1
  FLOW_NAME=$2

  REVISION=$(get_pg_revision "$PG_ID")

  echo "Committing changes for $FLOW_NAME"

  curl -k -s -X POST \
  "$NIFI_URL/nifi-api/versions/process-groups/$PG_ID/commit" \
  -H "Content-Type: application/json" \
  -d "{
    \"processGroupRevision\": {
      \"version\": $REVISION
    },
    \"versionedFlow\": {
      \"action\": \"COMMIT\",
      \"comments\": \"Automated commit\"
    }
  }"
}

############################################
# update flow version
############################################

update_flow() {

  PG_ID=$1

  INFO=$(curl -k -s \
  "$NIFI_URL/nifi-api/versions/process-groups/$PG_ID")

  FLOW_ID=$(echo "$INFO" | jq -r '.versionedFlow.flowId')

  LATEST=$(curl -k -s \
  "$REGISTRY_URL/nifi-registry-api/buckets/$BUCKET_ID/flows/$FLOW_ID/versions/latest" \
  | jq '.version')

  REVISION=$(get_pg_revision "$PG_ID")

  echo "Updating flow to registry version $LATEST"

  curl -k -s -X PUT \
  "$NIFI_URL/nifi-api/versions/process-groups/$PG_ID" \
  -H "Content-Type: application/json" \
  -d "{
    \"processGroupRevision\": {
      \"version\": $REVISION
    },
    \"versionedFlow\": {
      \"version\": $LATEST
    }
  }"
}

############################################
# deploy single flow
############################################

deploy_flow() {

  FILE=$1
  FLOW_NAME=$(basename "$FILE" .json)

  echo ""
  echo "Processing flow $FLOW_NAME"

  PG_ID=$(find_pg "$FLOW_NAME")

  if [ -z "$PG_ID" ]; then

      PG_ID=$(create_pg "$FLOW_NAME")

      echo "Process group created $PG_ID"

      upload_flow "$PG_ID" "$FILE"

  else

      echo "Process group already exists: $PG_ID"

  fi

  VC=$(is_versioned "$PG_ID")

  if [ "$VC" == "null" ]; then

      start_version_control "$PG_ID" "$FLOW_NAME"

  else

      commit_flow "$PG_ID" "$FLOW_NAME"

  fi

  update_flow "$PG_ID"
}

############################################
# start deployment
############################################

ROOT_PG=$(get_root_pg)

echo "Root Process Group: $ROOT_PG"

for flow in $FLOW_DIR/*.json
do
  deploy_flow "$flow"
done

echo ""
echo "All flows deployed successfully"
