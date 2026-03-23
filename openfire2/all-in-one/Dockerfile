FROM ghcr.io/igniterealtime/openfire@sha256:d17733fa763d0a3390bfaaacc169f91e80f34f39d7958911eb9704314614d86c

USER root

# Fix directories FIRST
RUN mkdir -p /var/lib/openfire/plugins \
    && mkdir -p /var/log/openfire \
    && chown -R openfire:openfire /var/lib/openfire \
    && chown -R openfire:openfire /var/log/openfire

# Install plugins
RUN curl -L -o /var/lib/openfire/plugins/restAPI.jar \
    https://github.com/igniterealtime/openfire-restAPI-plugin/releases/latest/download/restAPI.jar

RUN curl -L -o /var/lib/openfire/plugins/monitoring.jar \
    https://github.com/igniterealtime/openfire-monitoring-plugin/releases/latest/download/monitoring.jar

RUN curl -L -o /var/lib/openfire/plugins/httpfileupload.jar \
    https://github.com/igniterealtime/openfire-httpFileUpload-plugin/releases/latest/download/httpfileupload.jar

# Ensure ownership
RUN chown -R openfire:openfire /var/lib/openfire

USER openfire
