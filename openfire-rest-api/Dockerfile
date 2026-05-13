# ─────────────────────────────────────────────────────────────────────────────
# Base: your existing Openfire 5.0.4 image already in the registry.
# Replace the image reference below with your actual registry path.
#
# The official ghcr.io image installs Openfire under /usr/local/openfire
# and symlinks runtime dirs (conf, plugins, resources/security) to
# /var/lib/openfire at startup via its own entrypoint.
#
# We:
#   1. COPY plugin JARs into /usr/local/openfire/plugins_org/ so they are
#      present before the entrypoint symlink dance runs.
#   2. COPY our custom entrypoint that wraps the original one and seeds
#      MySQL properties on first boot.
#   3. No curl / wget / apt-get — zero network calls at build time.
# ─────────────────────────────────────────────────────────────────────────────
FROM ghcr.io/igniterealtime/openfire:v5.0.4
# ↑ Replace with your internal registry image, e.g.:
#   FROM your.registry.io/openfire:5.0.4

# Switch to root only for file copies; the base image drops back to the
# openfire user in its own entrypoint.
USER root

# ── COPY PLUGIN JARS ─────────────────────────────────────────────────────────
# These files must exist in the build context under plugins/ before you run
# docker build.  NO download happens here.
#
# The official image moves /usr/local/openfire/plugins → plugins_org at build
# time, and the entrypoint symlinks /var/lib/openfire/plugins → plugins_org on
# first boot.  Placing JARs in plugins_org means they are present in the
# live plugins dir immediately when Openfire starts.
COPY plugins/restAPI.jar    /usr/local/openfire/plugins_org/restAPI.jar
COPY plugins/monitoring.jar /usr/local/openfire/plugins_org/monitoring.jar

# Fix ownership so the openfire user can load them
RUN chown openfire:openfire \
        /usr/local/openfire/plugins_org/restAPI.jar \
        /usr/local/openfire/plugins_org/monitoring.jar

# ── COPY CUSTOM ENTRYPOINT WRAPPER ───────────────────────────────────────────
COPY scripts/entrypoint.sh /sbin/entrypoint-custom.sh
RUN chmod 755 /sbin/entrypoint-custom.sh

# ── COPY OPENFIRE.XML TEMPLATE ───────────────────────────────────────────────
COPY config/openfire.xml.tmpl /etc/openfire-tmpl/openfire.xml.tmpl
RUN chown -R openfire:openfire /etc/openfire-tmpl

# Ports already exposed by the base image, but listed here for clarity:
# 5222  XMPP client plain / STARTTLS
# 5223  XMPP client legacy SSL
# 5269  XMPP server-to-server
# 7070  HTTP-BOSH
# 7443  HTTPS-BOSH
# 9090  Admin console / REST API (HTTP)
# 9091  Admin console / REST API (HTTPS)
EXPOSE 5222 5223 5269 7070 7443 9090 9091

USER openfire

ENTRYPOINT ["/sbin/entrypoint-custom.sh"]
