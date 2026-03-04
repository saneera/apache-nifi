#!/bin/sh
set -e

echo "======================================="
echo "NiFi GitOps Registry Deployment Starting"
echo "======================================="

# --------------------------------------------------
# Wait for NiFi
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
# Get Root PG ID
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

get_pg() {
  curl -k -s \
    "$NIFI_URL/nifi-api/process-groups/$1" \
    -H "Authorization: Bearer $TOKEN"
}

get_pg_revision() {
  get_pg "$1" | jq -r '.revision.version'
}

debug_pg_hash() {
  PG_ID=$1

  echo "Checking PG hash variable..."

  curl -k -s \
    "$NIFI_URL/nifi-api/process-groups/$PG_ID" \
    -H "Authorization: Bearer $TOKEN" \
  | jq '{
      revision: .revision.version,
      hash: .component.variables.GIT_FLOW_HASH,
      commit: .component.variables.GIT_COMMIT
    }'
}

get_pg_hash_variable() {
  get_pg "$1" | jq -r '.component.variables.GIT_FLOW_HASH // empty'
}

set_pg_hash_variable() {
  PG_ID=$1
  HASH=$2

  echo "Updating PG hash variable..."

  # Retry up to 3 times if revision conflict
  for attempt in 1 2 3; do

    PG_JSON=$(get_pg "$PG_ID")
    REVISION=$(echo "$PG_JSON" | jq -r '.revision.version')

    # Merge existing variables safely
    UPDATED_VARIABLES=$(echo "$PG_JSON" | jq \
      --arg hash "$HASH" \
      --arg commit "${GIT_COMMIT:-manual}" \
      '.component.variables + {
        "GIT_FLOW_HASH": $hash,
        "GIT_COMMIT": $commit
      }')


    HTTP_STATUS=$(curl -k \
      -w "%{http_code}" \
      -X PUT \
      "$NIFI_URL/nifi-api/process-groups/$PG_ID" \
      -H "Authorization: Bearer $TOKEN" \
      -H "Content-Type: application/json" \
      -d "{
        \"revision\": {
          \"version\": $REVISION
        },
        \"component\": {
          \"id\": \"$PG_ID\",
          \"variables\": $UPDATED_VARIABLES
        }
      }")

    if [ "$HTTP_STATUS" -ge 200 ] && [ "$HTTP_STATUS" -lt 300 ]; then
      echo "Hash variable updated successfully."
      return 0
    fi

    if [ "$HTTP_STATUS" = "409" ]; then
      echo "Revision conflict. Retrying ($attempt/3)..."
      sleep 2
      continue
    fi

    echo "Failed to update PG variables"
    echo "HTTP Status: $HTTP_STATUS"
    exit 1

  done

  echo "Failed after 3 attempts due to revision conflicts."
  exit 1
}

is_versioned() {
  resp=$(curl -k -s \
    "$NIFI_URL/nifi-api/versions/process-groups/$1" \
    -H "Authorization: Bearer $TOKEN")

  echo "$resp" | jq -e '.versionControlInformation != null' > /dev/null
}

start_version_control() {
  PG_ID=$1
  NAME=$2
  REVISION=$(get_pg_revision "$PG_ID")

  echo "Starting version control..."

  curl -f -k -s -X POST \
    "$NIFI_URL/nifi-api/versions/process-groups/$PG_ID" \
    -H "Authorization: Bearer $TOKEN" \
    -H "Content-Type: application/json" \
    -d "{
      \"processGroupRevision\": {
        \"version\": $REVISION
      },
      \"versionControlInformation\": {
        \"registryId\": \"$REGISTRY_ID\",
        \"bucketId\": \"$REGISTRY_BUCKET_ID\",
        \"flowName\": \"$NAME\",
        \"flowDescription\": \"GitOps managed flow\"
      },
      \"comments\": \"Initial GitOps version - ${GIT_COMMIT:-manual}\"
    }" > /dev/null

  echo "Version control started."
}

commit_registry_version() {
  PG_ID=$1
  REVISION=$(get_pg_revision "$PG_ID")

  echo "Committing new registry version..."
  echo "PG: $PG_ID | Revision: $REVISION"

  RESPONSE_FILE=$(mktemp)

  HTTP_STATUS=$(curl -k \
    --connect-timeout 10 \
    --max-time 60 \
    -w "%{http_code}" \
    -o "$RESPONSE_FILE" \
    -X POST \
    "$NIFI_URL/nifi-api/versions/process-groups/$PG_ID" \
    -H "Authorization: Bearer $TOKEN" \
    -H "Content-Type: application/json" \
    -d "{
      \"processGroupRevision\": {
        \"version\": $REVISION
      },
      \"comments\": \"Git commit: ${GIT_COMMIT:-manual}\"
    }")

  if [ "$HTTP_STATUS" -ge 200 ] && [ "$HTTP_STATUS" -lt 300 ]; then
    echo "✅ Registry version committed successfully."
    rm -f "$RESPONSE_FILE"
    return 0
  fi

  echo "❌ Failed to commit registry version"
  echo "HTTP Status: $HTTP_STATUS"
  echo "Response body:"
  cat "$RESPONSE_FILE"
  rm -f "$RESPONSE_FILE"
  exit 1
}

update_pg_to_latest() {
  PG_ID=$1

  VERSION_INFO=$(curl -k -s \
    "$NIFI_URL/nifi-api/versions/process-groups/$PG_ID" \
    -H "Authorization: Bearer $TOKEN")

  TARGET_VERSION=$(echo "$VERSION_INFO" | jq -r '.versionControlInformation.version')
  REVISION=$(get_pg_revision "$PG_ID")

  echo "Updating PG to version $TARGET_VERSION..."

  curl -f -k -s -X PUT \
    "$NIFI_URL/nifi-api/versions/process-groups/$PG_ID" \
    -H "Authorization: Bearer $TOKEN" \
    -H "Content-Type: application/json" \
    -d "{
      \"processGroupRevision\": {
        \"version\": $REVISION
      },
      \"versionControlInformation\": {
        \"version\": $TARGET_VERSION
      }
    }" > /dev/null

  echo "PG updated to latest."
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
  # NEW FLOW
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

    LOCAL_HASH=$(calculate_local_hash "$FLOW_FILE")
    set_pg_hash_variable "$NEW_ID" "$LOCAL_HASH"

    echo "Flow deployed and versioned."

  else

    echo "Flow exists: $EXISTING_ID"

    LOCAL_HASH=$(calculate_local_hash "$FLOW_FILE")
    STORED_HASH=$(get_pg_hash_variable "$EXISTING_ID")

    echo "Local hash: $LOCAL_HASH"
    echo "Stored hash: $STORED_HASH"

    if [ "$LOCAL_HASH" = "$STORED_HASH" ]; then
      echo "No changes detected. Skipping."
      continue
    fi

    echo "Change detected."

    if is_versioned "$EXISTING_ID"; then
      commit_registry_version "$EXISTING_ID"
      update_pg_to_latest "$EXISTING_ID"
    else
      start_version_control "$EXISTING_ID" "$NAME"
    fi

    set_pg_hash_variable "$EXISTING_ID" "$LOCAL_HASH"

  fi

done

if [ "$FOUND" = false ]; then
  echo "No flow files found."
fi

echo "======================================="
echo "GitOps Deployment Completed Successfully"
echo "======================================="
