#!/usr/bin/env bash
set -e

############################################
# Environment variables required
############################################

NIFI_URL=${NIFI_URL}
REGISTRY_URL=${REGISTRY_URL}
TOKEN=${TOKEN}

ROOT_PG_ID=${ROOT_PG_ID}
REGISTRY_ID=${REGISTRY_ID}
BUCKET_ID=${BUCKET_ID}

FLOW_DIR=${FLOW_DIR:-flows}

############################################
# Generic NiFi API wrapper
############################################

api() {
 curl -s -k \
   -H "Authorization: Bearer $TOKEN" \
   -H "Content-Type: application/json" \
   "$@"
}

############################################
# Calculate stable flow hash
############################################

calculate_local_hash() {

jq 'del(.snapshotMetadata.comments)' "$1" \
 | jq -S '.' \
 | sha256sum \
 | awk '{print $1}'
}

############################################
# Find process group by name
############################################

find_pg_by_name() {

NAME=$1

api "$NIFI_URL/nifi-api/process-groups/$ROOT_PG_ID/process-groups" \
 | jq -r --arg NAME "$NAME" '
.processGroups[]
| select(.component.name==$NAME)
| .component.id'
}

############################################
# Get PG revision
############################################

get_pg_revision() {

api "$NIFI_URL/nifi-api/process-groups/$1" \
 | jq '.revision.version'
}

############################################
# Create process group
############################################

create_pg() {

NAME=$1

echo "Creating process group: $NAME"

PAYLOAD=$(jq -n --arg name "$NAME" '
{
 revision:{version:0},
 component:{
   name:$name,
   position:{x:0,y:0}
 }
}')

api -X POST \
 "$NIFI_URL/nifi-api/process-groups/$ROOT_PG_ID/process-groups" \
 -d "$PAYLOAD" \
 | jq -r '.id'
}

############################################
# Get version control info
############################################

get_version_info() {

api "$NIFI_URL/nifi-api/versions/process-groups/$1"
}

############################################
# Check if version controlled
############################################

is_versioned() {

get_version_info "$1" \
 | jq '.versionControlInformation != null'
}

############################################
# Start version control
############################################

start_version_control() {

PG_ID=$1
FLOW_NAME=$2

REV=$(get_pg_revision "$PG_ID")

echo "Starting version control for $FLOW_NAME"

PAYLOAD=$(jq -n \
 --arg bucket "$BUCKET_ID" \
 --arg registry "$REGISTRY_ID" \
 --arg flowName "$FLOW_NAME" \
 --argjson rev "$REV" '
{
 processGroupRevision:{version:$rev},
 versionControlInformation:{
   registryId:$registry,
   bucketId:$bucket,
   flowName:$flowName
 }
}')

api -X POST \
 "$NIFI_URL/nifi-api/versions/process-groups/$PG_ID" \
 -d "$PAYLOAD"
}

############################################
# Get registry hash from comment
############################################

get_registry_hash() {

FLOW_ID=$1

curl -s \
 "$REGISTRY_URL/nifi-registry-api/buckets/$BUCKET_ID/flows/$FLOW_ID/versions/latest" \
 | jq -r '.snapshotMetadata.comments' \
 | sed 's/hash://'
}

############################################
# Commit new registry version
############################################

commit_registry_version() {

PG_ID=$1
HASH=$2

INFO=$(get_version_info "$PG_ID")

BUCKET=$(echo "$INFO" | jq -r '.versionControlInformation.bucketId')
FLOW=$(echo "$INFO" | jq -r '.versionControlInformation.flowId')

REV=$(get_pg_revision "$PG_ID")

COMMENT="hash:$HASH"

echo "Committing new version with hash $HASH"

PAYLOAD=$(jq -n \
 --arg bucket "$BUCKET" \
 --arg flow "$FLOW" \
 --arg comment "$COMMENT" \
 --argjson rev "$REV" '
{
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
 -d "$PAYLOAD"
}

############################################
# Update PG to latest registry version
############################################

update_pg_to_latest() {

PG_ID=$1

INFO=$(get_version_info "$PG_ID")

BUCKET=$(echo "$INFO" | jq -r '.versionControlInformation.bucketId')
FLOW=$(echo "$INFO" | jq -r '.versionControlInformation.flowId')
VERSION=$(echo "$INFO" | jq -r '.versionControlInformation.version')

REV=$(get_pg_revision "$PG_ID")

echo "Updating process group to version $VERSION"

PAYLOAD=$(jq -n \
 --arg bucket "$BUCKET" \
 --arg flow "$FLOW" \
 --argjson version "$VERSION" \
 --argjson rev "$REV" '
{
 processGroupRevision:{version:$rev},
 versionControlInformation:{
   bucketId:$bucket,
   flowId:$flow,
   version:$version
 }
}')

api -X POST \
 "$NIFI_URL/nifi-api/versions/update-requests/process-groups/$PG_ID" \
 -d "$PAYLOAD"
}


upload_flow_definition() {

PG_ID=$1
FLOW_FILE=$2

echo "Uploading flow definition..."

curl -s -k -X POST \
 "$NIFI_URL/nifi-api/process-groups/$PG_ID/process-groups/upload" \
 -H "Authorization: Bearer $TOKEN" \
 -F "file=@$FLOW_FILE"
}

############################################
# Deploy all flows
############################################

deploy_flows() {

for FLOW_FILE in $FLOW_DIR/*.json
do

FLOW_NAME=$(basename "$FLOW_FILE" .json)

echo "--------------------------------"
echo "Processing flow: $FLOW_NAME"

LOCAL_HASH=$(calculate_local_hash "$FLOW_FILE")

PG_ID=$(find_pg_by_name "$FLOW_NAME")

################################
# Flow does not exist
################################

if [ -z "$PG_ID" ]; then

 echo "Flow not found in NiFi"

 PG_ID=$(create_pg "$FLOW_NAME")

 upload_flow_definition "$PG_ID" "$FLOW_FILE"

 start_version_control "$PG_ID" "$FLOW_NAME"

 commit_registry_version "$PG_ID" "$LOCAL_HASH"

 update_pg_to_latest "$PG_ID"

 continue
fi

################################
# Flow exists
################################

INFO=$(get_version_info "$PG_ID")
FLOW_ID=$(echo "$INFO" | jq -r '.versionControlInformation.flowId')

REGISTRY_HASH=$(get_registry_hash "$FLOW_ID")

echo "Local hash: $LOCAL_HASH"
echo "Registry hash: $REGISTRY_HASH"

if [ "$LOCAL_HASH" = "$REGISTRY_HASH" ]; then
 echo "Flow unchanged"
 continue
fi

echo "Flow changed"

commit_registry_version "$PG_ID" "$LOCAL_HASH"

update_pg_to_latest "$PG_ID"

done

}

############################################
# Start deployment
############################################

echo "================================"
echo "NiFi GitOps Deployment Starting"
echo "================================"

deploy_flows

echo "================================"
echo "Deployment Completed"
echo "================================"
