#!/bin/bash
set -e

# ─────────────────────────────────────────
# CONFIG
# ─────────────────────────────────────────
NIFI_URL="${NIFI_URL:-http://nifi:8080}"
NIFI_USER="${NIFI_USER:-admin}"
NIFI_PASS="${NIFI_PASSWORD:-admin}"
FLOWS_DIR="${FLOWS_DIR:-/flows}"
PARAM_CONTEXT_NAME="app-parameters"

echo "=============================="
echo "  NiFi Deployment Started"
echo "=============================="
echo "  NIFI_URL  : $NIFI_URL"
echo "  FLOWS_DIR : $FLOWS_DIR"
echo "=============================="

# ─────────────────────────────────────────
# HELPERS
# ─────────────────────────────────────────
nifi_get() {
    curl -sk -H "Authorization: Bearer $TOKEN" \
         -H "Content-Type: application/json" \
         "$NIFI_URL/nifi-api/$1"
}

nifi_post() {
    curl -sk -X POST \
         -H "Authorization: Bearer $TOKEN" \
         -H "Content-Type: application/json" \
         -d "$2" \
         "$NIFI_URL/nifi-api/$1"
}

nifi_put() {
    curl -sk -X PUT \
         -H "Authorization: Bearer $TOKEN" \
         -H "Content-Type: application/json" \
         -d "$2" \
         "$NIFI_URL/nifi-api/$1"
}

nifi_delete() {
    curl -sk -X DELETE \
         -H "Authorization: Bearer $TOKEN" \
         -H "Content-Type: application/json" \
         "$NIFI_URL/nifi-api/$1"
}

# Check jq is available
if ! command -v jq &> /dev/null; then
    echo "ERROR: jq is required but not installed"
    exit 1
fi

# ─────────────────────────────────────────
# STEP 0 — AUTH
# ─────────────────────────────────────────
echo ""
echo ">> Authenticating..."
TOKEN=$(curl -sk -X POST \
    -d "username=$NIFI_USER&password=$NIFI_PASS" \
    "$NIFI_URL/nifi-api/access/token")

if [ -z "$TOKEN" ]; then
    echo "ERROR: Failed to get token"
    exit 1
fi
echo "   ✓ Authenticated"

# Get root PG ID
ROOT_ID=$(nifi_get "flow/process-groups/root" | jq -r '.processGroupFlow.id')
echo "   Root PG ID: $ROOT_ID"

# ─────────────────────────────────────────
# STEP 1 — PARAMETER CONTEXT
# ─────────────────────────────────────────
echo ""
echo "-- Step 1: Deploy Parameters --"

# Find existing parameter context
CONTEXT_RESPONSE=$(nifi_get "flow/parameter-contexts")
CONTEXT_ID=$(echo "$CONTEXT_RESPONSE" | \
    jq -r --arg name "$PARAM_CONTEXT_NAME" \
    '.parameterContexts[] | select(.component.name == $name) | .id' 2>/dev/null)
CONTEXT_VERSION=$(echo "$CONTEXT_RESPONSE" | \
    jq -r --arg name "$PARAM_CONTEXT_NAME" \
    '.parameterContexts[] | select(.component.name == $name) | .revision.version' 2>/dev/null)

if [ -z "$CONTEXT_ID" ]; then
    echo "   Creating new parameter context..."
    CREATE_RESP=$(nifi_post "parameter-contexts" "{
        \"revision\": {\"version\": 0},
        \"component\": {
            \"name\": \"$PARAM_CONTEXT_NAME\",
            \"description\": \"Managed by ArgoCD K8s Job\",
            \"parameters\": []
        }
    }")
    CONTEXT_ID=$(echo "$CREATE_RESP" | jq -r '.id')
    CONTEXT_VERSION=0
    echo "   ✓ Created: $CONTEXT_ID"
else
    echo "   Found existing context: $CONTEXT_ID (version: $CONTEXT_VERSION)"
fi

# Build parameters JSON array from env vars
build_params() {
    PARAMS="["
    FIRST=true

    add_param() {
        local KEY="$1"
        local VALUE="$2"
        local SENSITIVE="$3"

        if [ -z "$VALUE" ]; then
            echo "   SKIP: $KEY (not set)"
            return
        fi

        # Escape special chars in value
        VALUE=$(echo "$VALUE" | sed 's/\\/\\\\/g; s/"/\\"/g')

        if [ "$FIRST" = true ]; then
            FIRST=false
        else
            PARAMS="$PARAMS,"
        fi

        PARAMS="$PARAMS{
            \"parameter\": {
                \"name\": \"$KEY\",
                \"value\": \"$VALUE\",
                \"sensitive\": $SENSITIVE
            }
        }"
    }

    # Non-sensitive — from ConfigMap
    add_param "otel.endpoint"    "$OTEL_ENDPOINT"    "false"
    add_param "db.url"           "$DB_URL"           "false"
    add_param "smtp.host"        "$SMTP_HOST"        "false"
    add_param "smtp.port"        "$SMTP_PORT"        "false"
    add_param "source.directory" "$SOURCE_DIRECTORY" "false"

    # Sensitive — from Secret
    add_param "db.password"      "$DB_PASSWORD"      "true"
    add_param "smtp.password"    "$SMTP_PASSWORD"    "true"
    add_param "api.key"          "$API_KEY"          "true"

    PARAMS="$PARAMS]"
    echo "$PARAMS"
}

PARAMS_JSON=$(build_params)

# Submit parameter update request
echo "   Submitting parameter update..."
UPDATE_RESP=$(nifi_post "parameter-contexts/$CONTEXT_ID/update-requests" "{
    \"revision\": {\"version\": $CONTEXT_VERSION},
    \"component\": {
        \"id\": \"$CONTEXT_ID\",
        \"name\": \"$PARAM_CONTEXT_NAME\",
        \"parameters\": $PARAMS_JSON
    }
}")

REQUEST_ID=$(echo "$UPDATE_RESP" | jq -r '.request.requestId')

if [ -z "$REQUEST_ID" ] || [ "$REQUEST_ID" = "null" ]; then
    echo "   ERROR: Failed to submit parameter update"
    echo "   Response: $UPDATE_RESP"
    exit 1
fi

# Poll until complete
echo "   Waiting for parameter update..."
for i in $(seq 1 20); do
    POLL=$(nifi_get "parameter-contexts/$CONTEXT_ID/update-requests/$REQUEST_ID")
    COMPLETE=$(echo "$POLL" | jq -r '.request.complete')
    STATE=$(echo "$POLL" | jq -r '.request.state')
    echo "   Poll $i/20: state=$STATE"
    if [ "$COMPLETE" = "true" ]; then
        # Clean up
        nifi_delete "parameter-contexts/$CONTEXT_ID/update-requests/$REQUEST_ID" > /dev/null
        echo "   ✓ Parameters updated"
        break
    fi
    sleep 3
done

# ─────────────────────────────────────────
# STEP 2 — DEPLOY FLOWS
# ─────────────────────────────────────────
echo ""
echo "-- Step 2: Deploy Flows --"

FLOW_FILES=$(find "$FLOWS_DIR" -name "*.json" | sort)

if [ -z "$FLOW_FILES" ]; then
    echo "   WARNING: No flow files found in $FLOWS_DIR"
else
    for FLOW_FILE in $FLOW_FILES; do
        echo ""
        echo "   Processing: $(basename $FLOW_FILE)"

        FLOW_DEF=$(cat "$FLOW_FILE")
        PG_NAME=$(echo "$FLOW_DEF" | jq -r '.flowContents.name')

        if [ -z "$PG_NAME" ] || [ "$PG_NAME" = "null" ]; then
            PG_NAME=$(basename "$FLOW_FILE" .json)
        fi

        echo "   Flow name: $PG_NAME"

        # Check if PG already exists
        EXISTING=$(nifi_get "flow/process-groups/$ROOT_ID")
        PG_ID=$(echo "$EXISTING" | \
            jq -r --arg name "$PG_NAME" \
            '.processGroupFlow.flow.processGroups[] | select(.component.name == $name) | .id' 2>/dev/null)
        PG_VERSION=$(echo "$EXISTING" | \
            jq -r --arg name "$PG_NAME" \
            '.processGroupFlow.flow.processGroups[] | select(.component.name == $name) | .revision.version' 2>/dev/null)

        if [ -z "$PG_ID" ]; then
            # ── CREATE NEW ──
            echo "   New flow — creating..."
            CREATE_RESP=$(nifi_post "process-groups/$ROOT_ID/process-groups" "{
                \"revision\": {\"version\": 0},
                \"disconnectedNodeAcknowledged\": false,
                \"component\": {
                    \"position\": {\"x\": 100, \"y\": 100},
                    \"flowDefinition\": $FLOW_DEF
                }
            }")
            NEW_PG_ID=$(echo "$CREATE_RESP" | jq -r '.id')
            echo "   ✓ Created: $NEW_PG_ID"
        else
            # ── UPDATE EXISTING ──
            echo "   Existing flow found: $PG_ID"

            # Wait for queues to drain
            echo "   Waiting for queues to drain..."
            for i in $(seq 1 30); do
                QUEUED=$(nifi_get "process-groups/$PG_ID" | \
                    jq -r '.component.queuedCount // "0"' | \
                    tr -d ',')
                if [ "$QUEUED" = "0" ] || [ -z "$QUEUED" ]; then
                    echo "   ✓ Queues empty"
                    break
                fi
                echo "   Queued: $QUEUED — waiting... ($i/30)"
                sleep 5
            done

            # Stop PG
            echo "   Stopping process group..."
            nifi_put "flow/process-groups/$PG_ID" "{
                \"id\": \"$PG_ID\",
                \"state\": \"STOPPED\",
                \"disconnectedNodeAcknowledged\": false
            }" > /dev/null

            # Wait until stopped
            for i in $(seq 1 20); do
                RUNNING=$(nifi_get "flow/process-groups/$PG_ID" | \
                    jq -r '.runningCount // 0')
                if [ "$RUNNING" = "0" ]; then
                    echo "   ✓ Stopped"
                    break
                fi
                echo "   Running: $RUNNING — waiting... ($i/20)"
                sleep 3
            done

            # Get fresh revision
            PG_VERSION=$(nifi_get "process-groups/$PG_ID" | \
                jq -r '.revision.version')

            # Update flow
            echo "   Uploading new flow definition..."
            UPDATE=$(nifi_put "process-groups/$PG_ID" "{
                \"revision\": {\"version\": $PG_VERSION},
                \"disconnectedNodeAcknowledged\": false,
                \"component\": {
                    \"id\": \"$PG_ID\",
                    \"flowDefinition\": $FLOW_DEF
                }
            }")

            STATUS=$(echo "$UPDATE" | jq -r '.component.name // empty')
            if [ -n "$STATUS" ]; then
                echo "   ✓ Flow updated"
            else
                echo "   ERROR: $(echo $UPDATE | jq -r '.message // .')"
                exit 1
            fi

            # Restart
            echo "   Restarting..."
            nifi_put "flow/process-groups/$PG_ID" "{
                \"id\": \"$PG_ID\",
                \"state\": \"RUNNING\",
                \"disconnectedNodeAcknowledged\": false
            }" > /dev/null
            echo "   ✓ Restarted"
        fi
    done
fi

# ─────────────────────────────────────────
# STEP 3 — ASSIGN PARAMETER CONTEXT
# ─────────────────────────────────────────
echo ""
echo "-- Step 3: Assign Parameter Context --"

ALL_PGS=$(nifi_get "flow/process-groups/$ROOT_ID")
PG_IDS=$(echo "$ALL_PGS" | jq -r '.processGroupFlow.flow.processGroups[] | .id')
PG_NAMES=$(echo "$ALL_PGS" | jq -r '.processGroupFlow.flow.processGroups[] | .component.name')

echo "$ALL_PGS" | jq -c '.processGroupFlow.flow.processGroups[]' | while read PG; do
    PG_ID=$(echo "$PG" | jq -r '.id')
    PG_NAME=$(echo "$PG" | jq -r '.component.name')
    PG_VER=$(echo "$PG" | jq -r '.revision.version')

    RESP=$(nifi_put "process-groups/$PG_ID" "{
        \"revision\": {\"version\": $PG_VER},
        \"disconnectedNodeAcknowledged\": false,
        \"component\": {
            \"id\": \"$PG_ID\",
            \"parameterContext\": {\"id\": \"$CONTEXT_ID\"}
        }
    }")

    NAME=$(echo "$RESP" | jq -r '.component.name // empty')
    if [ -n "$NAME" ]; then
        echo "   ✓ $PG_NAME"
    else
        echo "   ✗ $PG_NAME — $(echo $RESP | jq -r '.message // .')"
    fi
done

# ─────────────────────────────────────────
# STEP 4 — START ALL
# ─────────────────────────────────────────
echo ""
echo "-- Step 4: Start All Process Groups --"

echo "$ALL_PGS" | jq -c '.processGroupFlow.flow.processGroups[]' | while read PG; do
    PG_ID=$(echo "$PG" | jq -r '.id')
    PG_NAME=$(echo "$PG" | jq -r '.component.name')

    nifi_put "flow/process-groups/$PG_ID" "{
        \"id\": \"$PG_ID\",
        \"state\": \"RUNNING\",
        \"disconnectedNodeAcknowledged\": false
    }" > /dev/null

    echo "   ✓ Started: $PG_NAME"
done

echo ""
echo "=============================="
echo "  NiFi Deployment Complete ✓"
echo "=============================="
