FROM ghcr.io/igniterealtime/openfire:v5.0.4

USER root

# ── Existing plugins ──────────────────────────────────────────────────────────
# COPY plugins/restAPI.jar     /usr/local/openfire/plugins_org/restAPI.jar
# COPY plugins/monitoring.jar  /usr/local/openfire/plugins_org/monitoring.jar

# ── Your custom plugin ────────────────────────────────────────────────────────
COPY plugins/message-delete.jar  /usr/local/openfire/plugins_org/message-delete.jar

RUN chown openfire:openfire \
        # /usr/local/openfire/plugins_org/restAPI.jar \
        # /usr/local/openfire/plugins_org/monitoring.jar \
        /usr/local/openfire/plugins_org/message-delete.jar

COPY scripts/entrypoint.sh /sbin/entrypoint-custom.sh
RUN chmod 755 /sbin/entrypoint-custom.sh

# COPY config/openfire.xml.tmpl /etc/openfire-tmpl/openfire.xml.tmpl
# RUN chown -R openfire:openfire /etc/openfire-tmpl

USER openfire

ENTRYPOINT ["/sbin/entrypoint-custom.sh"]
