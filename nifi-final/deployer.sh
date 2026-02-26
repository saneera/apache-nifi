#!/bin/sh
set -e

echo "-----------------------------------"
echo "NiFi Flow Deployer Starting"
echo "-----------------------------------"

# --------------------------------------------------
# Wait for NiFi API (max 3 minutes)
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

if [ -z "$TOKEN" ]; then
  echo "Failed to obtain NiFi token"
  exit 1
fi

echo "Authentication successful."

# --------------------------------------------------
# Get Root Process Group ID
# --------------------------------------------------
ROOT_ID=$(curl -k -s \
  "$NIFI_URL/nifi-api/flow/process-groups/root" \
  -H "Authorization: Bearer $TOKEN" | \
  jq -r '.processGroupFlow.id')

echo "Root PG: $ROOT_ID"

FOUND=false

BASE_X=400
BASE_Y=200
OFFSET_X=650
OFFSET_Y=500
MAX_PER_ROW=3
INDEX=0

for ORIGINAL_FLOW in /flows/*.json; do

  [ -f "$ORIGINAL_FLOW" ] || continue
  FOUND=true

  NAME=$(jq -r '.flowContents.name' "$ORIGINAL_FLOW")
  echo "-----------------------------------"
  echo "Processing flow: $NAME"

  TMP_FLOW="/tmp/$(basename $ORIGINAL_FLOW)"

  jq --arg url "$TARGET_RPG_URL" '
    (.flowContents.remoteProcessGroups[]?.targetUris) = $url
    |
    (.flowContents.processGroups[]?.remoteProcessGroups[]?.targetUris) = $url
  ' "$ORIGINAL_FLOW" > "$TMP_FLOW"

  FLOW="$TMP_FLOW"

  HASH=$(sha256sum "$FLOW" | awk '{print $1}')
  echo "Calculated hash: $HASH"

  COL=$((INDEX % MAX_PER_ROW))
  ROW=$((INDEX / MAX_PER_ROW))
  POS_X=$((BASE_X + COL * OFFSET_X))
  POS_Y=$((BASE_Y + ROW * OFFSET_Y))
  INDEX=$((INDEX + 1))

  EXISTING_ID=$(curl -k -s \
    "$NIFI_URL/nifi-api/flow/process-groups/$ROOT_ID" \
    -H "Authorization: Bearer $TOKEN" | \
    jq -r ".processGroupFlow.flow.processGroups[]?
           | select(.component.name==\"$NAME\")
           | .component.id")

  if [ -z "$EXISTING_ID" ]; then
    echo "Flow does not exist. Uploading..."

    curl -f -k -s -X POST \
      "$NIFI_URL/nifi-api/process-groups/$ROOT_ID/process-groups/upload" \
      -H "Authorization: Bearer $TOKEN" \
      -F "file=@$FLOW" \
      -F "groupName=$NAME" \
      -F "positionX=$POS_X" \
      -F "positionY=$POS_Y" > /dev/null

  else
    echo "Flow exists: $EXISTING_ID"

    DETAILS=$(curl -k -s \
      "$NIFI_URL/nifi-api/process-groups/$EXISTING_ID" \
      -H "Authorization: Bearer $TOKEN")

    STORED_HASH=$(echo "$DETAILS" | jq -r '.component.comments // empty' | sed 's/flow-hash=//')

    if [ "$HASH" = "$STORED_HASH" ]; then
      echo "No changes detected. Skipping."
      continue
    fi

    echo "Change detected. Replacing flow safely..."

    # --------------------------------------------------
    # Stop processors
    # --------------------------------------------------
    curl -f -k -s -X PUT \
      "$NIFI_URL/nifi-api/flow/process-groups/$EXISTING_ID" \
      -H "Authorization: Bearer $TOKEN" \
      -H "Content-Type: application/json" \
      -d "{\"id\":\"$EXISTING_ID\",\"state\":\"STOPPED\"}" > /dev/null

    echo "Waiting for processors to stop..."

    until curl -k -s \
      "$NIFI_URL/nifi-api/process-groups/$EXISTING_ID" \
      -H "Authorization: Bearer $TOKEN" | \
      jq -e '.component.runningCount == 0' > /dev/null; do
      sleep 2
    done

    echo "Processors stopped."

    # --------------------------------------------------
    # Disable controller services
    # --------------------------------------------------
    echo "Disabling controller services..."

    CS_IDS=$(curl -k -s \
      "$NIFI_URL/nifi-api/flow/process-groups/$EXISTING_ID/controller-services" \
      -H "Authorization: Bearer $TOKEN" | \
      jq -r '.controllerServices[]?.id')

    for CS_ID in $CS_IDS; do

      CS_DETAILS=$(curl -k -s \
        "$NIFI_URL/nifi-api/controller-services/$CS_ID" \
        -H "Authorization: Bearer $TOKEN")

      CS_REV=$(echo "$CS_DETAILS" | jq -r '.revision.version')

      curl -f -k -s -X PUT \
        "$NIFI_URL/nifi-api/controller-services/$CS_ID/run-status" \
        -H "Authorization: Bearer $TOKEN" \
        -H "Content-Type: application/json" \
        -d "{\"revision\":{\"version\":$CS_REV},\"state\":\"DISABLED\"}" \
        > /dev/null

    done

    echo "Controller services disabled."
    sleep 3

    # --------------------------------------------------
    # Delete old process group
    # --------------------------------------------------
    REV=$(curl -k -s \
      "$NIFI_URL/nifi-api/process-groups/$EXISTING_ID" \
      -H "Authorization: Bearer $TOKEN" | \
      jq -r '.revision.version')

    curl -f -k -s -X DELETE \
      "$NIFI_URL/nifi-api/process-groups/$EXISTING_ID?version=$REV" \
      -H "Authorization: Bearer $TOKEN" > /dev/null

    echo "Old flow deleted."

    sleep 3

    # --------------------------------------------------
    # Upload new flow
    # --------------------------------------------------
    curl -f -k -s -X POST \
      "$NIFI_URL/nifi-api/process-groups/$ROOT_ID/process-groups/upload" \
      -H "Authorization: Bearer $TOKEN" \
      -F "file=@$FLOW" \
      -F "groupName=$NAME" \
      -F "positionX=$POS_X" \
      -F "positionY=$POS_Y" > /dev/null

    echo "New flow uploaded."
  fi

  # --------------------------------------------------
  # Store hash in comments
  # --------------------------------------------------
  NEW_ID=$(curl -k -s \
    "$NIFI_URL/nifi-api/flow/process-groups/$ROOT_ID" \
    -H "Authorization: Bearer $TOKEN" | \
    jq -r ".processGroupFlow.flow.processGroups[]
           | select(.component.name==\"$NAME\")
           | .component.id")

  NEW_REV=$(curl -k -s \
    "$NIFI_URL/nifi-api/process-groups/$NEW_ID" \
    -H "Authorization: Bearer $TOKEN" | \
    jq -r '.revision.version')

  curl -f -k -s -X PUT \
    "$NIFI_URL/nifi-api/process-groups/$NEW_ID" \
    -H "Authorization: Bearer $TOKEN" \
    -H "Content-Type: application/json" \
    -d "{\"revision\":{\"version\":$NEW_REV},
         \"component\":{\"id\":\"$NEW_ID\",\"comments\":\"flow-hash=$HASH\"}}" \
    > /dev/null

  echo "Flow deployed successfully."

done

if [ "$FOUND" = false ]; then
  echo "No flow files found. Nothing to deploy."
fi

echo "-----------------------------------"
echo "Deployment complete."
echo "-----------------------------------"
