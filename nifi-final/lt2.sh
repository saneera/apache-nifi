#!/bin/bash

set -e

echo "Starting NiFi Flow Deployment"

############################################
# env
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

curl -s \
"$NIFI_URL/nifi-api/flow/process-groups/root" \
| jq -r '.processGroupFlow.id'
}

get_pg_revision() {

curl -s \
"$NIFI_URL/nifi-api/process-groups/$1" \
| jq '.revision.version'
}

find_pg() {

curl -s \
"$NIFI_URL/nifi-api/flow/process-groups/$ROOT_PG" \
| jq -r ".processGroupFlow.flow.processGroups[]
| select(.component.name==\"$1\")
| .component.id"
}

create_pg() {

echo "Creating process group $1"

curl -s -X POST \
"$NIFI_URL/nifi-api/process-groups/$ROOT_PG/process-groups" \
-H "Content-Type: application/json" \
-d "{
\"revision\":{\"version\":0},
\"component\":{\"name\":\"$1\"}
}" | jq -r '.component.id'
}

is_versioned() {

curl -s \
"$NIFI_URL/nifi-api/versions/process-groups/$1" \
| jq '.versionedFlow.flowId'
}

calculate_local_hash() {

jq -S '.' "$1" | sha256sum | awk '{print $1}'
}

get_flow_id() {

curl -s \
"$NIFI_URL/nifi-api/versions/process-groups/$1" \
| jq -r '.versionedFlow.flowId'
}

get_registry_hash() {

FLOW_ID=$1

LATEST=$(curl -s \
"$REGISTRY_URL/nifi-registry-api/buckets/$BUCKET_ID/flows/$FLOW_ID/versions/latest")

echo "$LATEST" \
| jq -r '.snapshot.snapshotMetadata.comments' \
| grep -o 'flow-hash:[a-z0-9]*' \
| cut -d':' -f2
}

start_version_control() {

PG_ID=$1
FLOW_NAME=$2
FILE=$3

HASH=$(calculate_local_hash "$FILE")
REVISION=$(get_pg_revision "$PG_ID")

echo "Starting version control for $FLOW_NAME"

curl -s -X POST \
"$NIFI_URL/nifi-api/versions/process-groups/$PG_ID" \
-H "Content-Type: application/json" \
-d "{
\"processGroupRevision\":{\"version\":$REVISION},
\"versionedFlow\":{
\"registryId\":\"$REGISTRY_ID\",
\"bucketId\":\"$BUCKET_ID\",
\"flowName\":\"$FLOW_NAME\",
\"comments\":\"flow-hash:$HASH\",
\"branch\":\"main\"
}
}"
}

commit_flow_if_changed() {

FILE=$1
PG_ID=$2
FLOW_ID=$3

LOCAL_HASH=$(calculate_local_hash "$FILE")
REGISTRY_HASH=$(get_registry_hash "$FLOW_ID")

echo "Local hash: $LOCAL_HASH"
echo "Registry hash: $REGISTRY_HASH"

if [ "$LOCAL_HASH" == "$REGISTRY_HASH" ]; then
echo "No flow change detected"
return
fi

REVISION=$(get_pg_revision "$PG_ID")

echo "Flow changed, committing new version"

curl -s -X POST \
"$NIFI_URL/nifi-api/versions/process-groups/$PG_ID/commit" \
-H "Content-Type: application/json" \
-d "{
\"processGroupRevision\":{\"version\":$REVISION},
\"versionedFlow\":{
\"action\":\"COMMIT\",
\"comments\":\"flow-hash:$LOCAL_HASH\"
}
}"
}

update_flow() {

PG_ID=$1

INFO=$(curl -s \
"$NIFI_URL/nifi-api/versions/process-groups/$PG_ID")

FLOW_ID=$(echo "$INFO" | jq -r '.versionedFlow.flowId')

LATEST=$(curl -s \
"$REGISTRY_URL/nifi-registry-api/buckets/$BUCKET_ID/flows/$FLOW_ID/versions/latest" \
| jq '.version')

REVISION=$(get_pg_revision "$PG_ID")

echo "Updating flow to version $LATEST"

curl -s -X PUT \
"$NIFI_URL/nifi-api/versions/process-groups/$PG_ID" \
-H "Content-Type: application/json" \
-d "{
\"processGroupRevision\":{\"version\":$REVISION},
\"versionedFlow\":{\"version\":$LATEST}
}"
}

deploy_flow() {

FILE=$1
FLOW_NAME=$(basename "$FILE" .json)

echo ""
echo "Processing flow: $FLOW_NAME"

PG_ID=$(find_pg "$FLOW_NAME")

if [ -z "$PG_ID" ]; then
PG_ID=$(create_pg "$FLOW_NAME")
fi

echo "Process Group ID: $PG_ID"

VC=$(is_versioned "$PG_ID")

if [ "$VC" == "null" ]; then

start_version_control "$PG_ID" "$FLOW_NAME" "$FILE"

else

FLOW_ID=$(get_flow_id "$PG_ID")

commit_flow_if_changed "$FILE" "$PG_ID" "$FLOW_ID"

fi

update_flow "$PG_ID"
}

############################################
# main
############################################

ROOT_PG=$(get_root_pg)

echo "Root Process Group: $ROOT_PG"

for flow in $FLOW_DIR/*.json
do
deploy_flow "$flow"
done

echo ""
echo "All flows deployed successfully"
