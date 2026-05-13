#!/usr/bin/env bash
# ─────────────────────────────────────────────────────────────────────────────
# Custom entrypoint wrapper for Openfire 5.0.4 on Kubernetes.
#
# Responsibilities:
#   1. Wait for MySQL to be reachable
#   2. Apply Openfire MySQL schema (first boot only)
#   3. Seed ofProperty rows: domain, REST API, monitoring, default services
#      (MUC, PubSub, Search) - idempotent, guarded by a marker file
#   4. Write openfire.xml from template (env vars → real values)
#   5. Hand off to the original Openfire entrypoint
# ─────────────────────────────────────────────────────────────────────────────
set -euo pipefail

# ── DEFAULTS (all overridable via k8s ConfigMap / Secret) ────────────────────
MYSQL_HOST="${MYSQL_HOST:-mysql}"
MYSQL_PORT="${MYSQL_PORT:-3306}"
MYSQL_DATABASE="${MYSQL_DATABASE:-openfire}"
MYSQL_USER="${MYSQL_USER:-openfire}"
MYSQL_PASSWORD="${MYSQL_PASSWORD:-openfire}"
XMPP_DOMAIN="${XMPP_DOMAIN:-example.com}"
XMPP_FQDN="${XMPP_FQDN:-xmpp.example.com}"
REST_API_SECRET="${REST_API_SECRET:-changeme}"
REST_API_ALLOWED_IPS="${REST_API_ALLOWED_IPS:-}"
LOG_DEBUG="${LOG_DEBUG:-false}"

# Official image puts data here; conf/plugins are symlinked here at runtime
OPENFIRE_DIR="/usr/local/openfire"
DATA_DIR="/var/lib/openfire"
CONF_DIR="${DATA_DIR}/conf"
MARKER_DIR="${DATA_DIR}/.init"

# ── MYSQL HELPER ─────────────────────────────────────────────────────────────
mysql_exec() {
  mysql -h"${MYSQL_HOST}" -P"${MYSQL_PORT}" \
        -u"${MYSQL_USER}" -p"${MYSQL_PASSWORD}" \
        --connect-timeout=5 \
        "$@"
}

# ── 1. WAIT FOR MYSQL ─────────────────────────────────────────────────────────
echo "[init] Waiting for MySQL at ${MYSQL_HOST}:${MYSQL_PORT} ..."
until bash -c ">/dev/tcp/${MYSQL_HOST}/${MYSQL_PORT}" 2>/dev/null; do
  sleep 3
done
echo "[init] MySQL is reachable."
sleep 2   # let MySQL finish any init scripts

# ── 2. APPLY SCHEMA (first boot only) ────────────────────────────────────────
mkdir -p "${MARKER_DIR}"

if [ ! -f "${MARKER_DIR}/.schema_done" ]; then
  echo "[init] Checking Openfire schema ..."
  TABLE_COUNT=$(mysql_exec \
    -e "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='${MYSQL_DATABASE}';" \
    --skip-column-names 2>/dev/null || echo "0")

  if [ "${TABLE_COUNT:-0}" -lt 5 ]; then
    SCHEMA="${OPENFIRE_DIR}/resources/database/openfire_mysql.sql"
    if [ -f "${SCHEMA}" ]; then
      echo "[init] Applying schema from ${SCHEMA} ..."
      mysql_exec "${MYSQL_DATABASE}" < "${SCHEMA}"
      echo "[init] Schema applied."
    else
      echo "[init] WARNING: schema file not found at ${SCHEMA}"
    fi
  else
    echo "[init] Schema already present (${TABLE_COUNT} tables). Skipping."
  fi
  touch "${MARKER_DIR}/.schema_done"
fi

# ── 3. SEED DEFAULT PROPERTIES (first boot only) ─────────────────────────────
if [ ! -f "${MARKER_DIR}/.props_done" ]; then
  echo "[init] Seeding default ofProperty rows ..."
  mysql_exec "${MYSQL_DATABASE}" <<EOSQL
-- ── Core ──────────────────────────────────────────────────────────────────
INSERT IGNORE INTO ofProperty (name, propValue) VALUES
  ('xmpp.domain',                        '${XMPP_DOMAIN}'),
  ('xmpp.fqdn',                          '${XMPP_FQDN}'),
  ('setup',                              'true'),

-- ── Admin console ─────────────────────────────────────────────────────────
  ('adminConsole.port',                  '9090'),
  ('adminConsole.securePort',            '9091'),

-- ── REST API plugin ────────────────────────────────────────────────────────
  ('plugin.restapi.httpAuth',            'secret'),
  ('plugin.restapi.secret',              '${REST_API_SECRET}'),
  ('plugin.restapi.allowedIPs',          '${REST_API_ALLOWED_IPS}'),
  ('plugin.restapi.enabled',             'true'),

-- ── Monitoring plugin ──────────────────────────────────────────────────────
  ('monitoring.statType',                'full'),
  ('conversation.metadataArchiving',     'true'),
  ('conversation.messageArchiving',      'true'),

-- ── Multi-User Chat (conference.DOMAIN) ───────────────────────────────────
  ('muc.service.name',                   'conference'),
  ('muc.enabled',                        'true'),
  ('muc.tasks.user.timeout',             '300000'),

-- ── Publish-Subscribe (pubsub.DOMAIN) ─────────────────────────────────────
  ('pubsub.service.name',                'pubsub'),
  ('pubsub.enabled',                     'true'),

-- ── User Search (search.DOMAIN) ───────────────────────────────────────────
  ('search.serviceName',                 'search'),
  ('search.enabled',                     'true'),

-- ── Misc ──────────────────────────────────────────────────────────────────
  ('locale',                             'en'),
  ('log.debug.enabled',                  '${LOG_DEBUG}');
EOSQL

  echo "[init] Default properties seeded."
  touch "${MARKER_DIR}/.props_done"
fi

# ── 4. WRITE openfire.xml FROM TEMPLATE ──────────────────────────────────────
# The official entrypoint expects openfire.xml to exist at
# /var/lib/openfire/conf/openfire.xml (it symlinks there from the app dir).
mkdir -p "${CONF_DIR}"

TMPL="/etc/openfire-tmpl/openfire.xml.tmpl"
if [ -f "${TMPL}" ]; then
  echo "[init] Rendering openfire.xml ..."
  # envsubst replaces ${VAR} with real env values
  envsubst < "${TMPL}" > "${CONF_DIR}/openfire.xml"
fi

# ── 5. HAND OFF TO ORIGINAL ENTRYPOINT ───────────────────────────────────────
# The base image ships its entrypoint at /sbin/entrypoint.sh
echo "[init] Starting Openfire ..."
exec /sbin/entrypoint.sh
