#!/bin/bash
set -e

############################################
# CONFIG
############################################

NIFI_URL=${NIFI_URL:-https://nifi:8443}
REGISTRY_URL=${REGISTRY_URL:-https://nifi-registry:18443}

FLOW_FILE=$1
BUCKET_ID=$2

USERNAME=$NIFI_USERNAME
PASSWORD=$NIFI_PASSWORD

############################################
# Wait for NiFi
############################################

echo "Waiting for NiFi API..."

until curl -k -s "$NIFI_URL/nifi-api/access/config" > /dev/null; do
  sleep 5
done

echo "NiFi ready."

############################################
# Authenticate
############################################

TOKEN=$(curl -k -s -X POST \
"$NIFI_URL/nifi-api/access/token" \
-H "Content-Type: application/x-www-form-urlencoded" \
-d "username=$USERNAME&password=$PASSWORD")

echo "Authentication successful."

############################################
# Flow name
############################################

FLOW_NAME=$(jq -r '.flowContents.name // .header.flowName' "$FLOW_FILE")

echo "Flow name: $FLOW_NAME"

############################################
# Calculate LOCAL HASH
############################################

LOCAL_HASH=$(jq -S '.' "$FLOW_FILE" | sha256sum | awk '{print $1}')

echo "LOCAL_HASH: $LOCAL_HASH"

############################################
# Check Flow in Registry
############################################

FLOW_JSON=$(curl -k -s \
"$REGISTRY_URL/nifi-registry-api/buckets/$BUCKET_ID/flows")

FLOW_ID=$(echo "$FLOW_JSON" | jq -r ".[] | select(.name==\"$FLOW_NAME\") | .identifier")

############################################
# Create flow if missing
############################################

if [ -z "$FLOW_ID" ]; then

  echo "Creating new flow in registry..."

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
  "$REGISTRY_URL/nifi-registry-api/buckets/$BUCKET_ID/flows/$FLOW_ID" \
  | jq -r '.description | sub("flow-hash:";"")')

fi

echo "REG_HASH: $REG_HASH"

############################################
# Compare hash
############################################

if [ "$LOCAL_HASH" = "$REG_HASH" ]; then
  echo "Flow unchanged. Skipping registry version creation."
else

  echo "Flow changed. Creating new version..."

  LATEST=$(curl -k -s \
  "$REGISTRY_URL/nifi-registry-api/buckets/$BUCKET_ID/flows/$FLOW_ID/versions/latest" \
  | jq '.version // 0')

  NEXT_VERSION=$((LATEST+1))

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

  echo "Uploaded version $NEXT_VERSION"

  ############################################
  # Update description with new hash
  ############################################

  curl -k -s -X PUT \
  "$REGISTRY_URL/nifi-registry-api/buckets/$BUCKET_ID/flows/$FLOW_ID" \
  -H "Content-Type: application/json" \
  -d "{
  \"identifier\":\"$FLOW_ID\",
  \"name\":\"$FLOW_NAME\",
  \"description\":\"flow-hash:$LOCAL_HASH\"
  }"

fi

############################################
# Get latest registry version
############################################

REG_VERSION=$(curl -k -s \
"$REGISTRY_URL/nifi-registry-api/buckets/$BUCKET_ID/flows/$FLOW_ID/versions/latest" \
| jq '.version')

echo "Registry version: $REG_VERSION"

############################################
# Find Process Group
############################################

PG_ID=$(curl -k -s \
-H "Authorization: Bearer $TOKEN" \
"$NIFI_URL/nifi-api/process-groups/root" \
| jq -r ".processGroupFlow.flow.processGroups[] |
select(.component.name==\"$FLOW_NAME\") |
.component.id")

############################################
# Import if missing
############################################

if [ -z "$PG_ID" ]; then

  echo "Importing flow into NiFi..."

  REGISTRY_ID=$(curl -k -s \
  -H "Authorization: Bearer $TOKEN" \
  "$NIFI_URL/nifi-api/controller/registry-clients" \
  | jq -r '.registryClients[0].component.id')

  curl -k -s -X POST \
  "$NIFI_URL/nifi-api/process-groups/root/process-groups" \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d "{
  \"revision\":{\"version\":0},
  \"component\":{
  \"name\":\"$FLOW_NAME\",
  \"position\":{\"x\":0,\"y\":0},
  \"versionControlInformation\":{
  \"registryId\":\"$REGISTRY_ID\",
  \"bucketId\":\"$BUCKET_ID\",
  \"flowId\":\"$FLOW_ID\",
  \"version\":$REG_VERSION
  }
  }
  }"

  echo "Flow imported."

else

  echo "Process group exists."

  VC_JSON=$(curl -k -s \
  -H "Authorization: Bearer $TOKEN" \
  "$NIFI_URL/nifi-api/versions/process-groups/$PG_ID")

  STATE=$(echo "$VC_JSON" | jq -r '.versionControlInformation.state')

  if [ "$STATE" = "STALE" ]; then

    REV=$(echo "$VC_JSON" | jq '.processGroupRevision.version')

    PAYLOAD=$(jq -n \
    --argjson rev "$REV" \
    --argjson ver "$REG_VERSION" \
    '{
      processGroupRevision:{version:$rev},
      versionControlInformation:{version:$ver}
    }')

    echo "Updating process group..."

    curl -k -s -X POST \
    "$NIFI_URL/nifi-api/versions/update-requests/process-groups/$PG_ID" \
    -H "Authorization: Bearer $TOKEN" \
    -H "Content-Type: application/json" \
    -d "$PAYLOAD"

  else
    echo "Process group already up to date."
  fi

fi

echo "Deployment complete"
