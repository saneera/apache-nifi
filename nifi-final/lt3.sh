#!/bin/bash
set -e

# --- Configuration ---
FLOWS_DIR="/data/flows"
CLI="/opt/nifi-toolkit/bin/cli.sh"
# URLs and IDs should be passed via K8s Env
NIFI_PROPS="/tmp/nifi.props"
REG_PROPS="/tmp/reg.props"

echo "baseUrl=$NIFI_URL" > "$NIFI_PROPS"
echo "baseUrl=$REG_URL" > "$REG_PROPS"

# Get Auth Token once for the session
TOKEN=$(curl -k -s -X POST "$NIFI_URL/nifi-api/access/token" \
  -H "Content-Type: application/x-form-urlencoded" \
  -d "username=$NIFI_USER&password=$NIFI_PASSWORD")

# --- Loop Through All Flow Files ---
for FLOW_FILE in "$FLOWS_DIR"/*.json; do
    [ -e "$FLOW_FILE" ] || continue

    echo "--------------------------------------------------"
    echo "Processing file: $(basename "$FLOW_FILE")"

    # 1. Extract Flow Name from the JSON content
    FLOW_NAME=$(jq -r '.flowContents.name // .header.flowName' "$FLOW_FILE")

    if [ -z "$FLOW_NAME" ] || [ "$FLOW_NAME" == "null" ]; then
        echo "Error: Could not find flow name in $FLOW_FILE. Skipping."
        continue
    fi

    # 2. Registry Logic: Find or Create Flow
    FLOW_ID=$($CLI registry list-flows -p "$REG_PROPS" -b "$BUCKET_ID" | grep "$FLOW_NAME" | awk '{print $3}')

    if [ -z "$FLOW_ID" ]; then
        echo "Action: Creating new flow '$FLOW_NAME' (Version 1)"
        FLOW_ID=$($CLI registry create-flow -p "$REG_PROPS" -b "$BUCKET_ID" -fn "$FLOW_NAME")
        $CLI registry import-flow-version -p "$REG_PROPS" -f "$FLOW_ID" -i "$FLOW_FILE"
    else
        echo "Action: Checking for updates to '$FLOW_NAME'..."
        $CLI registry export-flow-version -p "$REG_PROPS" -f "$FLOW_ID" -o /tmp/registry_current.json

        # Compare logic: ignore metadata, only compare flowContents
        if diff -q <(jq -S '.flowContents' "$FLOW_FILE") <(jq -S '.flowContents' /tmp/registry_current.json) > /dev/null; then
            echo "Result: No changes detected. Version remains same."
        else
            echo "Result: Changes detected. Committing new version to Registry."
            $CLI registry import-flow-version -p "$REG_PROPS" -f "$FLOW_ID" -i "$FLOW_FILE"
        fi
    fi

    # 3. NiFi Sync: Update Canvas
    PG_ID=$($CLI nifi list-pgs -p "$NIFI_PROPS" -bt "$TOKEN" | grep "$FLOW_NAME" | awk '{print $3}' | head -n 1)
    LATEST_REG_VER=$($CLI registry list-flow-versions -p "$REG_PROPS" -f "$FLOW_ID" | head -n 1 | awk '{print $1}')

    if [ -z "$PG_ID" ]; then
        echo "Action: Importing Process Group to NiFi canvas..."
        $CLI nifi pg-import -p "$NIFI_PROPS" -bt "$TOKEN" -b "$BUCKET_ID" -f "$FLOW_ID" -rc "$REG_CLIENT_ID"
    else
        echo "Action: Updating NiFi Process Group to Version $LATEST_REG_VER"
        # Always run change-version to ensure NiFi is in sync with Registry's latest
        $CLI nifi pg-change-version -p "$NIFI_PROPS" -bt "$TOKEN" -pgid "$PG_ID" -v "$LATEST_REG_VER"
    fi
done

echo "--------------------------------------------------"
echo "All flows processed."


spec:
  template:
    spec:
      containers:
      - name: nifi-toolkit
        image: apache/nifi-toolkit:2.0.0 # Or your 2.7 image
        command: ["/bin/bash", "/scripts/sync-flows.sh"]
        env:
        - name: NIFI_URL
          value: "https://nifi:8443"
        - name: REG_URL
          value: "http://nifi-registry:18080"
        - name: BUCKET_ID
          value: "your-bucket-uuid"
        - name: REGISTRY_CLIENT_ID
          value: "your-client-uuid"
        volumeMounts:
        - name: scripts-volume
          mountPath: /scripts
        - name: flows-volume
          mountPath: /data/flows
      volumes:
      - name: scripts-volume
        configMap:
          name: nifi-scripts-cm
      - name: flows-volume
        configMap:
          name: nifi-flows-cm # This CM contains all your .json files




NIFI_PROPS="/tmp/nifi.properties"
cat <<EOF > "$NIFI_PROPS"
baseUrl=$NIFI_BASE_URL
EOF

REG_PROPS="/tmp/reg.properties"
cat <<EOF > "$REG_PROPS"
baseUrl=$REG_BASE_URL
EOF




=============

#!/bin/bash
set -e

NIFI_URL=https://nifi:8443
REGISTRY_URL=https://nifi-registry:18443

FLOW_FILE=$1
BUCKET_ID=$2

USERNAME=$NIFI_USERNAME
PASSWORD=$NIFI_PASSWORD

echo "Processing flow file: $FLOW_FILE"

############################################
# Get NiFi access token
############################################
TOKEN=$(curl -k -s -X POST \
  "$NIFI_URL/nifi-api/access/token" \
  -H "Content-Type: application/x-www-form-urlencoded" \
  -d "username=$USERNAME&password=$PASSWORD")

############################################
# Extract Flow Name
############################################
FLOW_NAME=$(jq -r '.flowContents.name // .header.flowName' "$FLOW_FILE")

if [ -z "$FLOW_NAME" ] || [ "$FLOW_NAME" == "null" ]; then
  echo "Cannot determine flow name"
  exit 1
fi

echo "Flow name: $FLOW_NAME"

############################################
# Check if flow exists in Registry
############################################
FLOW_ID=$(curl -k -s \
 "$REGISTRY_URL/nifi-registry-api/buckets/$BUCKET_ID/flows" \
 | jq -r ".[] | select(.name==\"$FLOW_NAME\") | .identifier")

############################################
# Create flow if not exists
############################################
if [ -z "$FLOW_ID" ]; then

  echo "Creating new flow in registry..."

  FLOW_ID=$(curl -k -s -X POST \
   "$REGISTRY_URL/nifi-registry-api/buckets/$BUCKET_ID/flows" \
   -H "Content-Type: application/json" \
   -d "{
      \"name\": \"$FLOW_NAME\",
      \"description\": \"Created by automation\"
   }" | jq -r '.identifier')

fi

echo "Registry Flow ID: $FLOW_ID"

############################################
# Import new flow version
############################################
echo "Uploading new flow version..."

curl -k -s -X POST \
 "$REGISTRY_URL/nifi-registry-api/buckets/$BUCKET_ID/flows/$FLOW_ID/versions" \
 -H "Content-Type: application/json" \
 -d @"$FLOW_FILE" > /dev/null

############################################
# Get latest registry version
############################################
LATEST_REG_VER=$(curl -k -s \
 "$REGISTRY_URL/nifi-registry-api/buckets/$BUCKET_ID/flows/$FLOW_ID/versions/latest" \
 | jq '.version')

echo "Latest Registry Version: $LATEST_REG_VER"

############################################
# Find process group in NiFi
############################################
PG_ID=$(curl -k -s \
 -H "Authorization: Bearer $TOKEN" \
 "$NIFI_URL/nifi-api/process-groups/root" \
 | jq -r ".processGroupFlow.flow.processGroups[] | select(.component.name==\"$FLOW_NAME\") | .component.id")

############################################
# Import PG if not exists
############################################
if [ -z "$PG_ID" ]; then

  echo "Importing process group..."

  curl -k -s -X POST \
   "$NIFI_URL/nifi-api/process-groups/root/process-groups" \
   -H "Authorization: Bearer $TOKEN" \
   -H "Content-Type: application/json" \
   -d "{
     \"revision\": {\"version\":0},
     \"component\":{
        \"name\":\"$FLOW_NAME\",
        \"position\":{\"x\":0,\"y\":0}
     }
   }"

  sleep 5

  PG_ID=$(curl -k -s \
   -H "Authorization: Bearer $TOKEN" \
   "$NIFI_URL/nifi-api/process-groups/root" \
   | jq -r ".processGroupFlow.flow.processGroups[] | select(.component.name==\"$FLOW_NAME\") | .component.id")

fi

echo "Process Group ID: $PG_ID"

############################################
# Get revision
############################################
REVISION=$(curl -k -s \
 -H "Authorization: Bearer $TOKEN" \
 "$NIFI_URL/nifi-api/process-groups/$PG_ID" \
 | jq '.revision.version')

############################################
# Connect PG to registry
############################################
echo "Updating version control..."

curl -k -s -X PUT \
 "$NIFI_URL/nifi-api/versions/process-groups/$PG_ID" \
 -H "Authorization: Bearer $TOKEN" \
 -H "Content-Type: application/json" \
 -d "{
   \"processGroupRevision\": {
      \"version\": $REVISION
   },
   \"versionControlInformation\": {
      \"bucketId\": \"$BUCKET_ID\",
      \"flowId\": \"$FLOW_ID\",
      \"version\": $LATEST_REG_VER
   }
 }"

echo "Deployment complete"
