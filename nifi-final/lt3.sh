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

