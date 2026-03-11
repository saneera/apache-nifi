#!/bin/bash
set -euo pipefail

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

POSITION_STEP=400

########################################
# Wait for NiFi
########################################

wait_for_nifi() {
  echo "Waiting for NiFi API..."

  until curl -k -s -X POST \
   "$NIFI_URL/nifi-api/access/token" \
   -H "Content-Type: application/x-www-form-urlencoded" \
   -d "username=$NIFI_USER&password=$NIFI_PASSWORD" > /dev/null
  do
   sleep 5
  done

  echo "NiFi ready."
}

########################################
# Authenticate
########################################

authenticate() {

  TOKEN=$(curl -k -s -X POST \
   "$NIFI_URL/nifi-api/access/token" \
   -H "Content-Type: application/x-www-form-urlencoded" \
   -d "username=$NIFI_USER&password=$NIFI_PASSWORD")

  AUTH_HEADER="Authorization: Bearer $TOKEN"
}

########################################
# Get Root Process Group
########################################

get_root_pg() {

 ROOT_PG=$(curl -k -s \
 "$NIFI_URL/nifi-api/flow/process-groups/root" \
 -H "$AUTH_HEADER" |
 jq -r '.processGroupFlow.id')

}

########################################
# Detect next available position
########################################

get_next_position() {

 MAX_X=$(curl -k -s \
 "$NIFI_URL/nifi-api/flow/process-groups/$ROOT_PG" \
 -H "$AUTH_HEADER" |
 jq '[.processGroupFlow.flow.processGroups[].position.x] | max')

 if [ "$MAX_X" = "null" ]; then
  POS_X=300
 else
  POS_X=$((MAX_X + POSITION_STEP))
 fi

 POS_Y=300
}

########################################
# Cache registry flows
########################################

cache_registry_flows() {

 echo "Caching registry flows..."

 REGISTRY_FLOW_META=$(curl -k -s \
 "$REGISTRY_URL/nifi-registry-api/buckets/$BUCKET_ID/flows")

}

########################################
# Prepare Flow JSON
########################################

prepare_flow() {

 ORIGINAL_FLOW=$1

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
}

########################################
# Calculate Hash
########################################

calculate_hash() {

 FLOW_HASH=$(jq -S '
 .flowContents
 | del(.. | .position?)
 ' "$FLOW_FILE" | sha256sum | cut -d' ' -f1)

 PARAM_HASH=$(jq -S '
 .parameterContexts // {}
 ' "$FLOW_FILE" | sha256sum | cut -d' ' -f1)

 LOCAL_HASH="${FLOW_HASH}_${PARAM_HASH}"
}

########################################
# Lookup Registry Flow
########################################

lookup_registry_flow() {

 FLOW_ID=$(echo "$REGISTRY_FLOW_META" |
 jq -r ".[] | select(.name==\"$FLOW_NAME\") | .identifier")

}

########################################
# Get Registry Hash
########################################

get_registry_hash() {

 if [ -z "$FLOW_ID" ]; then
  REG_HASH=""
 else
  REG_HASH=$(echo "$REGISTRY_FLOW_META" |
   jq -r ".[] | select(.identifier==\"$FLOW_ID\") | .description | sub(\"flow-hash:\";\"\")")
 fi

}

########################################
# Create Registry Flow
########################################

create_registry_flow() {

 FLOW_ID=$(curl -k -s -X POST \
 "$REGISTRY_URL/nifi-registry-api/buckets/$BUCKET_ID/flows" \
 -H "Content-Type: application/json" \
 -d "{
 \"name\":\"$FLOW_NAME\",
 \"description\":\"flow-hash:$LOCAL_HASH\"
 }" | jq -r '.identifier')

}

########################################
# Get Latest Version
########################################

get_latest_version() {

 LATEST=$(curl -k -s \
 "$REGISTRY_URL/nifi-registry-api/buckets/$BUCKET_ID/flows/$FLOW_ID/versions/latest")

 if echo "$LATEST" | jq . >/dev/null 2>&1; then
  REG_VERSION=$(echo "$LATEST" | jq '.snapshotMetadata.version')
 else
  REG_VERSION=0
 fi

 NEXT_VERSION=$((REG_VERSION+1))
}

########################################
# Upload Registry Version
########################################

upload_registry_version() {

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

}

########################################
# Update Registry Hash
########################################

update_registry_hash() {

 curl -k -s -X PUT \
 "$REGISTRY_URL/nifi-registry-api/buckets/$BUCKET_ID/flows/$FLOW_ID" \
 -H "Content-Type: application/json" \
 -d "{
 \"identifier\":\"$FLOW_ID\",
 \"name\":\"$FLOW_NAME\",
 \"description\":\"flow-hash:$LOCAL_HASH\"
 }"

}

########################################
# Find Process Group
########################################

find_pg() {

 PG_ID=$(curl -k -s \
 "$NIFI_URL/nifi-api/flow/process-groups/$ROOT_PG" \
 -H "$AUTH_HEADER" |
 jq -r ".processGroupFlow.flow.processGroups[]
 | select(.component.name==\"$FLOW_NAME\")
 | .component.id")

}

########################################
# Import Flow
########################################

import_flow() {

 echo "Importing flow at $POS_X,$POS_Y"

 IMPORT_PAYLOAD=$(jq -n \
 --arg name "$FLOW_NAME" \
 --arg registryId "$REG_CLIENT_ID" \
 --arg bucketId "$BUCKET_ID" \
 --arg flowId "$FLOW_ID" \
 --argjson version "$NEXT_VERSION" \
 --argjson posX "$POS_X" \
 --argjson posY "$POS_Y" \
 '
 {
 revision:{version:0},
 component:{
 name:$name,
 position:{x:$posX,y:$posY},
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
 -H "$AUTH_HEADER" \
 -H "Content-Type: application/json" \
 -d "$IMPORT_PAYLOAD"

 POS_X=$((POS_X + POSITION_STEP))
}

########################################
# Update Flow Version
########################################

update_flow_version() {

 PG_JSON=$(curl -k -s \
 "$NIFI_URL/nifi-api/process-groups/$PG_ID" \
 -H "$AUTH_HEADER")

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

 curl -k -s -X POST \
 "$NIFI_URL/nifi-api/versions/update-requests/process-groups/$PG_ID" \
 -H "$AUTH_HEADER" \
 -H "Content-Type: application/json" \
 -d "$UPDATE_PAYLOAD"
}

########################################
# MAIN
########################################

wait_for_nifi
authenticate
get_root_pg
get_next_position
cache_registry_flows

for ORIGINAL_FLOW in /flows/*.json; do

 [ -e "$ORIGINAL_FLOW" ] || continue

 FLOW_NAME=$(jq -r '.flowContents.name // .header.flowName' "$ORIGINAL_FLOW")

 echo ""
 echo "Processing flow: $FLOW_NAME"

 prepare_flow "$ORIGINAL_FLOW"
 calculate_hash
 lookup_registry_flow
 get_registry_hash

 echo "Flow Hash: $FLOW_HASH"
 echo "Param Hash: $PARAM_HASH"
 echo "Registry Hash: $REG_HASH"

 if [ "$LOCAL_HASH" = "$REG_HASH" ]; then
  echo "Flow unchanged. Skipping."
  continue
 fi

 if [ -z "$FLOW_ID" ]; then
  create_registry_flow
 fi

 get_latest_version
 upload_registry_version
 update_registry_hash
 find_pg

 if [ -z "$PG_ID" ]; then
  import_flow
 else
  update_flow_version
 fi

done

echo ""
echo "All flows processed."
