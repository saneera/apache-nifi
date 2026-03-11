
#!/bin/bash
set -e

########################################
# CONFIG
########################################

NIFI_URL="${NIFI_URL}"
REGISTRY_URL="${REGISTRY_URL}"

NIFI_USER="${SINGLE_USER_CREDENTIALS_USERNAME}"
NIFI_PASSWORD="${SINGLE_USER_CREDENTIALS_PASSWORD}"

REG_CLIENT_ID="${REGISTRY_ID}"
BUCKET_ID="${REGISTRY_BUCKET_ID}"

TARGET_RPG_URL="${TARGET_RPG_URL}"

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

echo "NiFi ready"

########################################
# Authenticate
########################################

TOKEN=$(curl -k -s -X POST \
 "$NIFI_URL/nifi-api/access/token" \
 -H "Content-Type: application/x-www-form-urlencoded" \
 -d "username=$NIFI_USER&password=$NIFI_PASSWORD")

########################################
# Root PG
########################################

ROOT_PG=$(curl -k -s \
 "$NIFI_URL/nifi-api/flow/process-groups/root" \
 -H "Authorization: Bearer $TOKEN" |
 jq -r '.processGroupFlow.id')

########################################
# Replace Parameters Automatically
########################################

update_flow() {

local input=$1
local output=$2

jq '

def env_name(n):
  n | ascii_upcase | gsub("-";"_");

# Update RPG
(.flowContents.remoteProcessGroups[]?.targetUris) = env.TARGET_RPG_URL |

(.flowContents.processGroups[]?.remoteProcessGroups[]?.targetUris) = env.TARGET_RPG_URL |

# Update parameters automatically
(.parameterContexts // {}) |=
with_entries(
  .value.parameters |=
  map(
    (env_name(.name)) as $env
    | if env[$env] then
        .value = env[$env]
      else
        .
      end
  )
)

' "$input" > "$output"

}

########################################
# Process flows
########################################

for ORIGINAL_FLOW in /flows/*.json
do

[ -e "$ORIGINAL_FLOW" ] || continue

FLOW_NAME=$(jq -r '.flowContents.name // .header.flowName' "$ORIGINAL_FLOW")

echo ""
echo "Deploying flow: $FLOW_NAME"

TMP_FLOW="/tmp/$(basename $ORIGINAL_FLOW)"

update_flow "$ORIGINAL_FLOW" "$TMP_FLOW"

FLOW_FILE="$TMP_FLOW"

########################################
# Calculate Hash
########################################

LOCAL_HASH=$(jq -S '.flowContents' "$FLOW_FILE" | sha256sum | awk '{print $1}')

########################################
# Check Registry
########################################

FLOW_ID=$(curl -k -s \
 "$REGISTRY_URL/nifi-registry-api/buckets/$BUCKET_ID/flows" |
 jq -r ".[] | select(.name==\"$FLOW_NAME\") | .identifier")

########################################
# Create Flow if missing
########################################

if [ -z "$FLOW_ID" ]
then

 echo "Creating new registry flow"

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

########################################
# Skip if unchanged
########################################

if [ "$LOCAL_HASH" = "$REG_HASH" ]
then
 echo "Flow unchanged"
 continue
fi

########################################
# Get latest version
########################################

LATEST=$(curl -k -s \
 "$REGISTRY_URL/nifi-registry-api/buckets/$BUCKET_ID/flows/$FLOW_ID/versions/latest")

if echo "$LATEST" | jq . >/dev/null 2>&1
then
 REG_VERSION=$(echo "$LATEST" | jq '.snapshotMetadata.version')
else
 REG_VERSION=0
fi

NEXT_VERSION=$((REG_VERSION+1))

########################################
# Upload new version
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
# Find PG
########################################

PG_ID=$(curl -k -s \
 "$NIFI_URL/nifi-api/flow/process-groups/$ROOT_PG" \
 -H "Authorization: Bearer $TOKEN" |
 jq -r ".processGroupFlow.flow.processGroups[]
 | select(.component.name==\"$FLOW_NAME\")
 | .component.id")

########################################
# Import if missing
########################################

if [ -z "$PG_ID" ]
then

 echo "Importing flow into NiFi"

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
# Update Version
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

while [ "$STATUS" != "Complete" ] && [ "$STATUS" != "Failed" ]
do

 STATUS_JSON=$(curl -k -s "$REQUEST_URL" \
 -H "Authorization: Bearer $TOKEN")

 STATUS=$(echo "$STATUS_JSON" | jq -r '.request.state')

 echo "Update status: $STATUS"

 sleep 5

done

curl -k -s -X DELETE "$REQUEST_URL" \
 -H "Authorization: Bearer $TOKEN"

echo "Deployment complete for $FLOW_NAME"

done

echo ""
echo "All flows deployed"
