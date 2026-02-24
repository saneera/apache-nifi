#!/bin/sh
set -e

echo "Waiting for NiFi..."

until curl -k -s "$NIFI_URL/nifi-api/system-diagnostics" > /dev/null; do
  sleep 5
done

echo "NiFi ready."

# Get auth token
TOKEN=$(curl -k -s -X POST \
  "$NIFI_URL/nifi-api/access/token" \
  -H "Content-Type: application/x-www-form-urlencoded" \
  -d "username=$USERNAME&password=$PASSWORD")

if [ -z "$TOKEN" ]; then
  echo "Failed to obtain NiFi token"
  exit 1
fi

# Get Root PG ID
ROOT_ID=$(curl -k -s \
  "$NIFI_URL/nifi-api/flow/process-groups/root" \
  -H "Authorization: Bearer $TOKEN" | \
  jq -r '.processGroupFlow.id')

echo "Root PG: $ROOT_ID"

echo "Starting flow deployment..."

# -------------------------------
# Position configuration
# -------------------------------
BASE_X=400
BASE_Y=200
OFFSET_X=600
OFFSET_Y=500
MAX_PER_ROW=3
INDEX=0

for FLOW in /flows/*.json; do

  NAME=$(jq -r '.flowContents.name' "$FLOW")
  HASH=$(sha256sum "$FLOW" | awk '{print $1}')

  # Calculate grid position
  COL=$((INDEX % MAX_PER_ROW))
  ROW=$((INDEX / MAX_PER_ROW))

  POS_X=$((BASE_X + COL * OFFSET_X))
  POS_Y=$((BASE_Y + ROW * OFFSET_Y))

  INDEX=$((INDEX + 1))

  echo "-----------------------------------"
  echo "Processing: $NAME"
  echo "New hash: $HASH"
  echo "Position: X=$POS_X Y=$POS_Y"

  EXISTING_ID=$(curl -k -s \
    "$NIFI_URL/nifi-api/flow/process-groups/$ROOT_ID" \
    -H "Authorization: Bearer $TOKEN" | \
    jq -r ".processGroupFlow.flow.processGroups[]?
           | select(.component.name==\"$NAME\")
           | .component.id")

  # ----------------------------------------------------------------
  # CASE 1: Process Group does not exist
  # ----------------------------------------------------------------
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

    if [ "$HASH" = "$STORED_HASH" ]; then
      echo "No changes detected. Skipping."
      continue
    fi

    echo "Change detected. Replacing flow..."

    REV=$(echo "$DETAILS" | jq -r '.revision.version')

    curl -k -s -X PUT \
      "$NIFI_URL/nifi-api/flow/process-groups/$EXISTING_ID" \
      -H "Authorization: Bearer $TOKEN" \
      -H "Content-Type: application/json" \
      -d "{
            \"id\": \"$EXISTING_ID\",
            \"state\": \"STOPPED\"
          }" > /dev/null

    sleep 2

    curl -k -s -X DELETE \
      "$NIFI_URL/nifi-api/process-groups/$EXISTING_ID?version=$REV" \
      -H "Authorization: Bearer $TOKEN" > /dev/null

    sleep 2

    curl -k -s -X POST \
      "$NIFI_URL/nifi-api/process-groups/$ROOT_ID/process-groups/upload" \
      -H "Authorization: Bearer $TOKEN" \
      -F "file=@$FLOW" \
      -F "groupName=$NAME" \
      -F "positionX=$POS_X" \
      -F "positionY=$POS_Y" > /dev/null

    echo "Flow re-uploaded."
  fi

  # Get new ID after upload
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

  echo "Hash updated."

done

echo "-----------------------------------"
echo "Deployment complete."
