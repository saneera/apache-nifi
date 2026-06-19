#!/bin/bash
# ─────────────────────────────────────────────────────────────────────────────
# Run this ONCE on the Openfire pod to patch AuthCheckFilter excludes.
# This is the most reliable fix for the 302 redirect issue.
#
# Usage:
#   kubectl cp patch-web-xml.sh openfire/<pod>:/tmp/patch-web-xml.sh -n openfire
#   kubectl exec -it <pod> -n openfire -- bash /tmp/patch-web-xml.sh
# ─────────────────────────────────────────────────────────────────────────────

# Find web.xml
WEBXML=$(find /usr/local/openfire -name "web.xml" 2>/dev/null | head -1)

if [ -z "${WEBXML}" ]; then
    echo "ERROR: web.xml not found"
    exit 1
fi

echo "Found web.xml at: ${WEBXML}"
echo ""
echo "--- Current excludes ---"
grep -A2 "excludes" "${WEBXML}" | head -10
echo ""

if grep -q "muc-rest-api" "${WEBXML}"; then
    echo "muc-rest-api already in excludes — nothing to do."
    exit 0
fi

# Backup
cp "${WEBXML}" "${WEBXML}.bak"
echo "Backup created: ${WEBXML}.bak"

# Add our plugin to the excludes param-value
# The existing value starts with "login.jsp,..."
sed -i 's|<param-name>excludes</param-name>|<param-name>excludes</param-name>|' "${WEBXML}"
sed -i '/param-name.*excludes/{n; s|<param-value>|<param-value>plugins/muc-rest-api/*,|}' "${WEBXML}"

echo ""
echo "--- Updated excludes ---"
grep -A2 "excludes" "${WEBXML}" | head -10

echo ""
echo "Done. Restart Openfire for changes to take effect:"
echo "  kubectl rollout restart deployment/openfire -n openfire"
