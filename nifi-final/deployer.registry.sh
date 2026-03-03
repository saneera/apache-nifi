#!/bin/sh
set -e

echo "======================================="
echo "NiFi GitOps Registry Deployment Starting"
echo "======================================="

# --------------------------------------------------
# Wait for NiFi API
# --------------------------------------------------
echo "Waiting for NiFi API..."

until curl -k -s -o /dev/null -w "%{http_code}" \
  "$NIFI_URL/nifi-api" | grep -q 200; do
  sleep 5
done

echo "NiFi reachable."

# --------------------------------------------------
# Authenticate
# --------------------------------------------------
TOKEN=$(curl -k -s -X POST \
  "$NIFI_URL/nifi-api/access/token" \
  -H "Content-Type: application/x-www-form-urlencoded" \
  -d "username=$USERNAME&password=$PASSWORD")

[ -z "$TOKEN" ] && echo "Authentication failed" && exit 1

echo "Authenticated."

# --------------------------------------------------
# Get Root Process Group ID
# --------------------------------------------------
ROOT_ID=$(curl -k -s \
  "$NIFI_URL/nifi-api/flow/process-groups/root" \
  -H "Authorization: Bearer $TOKEN" | \
  jq -r '.processGroupFlow.id')

echo "Root PG: $ROOT_ID"

# --------------------------------------------------
# FUNCTIONS
# --------------------------------------------------

calculate_local_hash() {
  jq -S '.' "$1" | sha256sum | awk '{print $1}'
}

is_versioned() {
  curl -k -s \
    "$NIFI_URL/nifi-api/versions/process-groups/$1" \
    -H "Authorization: Bearer $TOKEN" | \
    jq -e '.versionControlInformation' > /dev/null 2>&1
}

get_registry_info() {
  curl -k -s \
    "$NIFI_URL/nifi-api/versions/process-groups/$1" \
    -H "Authorization: Bearer $TOKEN"
}

get_registry_snapshot_hash() {
  PG_ID=$1

  VERSION_INFO=$(get_registry_info "$PG_ID")

  BUCKET_ID=$(echo "$VERSION_INFO" | jq -r '.versionControlInformation.bucketId')
  FLOW_ID=$(echo "$VERSION_INFO" | jq -r '.versionControlInformation.flowId')
  VERSION=$(echo "$VERSION_INFO" | jq -r '.versionControlInformation.version')

  SNAPSHOT=$(curl -k -s \
    "$REGISTRY_URL/nifi-registry-api/buckets/$BUCKET_ID/flows/$FLOW_ID/versions/$VERSION")

  echo "$SNAPSHOT" | jq -S '.flowSnapshot.flowContents' | sha256sum | awk '{print $1}'
}

start_version_control() {
  PG_ID=$1
  NAME=$2

  echo "Starting version control for $NAME..."

  curl -f -k -s -X POST \
    "$NIFI_URL/nifi-api/versions/process-groups/$PG_ID" \
    -H "Authorization: Bearer $TOKEN" \
    -H "Content-Type: application/json" \
    -d "{
      \"versionControlInformation\": {
        \"registryId\": \"$REGISTRY_ID\",
        \"bucketId\": \"$REGISTRY_BUCKET_ID\",
        \"flowName\": \"$NAME\",
        \"flowDescription\": \"GitOps managed flow\",
        \"version\": 1
      }
    }" > /dev/null

  echo "Version control started."
}

commit_registry_version() {
  PG_ID=$1

  echo "Committing new registry version..."

  curl -f -k -s -X POST \
    "$NIFI_URL/nifi-api/versions/process-groups/$PG_ID" \
    -H "Authorization: Bearer $TOKEN" \
    -H "Content-Type: application/json" \
    -d "{
      \"versionControlInformation\": {
        \"comment\": \"Git commit: ${GIT_COMMIT:-manual}\"
      }
    }" > /dev/null

  echo "New version committed."
}

update_pg_to_latest() {
  PG_ID=$1

  VERSION_INFO=$(get_registry_info "$PG_ID")
  CURRENT_VERSION=$(echo "$VERSION_INFO" | jq -r '.versionControlInformation.version')

  echo "Updating PG to version $CURRENT_VERSION..."

  curl -f -k -s -X PUT \
    "$NIFI_URL/nifi-api/versions/process-groups/$PG_ID" \
    -H "Authorization: Bearer $TOKEN" \
    -H "Content-Type: application/json" \
    -d "{
      \"versionControlInformation\": {
        \"version\": $CURRENT_VERSION
      }
    }" > /dev/null

  echo "PG updated."
}

# --------------------------------------------------
# MAIN LOOP
# --------------------------------------------------

FOUND=false

for FLOW_FILE in /flows/*.json; do

  [ -f "$FLOW_FILE" ] || continue
  FOUND=true

  NAME=$(jq -r '.flowContents.name' "$FLOW_FILE")

  echo "---------------------------------------"
  echo "Processing flow: $NAME"

  EXISTING_ID=$(curl -k -s \
    "$NIFI_URL/nifi-api/flow/process-groups/$ROOT_ID" \
    -H "Authorization: Bearer $TOKEN" | \
    jq -r ".processGroupFlow.flow.processGroups[]?
           | select(.component.name==\"$NAME\")
           | .component.id")

  # --------------------------------------------------
  # New Flow
  # --------------------------------------------------
  if [ -z "$EXISTING_ID" ]; then

    echo "Uploading new flow..."

    curl -f -k -s -X POST \
      "$NIFI_URL/nifi-api/process-groups/$ROOT_ID/process-groups/upload" \
      -H "Authorization: Bearer $TOKEN" \
      -F "file=@$FLOW_FILE" \
      -F "groupName=$NAME" > /dev/null

    sleep 3

    NEW_ID=$(curl -k -s \
      "$NIFI_URL/nifi-api/flow/process-groups/$ROOT_ID" \
      -H "Authorization: Bearer $TOKEN" | \
      jq -r ".processGroupFlow.flow.processGroups[]
             | select(.component.name==\"$NAME\")
             | .component.id")

    start_version_control "$NEW_ID" "$NAME"

    echo "Flow deployed and versioned."

  else

    echo "Flow exists: $EXISTING_ID"

    if is_versioned "$EXISTING_ID"; then

      LOCAL_HASH=$(calculate_local_hash "$FLOW_FILE")
      REGISTRY_HASH=$(get_registry_snapshot_hash "$EXISTING_ID")

      echo "Local hash: $LOCAL_HASH"
      echo "Registry hash: $REGISTRY_HASH"

      if [ "$LOCAL_HASH" = "$REGISTRY_HASH" ]; then
        echo "No changes detected. Skipping."
      else
        echo "Change detected."

        commit_registry_version "$EXISTING_ID"
        update_pg_to_latest "$EXISTING_ID"
      fi

    else
      echo "Flow not versioned. Starting version control."
      start_version_control "$EXISTING_ID" "$NAME"
    fi

  fi

done

if [ "$FOUND" = false ]; then
  echo "No flow files found."
fi

echo "======================================="
echo "GitOps Deployment Completed Successfully"
echo "======================================="



HTTP_RESPONSE=$(curl -k -s -X POST \
  "$NIFI_URL/nifi-api/versions/process-groups/$PG_ID" \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d "{
    \"versionControlInformation\": {
      \"registryId\": \"$REGISTRY_ID\",
      \"bucketId\": \"$REGISTRY_BUCKET_ID\",
      \"flowName\": \"$NAME\",
      \"flowDescription\": \"GitOps managed flow\",
      \"version\": 1
    }
  }" \
  -w "HTTPSTATUS:%{http_code}")

HTTP_BODY=$(echo "$HTTP_RESPONSE" | sed -e 's/HTTPSTATUS\:.*//g')
HTTP_STATUS=$(echo "$HTTP_RESPONSE" | tr -d '\n' | sed -e 's/.*HTTPSTATUS://')

echo "Status: $HTTP_STATUS"
echo "Body:"
echo "$HTTP_BODY"
