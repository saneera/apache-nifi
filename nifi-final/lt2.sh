#!/bin/bash
set -e

echo "Starting NiFi Flow Deployment"

############################################
# Environment
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
\"revision\":{\"version\":0},
\"component\":{\"name\":\"$1\"}
}" | jq -r '.component.id'

}

############################################
# upload flow snapshot
############################################

upload_flow() {

PG_ID=$1
FILE=$2

echo "Uploading flow snapshot"

curl -k -s -X POST \
"$NIFI_URL/nifi-api/process-groups/$PG_ID/flow-contents?disconnectedNodeAcknowledged=true" \
-H "Content-Type: application/json" \
-d @"$FILE"

}

############################################
# update flow contents
############################################

update_flow_contents() {

PG_ID=$1
FILE=$2

echo "Updating flow contents"

curl -k -s -X PUT \
"$NIFI_URL/nifi-api/process-groups/$PG_ID/flow-contents?disconnectedNodeAcknowledged=true" \
-H "Content-Type: application/json" \
-d @"$FILE"

}

############################################
# version control check
############################################

is_versioned() {

curl -k -s \
"$NIFI_URL/nifi-api/versions/process-groups/$1" \
| jq '.versionedFlow.flowId'

}

get_flow_id() {

curl -k -s \
"$NIFI_URL/nifi-api/versions/process-groups/$1" \
| jq -r '.versionedFlow.flowId'

}

############################################
# hash helpers
############################################

calculate_local_hash() {

jq -S 'del(..|.identifier?) | del(..|.instanceIdentifier?)' "$1" \
| sha256sum \
| awk '{print $1}'

}

get_registry_hash() {

FLOW_ID=$1

LATEST=$(curl -k -s \
"$REGISTRY_URL/nifi-registry-api/buckets/$BUCKET_ID/flows/$FLOW_ID/versions/latest")

echo "$LATEST" \
| jq -r '.snapshot.snapshotMetadata.comments' \
| grep -o 'flow-hash:[a-z0-9]*' \
| cut -d':' -f2

}

############################################
# start version control
############################################

start_version_control() {

PG_ID=$1
FLOW_NAME=$2
HASH=$3

REVISION=$(get_pg_revision "$PG_ID")

echo "Starting version control for $FLOW_NAME"

curl -k -s -X POST \
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

############################################
# commit changes
############################################

commit_flow() {

PG_ID=$1
FLOW_NAME=$2
HASH=$3

REVISION=$(get_pg_revision "$PG_ID")

echo "Committing flow changes"

curl -k -s -X POST \
"$NIFI_URL/nifi-api/versions/process-groups/$PG_ID/commit" \
-H "Content-Type: application/json" \
-d "{
\"processGroupRevision\":{\"version\":$REVISION},
\"versionedFlow\":{
\"action\":\"COMMIT\",
\"comments\":\"flow-hash:$HASH\"
}
}"

}

############################################
# update NiFi flow version
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

echo "Updating flow to version $LATEST"

curl -k -s -X PUT \
"$NIFI_URL/nifi-api/versions/process-groups/$PG_ID" \
-H "Content-Type: application/json" \
-d "{
\"processGroupRevision\":{\"version\":$REVISION},
\"versionedFlow\":{\"version\":$LATEST}
}"

}

############################################
# deploy logic
############################################

deploy_flow() {

FILE=$1
FLOW_NAME=$(basename "$FILE" .json)

echo ""
echo "Processing flow: $FLOW_NAME"

PG_ID=$(find_pg "$FLOW_NAME")

LOCAL_HASH=$(calculate_local_hash "$FILE")

if [ -z "$PG_ID" ]; then

echo "Process group not found"

PG_ID=$(create_pg "$FLOW_NAME")

echo "PG created $PG_ID"

upload_flow "$PG_ID" "$FILE"

start_version_control "$PG_ID" "$FLOW_NAME" "$LOCAL_HASH"

update_flow "$PG_ID"

return

fi

echo "Process group exists: $PG_ID"

VC=$(is_versioned "$PG_ID")

FLOW_ID=$(get_flow_id "$PG_ID")

REGISTRY_HASH=$(get_registry_hash "$FLOW_ID")

echo "Local hash: $LOCAL_HASH"
echo "Registry hash: $REGISTRY_HASH"

if [ "$LOCAL_HASH" == "$REGISTRY_HASH" ]; then

echo "No flow change detected"
return

fi

echo "Flow changed — updating"

update_flow_contents "$PG_ID" "$FILE"

commit_flow "$PG_ID" "$FLOW_NAME" "$LOCAL_HASH"

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



FLOW_INFO=$(echo "$INFO" | jq '.versionControlInformation')
FLOW_ID=$(echo "$INFO" | jq -r '.versionControlInformation.flowId')

REGISTRY_ID=$(echo "$INFO" | jq -r '.versionControlInformation.registryId')

BUCKET_ID=$(echo "$INFO" | jq -r '.versionControlInformation.bucketId')

CURRENT_VERSION=$(echo "$INFO" | jq -r '.versionControlInformation.version')

PAYLOAD=$(jq -n \
--argjson rev "$REVISION" \
--arg reg "$REGISTRY_ID" \
--arg bucket "$BUCKET_ID" \
--arg flow "$FLOW_ID" \
--argjson ver "$LATEST" \
'{
    processGroupRevision:{version:$rev},
    versionedFlow:{
    registryId:$reg,
    bucketId:$bucket,
    flowId:$flow,
    version:$ver
    }
    }')


    curl -k -X PUT \
    "$NIFI_URL/nifi-api/versions/process-groups/$PG_ID" \
    -H "Authorization: Bearer $TOKEN" \
    -H "Content-Type: application/json" \
    -d "$PAYLOAD"


