#!/bin/bash

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

########################################
# Wait for NiFi
########################################

echo "Waiting for NiFi API..."

until curl -k -s -X POST \
 "$NIFI_URL/nifi-api/access/token" \
 -H "Content-Type: application/x-www-form-urlencoded" \
 -d "username=$NIFI_USER&password=$NIFI_PASSWORD" > /dev/null
do
 sleep 5
done

echo "NiFi authentication endpoint ready."

########################################
# Authenticate
########################################

TOKEN=$(curl -k -s -X POST \
 "$NIFI_URL/nifi-api/access/token" \
 -H "Content-Type: application/x-www-form-urlencoded" \
 -d "username=$NIFI_USER&password=$NIFI_PASSWORD")

if [ -z "$TOKEN" ]; then
 echo "Failed to obtain NiFi token"
 exit 1
fi

echo "Authentication successful."

########################################
# Get root process group
########################################

ROOT_PG=$(curl -k -s \
 "$NIFI_URL/nifi-api/flow/process-groups/root" \
 -H "Authorization: Bearer $TOKEN" |
 jq -r '.processGroupFlow.id')

########################################
# Process all flow JSON files
########################################

for ORIGINAL_FLOW in /flows/*.json; do

[ -e "$ORIGINAL_FLOW" ] || continue

FLOW_NAME=$(jq -r '.flowContents.name // .header.flowName' "$ORIGINAL_FLOW")

echo ""
echo "=============================="
echo "Processing flow: $FLOW_NAME"

########################################
# Update URLs and parameter context
########################################

TMP_FLOW="/tmp/$(basename $ORIGINAL_FLOW)"

jq \
 --arg rpgUrl "$TARGET_RPG_URL" \
 --arg fragmentUrl "$FRAGMENT_MANAGER_URL" \
 '

(.flowContents.remoteProcessGroups[]?.targetUris) = $rpgUrl |

(.flowContents.processGroups[]?.remoteProcessGroups[]?.targetUris) = $rpgUrl |

(.parameterContexts // {}) |=
with_entries(
  .value.parameters |=
  map(
    if .name == "fragment-manager-url"
    then .value = $fragmentUrl
    else .
    end
  )
)

' "$ORIGINAL_FLOW" > "$TMP_FLOW"

FLOW_FILE="$TMP_FLOW"

########################################
# Calculate hash
########################################

LOCAL_HASH=$(jq -S '.' "$FLOW_FILE" | sha256sum | awk '{print $1}')

########################################
# Check registry flow
########################################

FLOW_ID=$(curl -k -s \
 "$REGISTRY_URL/nifi-registry-api/buckets/$BUCKET_ID/flows" |
 jq -r ".[] | select(.name==\"$FLOW_NAME\") | .identifier")

########################################
# Create flow if missing
########################################

if [ -z "$FLOW_ID" ]; then

 echo "Creating flow in registry..."

 FLOW_ID=$(curl -k -s -X POST \
 "$REGISTRY_URL/nifi-registry-api/buckets/$BUCKET_ID/flows" \
 -H "Content-Type: application/json" \
 -d "{
 \"name\":\"$FLOW_NAME\",
 \"description\":\"flow-hash:$LOCAL_HASH\"
 }" | jq -r '.identifier')

 REG_HASH=""

else

 REG_HASH=$(curl -k -s \
 "$REGISTRY_URL/nifi-registry-api/buckets/$BUCKET_ID/flows/$FLOW_ID" |
 jq -r '.description | sub("flow-hash:";"")')

fi

echo "Registry hash: $REG_HASH"
echo "Local hash: $LOCAL_HASH"

########################################
# Skip if unchanged
########################################

if [ "$LOCAL_HASH" = "$REG_HASH" ]; then
 echo "Flow unchanged. Skipping."
 continue
fi

########################################
# Get latest registry version
########################################

LATEST=$(curl -k -s \
 "$REGISTRY_URL/nifi-registry-api/buckets/$BUCKET_ID/flows/$FLOW_ID/versions/latest")

if echo "$LATEST" | jq . >/dev/null 2>&1; then
 REG_VERSION=$(echo "$LATEST" | jq '.snapshotMetadata.version')
else
 REG_VERSION=0
fi

NEXT_VERSION=$((REG_VERSION+1))

echo "New version: $NEXT_VERSION"

########################################
# Upload new registry version
########################################

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

########################################
# Update registry hash
########################################

curl -k -s -X PUT \
 "$REGISTRY_URL/nifi-registry-api/buckets/$BUCKET_ID/flows/$FLOW_ID" \
 -H "Content-Type: application/json" \
 -d "{
 \"identifier\":\"$FLOW_ID\",
 \"name\":\"$FLOW_NAME\",
 \"description\":\"flow-hash:$LOCAL_HASH\"
 }"

########################################
# Find process group in NiFi
########################################

PG_ID=$(curl -k -s \
 "$NIFI_URL/nifi-api/flow/process-groups/$ROOT_PG" \
 -H "Authorization: Bearer $TOKEN" |
 jq -r ".processGroupFlow.flow.processGroups[]
 | select(.component.name==\"$FLOW_NAME\")
 | .component.id")

########################################
# Import flow if missing
########################################

if [ -z "$PG_ID" ]; then

 echo "Importing flow..."

 IMPORT_PAYLOAD=$(jq -n \
 --arg name "$FLOW_NAME" \
 --arg registryId "$REG_CLIENT_ID" \
 --arg bucketId "$BUCKET_ID" \
 --arg flowId "$FLOW_ID" \
 --argjson version "$NEXT_VERSION" \
 '
 {
 revision:{version:0},
 component:{
 name:$name,
 position:{x:0,y:0},
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
 -H "Authorization: Bearer $TOKEN" \
 -H "Content-Type: application/json" \
 -d "$IMPORT_PAYLOAD"

 echo "Flow imported"

 continue
fi

########################################
# Update version
########################################

PG_JSON=$(curl -k -s \
 "$NIFI_URL/nifi-api/process-groups/$PG_ID" \
 -H "Authorization: Bearer $TOKEN")

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

HTTP_RESPONSE=$(curl -k -s -X POST \
 "$NIFI_URL/nifi-api/versions/update-requests/process-groups/$PG_ID" \
 -H "Authorization: Bearer $TOKEN" \
 -H "Content-Type: application/json" \
 -d "$UPDATE_PAYLOAD")

REQUEST_ID=$(echo "$HTTP_RESPONSE" | jq -r '.request.requestId')
REQUEST_URL="$NIFI_URL/nifi-api/versions/update-requests/$REQUEST_ID"

STATUS="Pending"

########################################
# Wait for update
########################################

while [ "$STATUS" != "Complete" ] && [ "$STATUS" != "Failed" ]; do

 STATUS_JSON=$(curl -k -s \
 "$REQUEST_URL" \
 -H "Authorization: Bearer $TOKEN")

 STATUS=$(echo "$STATUS_JSON" | jq -r '.request.state')

 echo "Update status: $STATUS"

 if [ "$STATUS" = "Failed" ]; then
  echo "Update failed"
  exit 1
 fi

 sleep 5

done

########################################
# Cleanup request
########################################

curl -k -s -X DELETE \
 "$REQUEST_URL" \
 -H "Authorization: Bearer $TOKEN"

########################################
# Final state
########################################

VC_JSON_UPDATED=$(curl -k -s \
 -H "Authorization: Bearer $TOKEN" \
 "$NIFI_URL/nifi-api/versions/process-groups/$PG_ID")

UPDATED_STATE=$(echo "$VC_JSON_UPDATED" | jq -r '.versionControlInformation.state')

echo "Current PG $PG_ID ($FLOW_NAME) state: $UPDATED_STATE"

echo "Deployment complete for $FLOW_NAME"

done

echo ""
echo "======================================"
echo "All flows processed."
