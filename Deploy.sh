#!/usr/bin/env bash
set -euo pipefail

NIFI_API="https://localhost:8443/nifi-api"
PROD_PG_ID="78e9a564-019c-1000-43d7-4a94b42231e5"
FLOW_JSON="test-flow.json"
NIFI_USER="admin"
NIFI_PASS="admin123456!"

command -v jq >/dev/null 2>&1 || { echo "jq not found. Install: brew install jq"; exit 1; }
[[ -f "$FLOW_JSON" ]] || { echo "Flow JSON file not found: $FLOW_JSON"; exit 1; }

echo "1) Get NiFi token"
TOKEN=$(
  curl -sS -k \
    -H "Content-Type: application/x-www-form-urlencoded" \
    --data-urlencode "username=$NIFI_USER" \
    --data-urlencode "password=$NIFI_PASS" \
    "$NIFI_API/access/token"
)
[[ -n "$TOKEN" ]] || { echo "Failed to obtain NiFi token"; exit 1; }

echo "2) Get Process Group entity to read revision"
PG_ENTITY=$(
  curl -sS -k \
    -H "Authorization: Bearer $TOKEN" \
    "$NIFI_API/process-groups/$PROD_PG_ID"
)

REV_VERSION=$(echo "$PG_ENTITY" | jq -r '.revision.version')
REV_CLIENT=$(echo "$PG_ENTITY" | jq -r '.revision.clientId // empty')

# If clientId is empty, set one (NiFi generally accepts a clientId you choose)
if [[ -z "$REV_CLIENT" || "$REV_CLIENT" == "null" ]]; then
  REV_CLIENT="deploy-script-$(date +%s)"
fi

echo "   processGroupRevision.version = $REV_VERSION"
echo "   processGroupRevision.clientId = $REV_CLIENT"

echo "3) Build ProcessGroupImportEntity (processGroupRevision + versionedFlowSnapshot)"
REQ_BODY=$(
  jq -n \
    --arg cid "$REV_CLIENT" \
    --argjson ver "$REV_VERSION" \
    --slurpfile snap "$FLOW_JSON" \
    '{
      processGroupRevision: { clientId: $cid, version: $ver },
      disconnectedNodeAcknowledged: false,
      versionedFlowSnapshot: $snap[0]
    }'
)

echo "4) Replace Process Group contents"
RESP=$(
  curl -sS -k \
    -H "Authorization: Bearer $TOKEN" \
    -H "Content-Type: application/json" \
    -X PUT \
    --data-binary "$REQ_BODY" \
    "$NIFI_API/process-groups/$PROD_PG_ID/flow-contents"
)

# Pretty print if JSON; otherwise print raw (NiFi errors are often plain text)
echo "$RESP" | jq . 2>/dev/null || echo "$RESP"

echo "Deploy complete."




==========

trigger:
  branches:
    include:
      - main

pool:
  vmImage: ubuntu-latest

variables:
  NIFI_API: 'https://localhost:8443/nifi-api'
  PROD_PG_ID: '78e9a564-019c-1000-43d7-4a94b42231e5'
  FLOW_JSON: 'default/Test-flow-in-azure.json'

steps:
  - checkout: self

  - bash: |
      set -euo pipefail
      sudo apt-get update -y
      sudo apt-get install -y jq

      echo "1) Get NiFi token"
      TOKEN=$(curl -sS -k \
        -H "Content-Type: application/x-www-form-urlencoded" \
        --data-urlencode "username=$(NIFI_USER)" \
        --data-urlencode "password=$(NIFI_PASS)" \
        "$(NIFI_API)/access/token")

      if [[ -z "$TOKEN" ]]; then
        echo "Failed to obtain NiFi token"
        exit 1
      fi

      echo "2) Replace Prod Process Group contents using flow definition JSON"
      # NiFi endpoint: PUT /process-groups/{id}/flow-contents
      curl -sS -k \
        -H "Authorization: Bearer $TOKEN" \
        -H "Content-Type: application/json" \
        -X PUT \
        --data-binary "@$(FLOW_JSON)" \
        "$(NIFI_API)/process-groups/$(PROD_PG_ID)/flow-contents" \
        | jq .

      echo "Deploy complete."
    displayName: Deploy flow JSON to PROD (replace contents)
    env:
      NIFI_USER: $(NIFI_USER)
      NIFI_PASS: $(NIFI_PASS)
