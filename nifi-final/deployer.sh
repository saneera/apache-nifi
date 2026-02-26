#!/bin/sh
set -e

echo "=============================================="
echo " NiFi Blue/Green Zero-Downtime Deployer"
echo "=============================================="

# --------------------------------------------------
# Wait for NiFi API
# --------------------------------------------------
MAX_RETRIES=36
COUNT=0

echo "Waiting for NiFi API..."

until curl -k -s -o /dev/null -w "%{http_code}" \
  "$NIFI_URL/nifi-api" | grep -q 200; do
  sleep 5
  COUNT=$((COUNT+1))
  if [ "$COUNT" -ge "$MAX_RETRIES" ]; then
    echo "NiFi API not reachable. Exiting."
    exit 1
  fi
done

echo "NiFi API reachable."

# --------------------------------------------------
# Authenticate
# --------------------------------------------------
TOKEN=$(curl -k -s -X POST \
  "$NIFI_URL/nifi-api/access/token" \
  -H "Content-Type: application/x-www-form-urlencoded" \
  -d "username=$USERNAME&password=$PASSWORD")

[ -z "$TOKEN" ] && { echo "Auth failed"; exit 1; }

# --------------------------------------------------
# Get Root ID
# --------------------------------------------------
ROOT_ID=$(curl -k -s \
  "$NIFI_URL/nifi-api/flow/process-groups/root" \
  -H "Authorization: Bearer $TOKEN" | \
  jq -r '.processGroupFlow.id')

echo "Root PG: $ROOT_ID"

# --------------------------------------------------
# Process All Flow Files
# --------------------------------------------------
for FLOW_FILE in /flows/*.json; do

  [ -f "$FLOW_FILE" ] || continue

  BASE_NAME=$(jq -r '.flowContents.name' "$FLOW_FILE")
  echo "----------------------------------------------"
  echo "Deploying base flow: $BASE_NAME"

  BLUE_NAME="${BASE_NAME}-Blue"
  GREEN_NAME="${BASE_NAME}-Green"

  HASH=$(sha256sum "$FLOW_FILE" | awk '{print $1}')
  echo "New flow hash: $HASH"

  # --------------------------------------------------
  # Find Existing Blue/Green
  # --------------------------------------------------
  FLOW_JSON=$(curl -k -s \
    "$NIFI_URL/nifi-api/flow/process-groups/$ROOT_ID" \
    -H "Authorization: Bearer $TOKEN")

  BLUE_ID=$(echo "$FLOW_JSON" | jq -r \
    ".processGroupFlow.flow.processGroups[]?
     | select(.component.name==\"$BLUE_NAME\")
     | .component.id")

  GREEN_ID=$(echo "$FLOW_JSON" | jq -r \
    ".processGroupFlow.flow.processGroups[]?
     | select(.component.name==\"$GREEN_NAME\")
     | .component.id")

  # --------------------------------------------------
  # Determine Active
  # --------------------------------------------------
  ACTIVE_ID=""
  TARGET_NAME=""
  TARGET_ID=""

  if [ -n "$BLUE_ID" ]; then
    BLUE_RUNNING=$(curl -k -s \
      "$NIFI_URL/nifi-api/process-groups/$BLUE_ID" \
      -H "Authorization: Bearer $TOKEN" | \
      jq -r '.component.runningCount')

    if [ "$BLUE_RUNNING" -gt 0 ]; then
      ACTIVE_ID=$BLUE_ID
      TARGET_NAME=$GREEN_NAME
      TARGET_ID=$GREEN_ID
    fi
  fi

  if [ -z "$ACTIVE_ID" ] && [ -n "$GREEN_ID" ]; then
    GREEN_RUNNING=$(curl -k -s \
      "$NIFI_URL/nifi-api/process-groups/$GREEN_ID" \
      -H "Authorization: Bearer $TOKEN" | \
      jq -r '.component.runningCount')

    if [ "$GREEN_RUNNING" -gt 0 ]; then
      ACTIVE_ID=$GREEN_ID
      TARGET_NAME=$BLUE_NAME
      TARGET_ID=$BLUE_ID
    fi
  fi

  # If neither running, default to Blue first deploy
  if [ -z "$ACTIVE_ID" ]; then
    TARGET_NAME=$BLUE_NAME
    TARGET_ID=$BLUE_ID
  fi

  echo "Active Flow ID: $ACTIVE_ID"
  echo "Deploy Target: $TARGET_NAME"

  # --------------------------------------------------
  # If target exists → delete it safely (inactive only)
  # --------------------------------------------------
  if [ -n "$TARGET_ID" ]; then
    echo "Cleaning inactive target..."

    REV=$(curl -k -s \
      "$NIFI_URL/nifi-api/process-groups/$TARGET_ID" \
      -H "Authorization: Bearer $TOKEN" | \
      jq -r '.revision.version')

    curl -k -s -X DELETE \
      "$NIFI_URL/nifi-api/process-groups/$TARGET_ID?version=$REV" \
      -H "Authorization: Bearer $TOKEN" > /dev/null

    sleep 3
  fi

  # --------------------------------------------------
  # Upload New Target Flow
  # --------------------------------------------------
  echo "Uploading new target flow..."

  curl -f -k -s -X POST \
    "$NIFI_URL/nifi-api/process-groups/$ROOT_ID/process-groups/upload" \
    -H "Authorization: Bearer $TOKEN" \
    -F "file=@$FLOW_FILE" \
    -F "groupName=$TARGET_NAME" \
    -F "positionX=400" \
    -F "positionY=200" > /dev/null

  sleep 3

  # Get new ID
  NEW_ID=$(curl -k -s \
    "$NIFI_URL/nifi-api/flow/process-groups/$ROOT_ID" \
    -H "Authorization: Bearer $TOKEN" | \
    jq -r ".processGroupFlow.flow.processGroups[]
           | select(.component.name==\"$TARGET_NAME\")
           | .component.id")

  # --------------------------------------------------
  # Start New Flow
  # --------------------------------------------------
  echo "Starting new flow..."

  curl -k -s -X PUT \
    "$NIFI_URL/nifi-api/flow/process-groups/$NEW_ID" \
    -H "Authorization: Bearer $TOKEN" \
    -H "Content-Type: application/json" \
    -d "{\"id\":\"$NEW_ID\",\"state\":\"RUNNING\"}" > /dev/null

  echo "New flow started."

  # --------------------------------------------------
  # Stop Old Flow (after switch)
  # --------------------------------------------------
  if [ -n "$ACTIVE_ID" ]; then
    echo "Stopping old active flow..."

    curl -k -s -X PUT \
      "$NIFI_URL/nifi-api/flow/process-groups/$ACTIVE_ID" \
      -H "Authorization: Bearer $TOKEN" \
      -H "Content-Type: application/json" \
      -d "{\"id\":\"$ACTIVE_ID\",\"state\":\"STOPPED\"}" > /dev/null

    echo "Old flow stopped."

    # --------------------------------------------------
    # Queue Purge Automation
    # --------------------------------------------------
    echo "Purging old queues..."

    CONNECTIONS=$(curl -k -s \
      "$NIFI_URL/nifi-api/flow/process-groups/$ACTIVE_ID/connections" \
      -H "Authorization: Bearer $TOKEN" | \
      jq -r '.connections[].id')

    for CID in $CONNECTIONS; do
      curl -k -s -X POST \
        "$NIFI_URL/nifi-api/flowfile-queues/$CID/drop-requests" \
        -H "Authorization: Bearer $TOKEN" \
        -H "Content-Type: application/json" \
        -d "{}" > /dev/null
    done

    echo "Queues purged."
  fi

  echo "Deployment complete for $BASE_NAME"
  echo "----------------------------------------------"

done

echo "=============================================="
echo " Blue/Green Deployment Finished Successfully"
echo "=============================================="
