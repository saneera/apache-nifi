# Minimal runner for deploy.sh — only needed if you don't already have an image with bash, curl and jq.
# The script itself is NOT baked in: it comes from the nifi-deploy-script ConfigMap, so script changes
# never need a new image.
#
#   docker build -t registry.local/hfc2/nifi-deploy-runner:1.0.0 image/
#   docker push   registry.local/hfc2/nifi-deploy-runner:1.0.0
#
# On an air-gapped network, base this on your approved internal base image instead of alpine.
FROM alpine:3.20

# bash, curl, jq: used throughout the script. coreutils: sha256sum/date/seq (busybox has them too,
# coreutils keeps behaviour identical to a standard Linux box).
RUN apk add --no-cache bash curl jq coreutils ca-certificates \
 && adduser -D -H -u 10001 deploy

USER 10001
WORKDIR /tmp
