#!/bin/sh
set -e

echo "Waiting for NiFi..."

until curl -k -s "$NIFI_URL/nifi-api/system-diagnostics" > /dev/null; do
  sleep 5
done

echo "NiFi ready."

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

# --------------------------------------------------
# Get Root PG ID
# --------------------------------------------------
ROOT_ID=$(curl -k -s \
  "$NIFI_URL/nifi-api/flow/process-groups/root" \
  -H "Authorization: Bearer $TOKEN" | \
  jq -r '.processGroupFlow.id')

echo "Root PG: $ROOT_ID"
echo "Starting flow deployment..."

# --------------------------------------------------
# Position Layout Configuration
# --------------------------------------------------
BASE_X=400
BASE_Y=200
OFFSET_X=650
OFFSET_Y=500
MAX_PER_ROW=3
INDEX=0

for ORIGINAL_FLOW in /flows/*.json; do

  NAME=$(jq -r '.flowContents.name' "$ORIGINAL_FLOW")

  echo "-----------------------------------"
  echo "Processing flow: $NAME"

  # --------------------------------------------------
  # Rewrite Remote Process Group URLs
  # --------------------------------------------------
  TMP_FLOW="/tmp/$(basename $ORIGINAL_FLOW)"

  jq --arg url "$TARGET_RPG_URL" '
    (.flowContents.remoteProcessGroups[]?.targetUris) = $url
    |
    (.flowContents.processGroups[]?.remoteProcessGroups[]?.targetUris) = $url
  ' "$ORIGINAL_FLOW" > "$TMP_FLOW"

  FLOW="$TMP_FLOW"

  # --------------------------------------------------
  # Calculate hash AFTER rewrite
  # --------------------------------------------------
  HASH=$(sha256sum "$FLOW" | awk '{print $1}')
  echo "Calculated hash: $HASH"

  # --------------------------------------------------
  # Calculate Position
  # --------------------------------------------------
  COL=$((INDEX % MAX_PER_ROW))
  ROW=$((INDEX / MAX_PER_ROW))
  POS_X=$((BASE_X + COL * OFFSET_X))
  POS_Y=$((BASE_Y + ROW * OFFSET_Y))
  INDEX=$((INDEX + 1))

  echo "Position: X=$POS_X Y=$POS_Y"

  # --------------------------------------------------
  # Check if PG exists
  # --------------------------------------------------
  EXISTING_ID=$(curl -k -s \
    "$NIFI_URL/nifi-api/flow/process-groups/$ROOT_ID" \
    -H "Authorization: Bearer $TOKEN" | \
    jq -r ".processGroupFlow.flow.processGroups[]?
           | select(.component.name==\"$NAME\")
           | .component.id")

  # ==================================================
  # CASE 1 — Flow does not exist
  # ==================================================
  if [ -z "$EXISTING_ID" ]; then
    echo "Flow does not exist. Uploading..."

    curl -k -s -X POST \
      "$NIFI_URL/nifi-api/process-groups/$ROOT_ID/process-groups/upload" \
      -H "Authorization: Bearer $TOKEN" \
      -F "file=@$FLOW" \
      -F "groupName=$NAME" \
      -F "positionX=$POS_X" \
      -F "positionY=$POS_Y" > /dev/null

    echo "Flow uploaded."

  else
    echo "Flow exists: $EXISTING_ID"

    DETAILS=$(curl -k -s \
      "$NIFI_URL/nifi-api/process-groups/$EXISTING_ID" \
      -H "Authorization: Bearer $TOKEN")

    STORED_HASH=$(echo "$DETAILS" | jq -r '.component.comments // empty' | sed 's/flow-hash=//')

    echo "Stored hash: $STORED_HASH"

    if [ "$HASH" = "$STORED_HASH" ]; then
      echo "No changes detected. Skipping."
      continue
    fi

    echo "Change detected. Replacing flow..."

    REV=$(echo "$DETAILS" | jq -r '.revision.version')

    # Stop PG
    curl -k -s -X PUT \
      "$NIFI_URL/nifi-api/flow/process-groups/$EXISTING_ID" \
      -H "Authorization: Bearer $TOKEN" \
      -H "Content-Type: application/json" \
      -d "{
            \"id\": \"$EXISTING_ID\",
            \"state\": \"STOPPED\"
          }" > /dev/null

    sleep 3

    # Delete PG
    curl -k -s -X DELETE \
      "$NIFI_URL/nifi-api/process-groups/$EXISTING_ID?version=$REV" \
      -H "Authorization: Bearer $TOKEN" > /dev/null

    sleep 3

    # Re-upload
    curl -k -s -X POST \
      "$NIFI_URL/nifi-api/process-groups/$ROOT_ID/process-groups/upload" \
      -H "Authorization: Bearer $TOKEN" \
      -F "file=@$FLOW" \
      -F "groupName=$NAME" \
      -F "positionX=$POS_X" \
      -F "positionY=$POS_Y" > /dev/null

    echo "Flow replaced."
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

  curl -k -s -X PUT \
    "$NIFI_URL/nifi-api/process-groups/$NEW_ID" \
    -H "Authorization: Bearer $TOKEN" \
    -H "Content-Type: application/json" \
    -d "{
          \"revision\": {\"version\": $NEW_REV},
          \"component\": {
            \"id\": \"$NEW_ID\",
            \"comments\": \"flow-hash=$HASH\"
          }
        }" > /dev/null

  echo "Hash stored."

done

echo "-----------------------------------"
echo "Deployment complete."
