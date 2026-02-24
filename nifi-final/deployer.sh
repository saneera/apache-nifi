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

for FLOW in /flows/*.json; do

  NAME=$(jq -r '.flowContents.name' "$FLOW")
  HASH=$(sha256sum "$FLOW" | awk '{print $1}')

  echo "-----------------------------------"
  echo "Processing: $NAME"
  echo "New hash: $HASH"

  # Find existing PG by name
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
      -F "name=$NAME" \
      -F "position[x]=0" \
      -F "position[y]=0" > /dev/null

    echo "Flow uploaded."

    # Get new PG ID
    NEW_ID=$(curl -k -s \
      "$NIFI_URL/nifi-api/flow/process-groups/$ROOT_ID" \
      -H "Authorization: Bearer $TOKEN" | \
      jq -r ".processGroupFlow.flow.processGroups[]
             | select(.component.name==\"$NAME\")
             | .component.id")

    # Get revision
    REV=$(curl -k -s \
      "$NIFI_URL/nifi-api/process-groups/$NEW_ID" \
      -H "Authorization: Bearer $TOKEN" | \
      jq -r '.revision.version')

    # Store hash in comments
    curl -k -s -X PUT \
      "$NIFI_URL/nifi-api/process-groups/$NEW_ID" \
      -H "Authorization: Bearer $TOKEN" \
      -H "Content-Type: application/json" \
      -d "{
            \"revision\": {\"version\": $REV},
            \"component\": {
              \"id\": \"$NEW_ID\",
              \"comments\": \"flow-hash=$HASH\"
            }
          }" > /dev/null

    echo "Hash stored in process group comments."
    continue
  fi

  echo "Flow exists: $EXISTING_ID"

  # ----------------------------------------------------------------
  # CASE 2: Process Group exists
  # ----------------------------------------------------------------

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

  sleep 2

  # Delete PG
  curl -k -s -X DELETE \
    "$NIFI_URL/nifi-api/process-groups/$EXISTING_ID?version=$REV" \
    -H "Authorization: Bearer $TOKEN" > /dev/null

  sleep 2

  # Re-upload flow
  curl -k -s -X POST \
    "$NIFI_URL/nifi-api/process-groups/$ROOT_ID/process-groups/upload" \
    -H "Authorization: Bearer $TOKEN" \
    -F "file=@$FLOW" \
    -F "name=$NAME" \
    -F "position[x]=0" \
    -F "position[y]=0" > /dev/null

  echo "Flow re-uploaded."

  # Get new ID
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

  # Store new hash
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

  echo "Flow replaced and hash updated."

done

echo "-----------------------------------"
echo "Deployment complete."
