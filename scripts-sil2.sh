#!/bin/bash
# =============================================================================
# NiFi RED / BLACK + Prometheus certificate generation
#
# Output:
#   certs/
#     root-ca/      root-ca.key, root-ca.crt          (Shared Root CA - KEEP SAFE)
#     red/          red.key/.csr/.crt, red-keystore.p12, red-truststore.p12, *.b64
#     black/        black.key/.csr/.crt, black-keystore.p12, black-truststore.p12, *.b64
#     prometheus/   prometheus.key/.csr/.crt, nifi-scrape-tls.yaml
#
# The root CA is created ONCE and reused on later runs, so re-running the script
# does NOT break the certificates already deployed. Use --new-ca to force a new CA
# (then ALL keystores/truststores must be redeployed).
# =============================================================================
export MSYS_NO_PATHCONV=1      # Git Bash: stop "/CN=..." being converted to a Windows path
set -euo pipefail              # stop at the first error

# ----------------------------- Configuration ---------------------------------
PASS="${PASS:-th1s1s3up34e5r37}"   # better: export PASS=... before running, keep it out of git
NAMESPACE="inspire-silrelease"
CERTS_DIR="certs"

# RED Cluster Info
RED_IP="172.27.3.23"
RED_DNS="nifi-red.inspire-silrelease.svc.cluster.local"
RED_DNS2="nifi-red-0.nifi-red-headless.inspire-silrelease.svc.cluster.local"

# BLACK Cluster Info
BLACK_IP="172.27.3.12"
BLACK_DNS="nifi-black.inspire-silrelease.svc.cluster.local"
BLACK_DNS2="nifi-black-0.nifi-black-headless.inspire-silrelease.svc.cluster.local"

CA_DIR="$CERTS_DIR/root-ca"
CA_KEY="$CA_DIR/root-ca.key"
CA_CRT="$CA_DIR/root-ca.crt"
CA_SRL="$CA_DIR/root-ca.srl"

NEW_CA=false
[[ "${1:-}" == "--new-ca" ]] && NEW_CA=true

mkdir -p "$CA_DIR"

# keep keys and passwords out of git
cat > "$CERTS_DIR/.gitignore" <<'EOF'
# Generated certificates and keys - do not commit
*
!.gitignore
EOF

# ------------------------- 1. Shared Root CA ---------------------------------
if [[ -f "$CA_KEY" && -f "$CA_CRT" && "$NEW_CA" != true ]]; then
    echo "1. Using existing Shared Root CA: $CA_CRT"
else
    echo "1. Generating Shared Root CA (The Trust Source)..."
    openssl req -new -x509 -keyout "$CA_KEY" -out "$CA_CRT" -days 3650 -nodes \
        -subj "/CN=NiFi-Shared-Root-CA"
fi
openssl x509 -in "$CA_CRT" -noout -subject -enddate

# ------------------------- 2. NiFi RED / BLACK -------------------------------
generate_nifi_p12() {
    local NAME=$1
    local IP=$2
    local DNS=$3
    local DNS2=$4
    local DIR="$CERTS_DIR/$NAME"
    mkdir -p "$DIR"

    echo "2. Creating PKCS12 for $NAME ($DNS)..."

    # Create OpenSSL config to force SAN (IP and DNS)
    cat > "$DIR/${NAME}-san.conf" <<EOF
[req]
distinguished_name = req_distinguished_name
req_extensions = v3_req
prompt = no
[req_distinguished_name]
CN = ${DNS}
[v3_req]
subjectAltName = IP:${IP},DNS:${DNS},DNS:${DNS2}
keyUsage = digitalSignature, keyEncipherment
extendedKeyUsage = serverAuth, clientAuth
EOF

    # Generate Private Key and CSR
    openssl req -new -nodes -newkey rsa:2048 -keyout "$DIR/${NAME}.key" -out "$DIR/${NAME}.csr" \
        -config "$DIR/${NAME}-san.conf"

    # Sign with Shared Root CA
    openssl x509 -req -in "$DIR/${NAME}.csr" -CA "$CA_CRT" -CAkey "$CA_KEY" \
        -CAcreateserial -CAserial "$CA_SRL" \
        -out "$DIR/${NAME}.crt" -days 825 -sha256 -extensions v3_req -extfile "$DIR/${NAME}-san.conf"

    # Create Keystore (PKCS12)
    openssl pkcs12 -export -in "$DIR/${NAME}.crt" -inkey "$DIR/${NAME}.key" -certfile "$CA_CRT" \
        -out "$DIR/${NAME}-keystore.p12" -name "nifi-key" -passout "pass:${PASS}"

    # Create Truststore (PKCS12) - contains the Shared Root CA
    rm -f "$DIR/${NAME}-truststore.p12"
    keytool -importcert -alias "nifi-root" -file "$CA_CRT" -keystore "$DIR/${NAME}-truststore.p12" \
        -storetype PKCS12 -storepass "${PASS}" -noprompt

    # Base64 strings for the Kubernetes Secrets (instead of pasting them into this script)
    base64 -w0 "$DIR/${NAME}-keystore.p12"   > "$DIR/${NAME}-keystore.b64"
    base64 -w0 "$DIR/${NAME}-truststore.p12" > "$DIR/${NAME}-truststore.b64"

    openssl verify -CAfile "$CA_CRT" "$DIR/${NAME}.crt"
}

generate_nifi_p12 "red"   "$RED_IP"   "$RED_DNS"   "$RED_DNS2"
generate_nifi_p12 "black" "$BLACK_IP" "$BLACK_DNS" "$BLACK_DNS2"

# ------------------------- 3. Prometheus client cert -------------------------
PROM_DIR="$CERTS_DIR/prometheus"
mkdir -p "$PROM_DIR"
echo "3. Creating Prometheus client cert..."

cat > "$PROM_DIR/prometheus-san.conf" <<EOF
[req]
distinguished_name = req_distinguished_name
req_extensions = v3_req
prompt = no
[req_distinguished_name]
OU = monitoring
CN = prometheus
[v3_req]
keyUsage = digitalSignature, keyEncipherment
extendedKeyUsage = clientAuth
EOF

openssl req -new -nodes -newkey rsa:2048 -keyout "$PROM_DIR/prometheus.key" -out "$PROM_DIR/prometheus.csr" \
    -config "$PROM_DIR/prometheus-san.conf"
openssl x509 -req -in "$PROM_DIR/prometheus.csr" -CA "$CA_CRT" -CAkey "$CA_KEY" \
    -CAcreateserial -CAserial "$CA_SRL" \
    -out "$PROM_DIR/prometheus.crt" -days 825 -sha256 -extensions v3_req -extfile "$PROM_DIR/prometheus-san.conf"
openssl verify -CAfile "$CA_CRT" "$PROM_DIR/prometheus.crt"

# Secret used by the Prometheus ServiceMonitor (same file for RED and BLACK clusters)
cat > "$PROM_DIR/nifi-scrape-tls.yaml" <<EOF
apiVersion: v1
kind: Secret
metadata:
  name: nifi-scrape-tls
  namespace: ${NAMESPACE}
type: Opaque
data:
  ca.crt: $(base64 -w0 "$CA_CRT")
  tls.crt: $(base64 -w0 "$PROM_DIR/prometheus.crt")
  tls.key: $(base64 -w0 "$PROM_DIR/prometheus.key")
EOF

# ------------------------------- Summary --------------------------------------
echo "-------------------------------------------------"
echo "DONE! Your files are ready:"
echo "ROOT CA   : $CA_DIR/root-ca.crt  (root-ca.key - keep safe, needed for future certs)"
echo "RED       : $CERTS_DIR/red/red-keystore.p12, red-truststore.p12   (+ .b64 for the Secret)"
echo "BLACK     : $CERTS_DIR/black/black-keystore.p12, black-truststore.p12 (+ .b64 for the Secret)"
echo "PROMETHEUS: $PROM_DIR/prometheus.crt, prometheus.key, nifi-scrape-tls.yaml"
echo
echo "NiFi user for Prometheus: CN=prometheus, OU=monitoring  (policy: view the user interface)"
