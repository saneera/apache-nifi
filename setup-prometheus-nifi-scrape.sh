#!/usr/bin/env bash
# =============================================================================
# setup-prometheus-nifi-scrape.sh
#
# Sets up Prometheus (Prometheus Operator) scraping of NiFi 2.x
# /nifi-api/flow/metrics/prometheus using a CLIENT CERTIFICATE (mTLS, no token).
#
# Uses the EXISTING NiFi-Shared-Root-CA (root-ca.crt / root-ca.key) created by the
# NiFi keystore script, so NiFi already trusts the new certificate - no NiFi
# keystore/truststore change and no NiFi restart.
#
# Steps:
#   1. Check tools
#   2. Check root CA files (and that they signed the NiFi cert currently served)
#   3. Create Prometheus client cert (CN=prometheus, OU=monitoring), signed by the root CA
#   4. Test the NiFi metrics endpoint with the new cert (curl)
#   5. Detect Service labels / port name / Prometheus selector (if kubectl can reach the cluster)
#   6. Write Secret + ServiceMonitor YAML for RED and BLACK
#   7. Optionally kubectl apply (--apply)
#   8. Print the manual NiFi UI steps (user + policy)
#
# Works in Git Bash (MINGW64), Linux and macOS.
#
# Usage:
#   ./setup-prometheus-nifi-scrape.sh                 # generate files only
#   ./setup-prometheus-nifi-scrape.sh --apply         # generate + kubectl apply
#   ./setup-prometheus-nifi-scrape.sh --sides red     # only RED
#   ./setup-prometheus-nifi-scrape.sh --force         # recreate client cert even if it exists
#
# Any setting below can be overridden by environment variable, e.g.
#   RED_CONTEXT=red-cluster BLACK_CONTEXT=black-cluster ./setup-prometheus-nifi-scrape.sh --apply
# =============================================================================
set -euo pipefail
export MSYS_NO_PATHCONV=1          # stop Git Bash rewriting "/CN=..." style arguments into Windows paths

# ============================== CONFIGURATION ================================
CA_DIR="${CA_DIR:-.}"                              # folder containing root-ca.crt and root-ca.key
CA_CERT="${CA_CERT:-$CA_DIR/root-ca.crt}"
CA_KEY="${CA_KEY:-$CA_DIR/root-ca.key}"

OUT_DIR="${OUT_DIR:-./prometheus-scrape}"          # all generated files go here (DO NOT COMMIT)

NIFI_NS="${NIFI_NS:-inspire-silrelease}"           # namespace of the NiFi Service (both clusters)
NIFI_PORT="${NIFI_PORT:-8443}"
METRICS_PATH="${METRICS_PATH:-/nifi-api/flow/metrics/prometheus}"
SCRAPE_INTERVAL="${SCRAPE_INTERVAL:-30s}"
SECRET_NAME="${SECRET_NAME:-nifi-scrape-tls}"

CLIENT_NAME="${CLIENT_NAME:-prometheus}"           # file prefix
CLIENT_CN="${CLIENT_CN:-prometheus}"
CLIENT_OU="${CLIENT_OU:-monitoring}"
CLIENT_DAYS="${CLIENT_DAYS:-825}"

# --- RED ---
RED_CONTEXT="${RED_CONTEXT:-}"                     # kubectl context for RED cluster ('' = current)
RED_SERVICE="${RED_SERVICE:-nifi-red}"
RED_SERVER_NAME="${RED_SERVER_NAME:-nifi-red.${NIFI_NS}.svc.cluster.local}"   # must be a SAN in NiFi's cert
RED_TEST_URL="${RED_TEST_URL:-https://10.11.253.93:30084}"                     # reachable from THIS machine ('' = skip)

# --- BLACK ---
BLACK_CONTEXT="${BLACK_CONTEXT:-}"
BLACK_SERVICE="${BLACK_SERVICE:-nifi-black}"
BLACK_SERVER_NAME="${BLACK_SERVER_NAME:-nifi-black.${NIFI_NS}.svc.cluster.local}"
BLACK_TEST_URL="${BLACK_TEST_URL:-}"

# --- Fallbacks if auto-detection via kubectl is not possible ---
# Label your Prometheus uses to pick ServiceMonitors (key=value), e.g. release=kube-prometheus-stack
PROM_SELECTOR_LABEL="${PROM_SELECTOR_LABEL:-}"
# Port name of 8443 in the NiFi Service, e.g. https
SERVICE_PORT_NAME="${SERVICE_PORT_NAME:-}"
# Labels on the NiFi Service, comma separated, e.g. "app=nifi-red". '' = auto-detect
RED_SERVICE_LABELS="${RED_SERVICE_LABELS:-}"
BLACK_SERVICE_LABELS="${BLACK_SERVICE_LABELS:-}"
# =============================================================================

APPLY=false
FORCE=false
SIDES="red,black"

while [[ $# -gt 0 ]]; do
  case "$1" in
    --apply) APPLY=true ;;
    --force) FORCE=true ;;
    --sides) SIDES="$2"; shift ;;
    -h|--help) sed -n '2,35p' "$0"; exit 0 ;;
    *) echo "Unknown option: $1"; exit 1 ;;
  esac
  shift
done

# ------------------------------- helpers -------------------------------------
c_ok="\033[32m"; c_warn="\033[33m"; c_err="\033[31m"; c_hd="\033[1;36m"; c_off="\033[0m"
step() { echo -e "\n${c_hd}==> $*${c_off}"; }
ok()   { echo -e "  ${c_ok}✔${c_off} $*"; }
warn() { echo -e "  ${c_warn}!${c_off} $*"; WARNINGS+=("$*"); }
die()  { echo -e "  ${c_err}✘ $*${c_off}"; exit 1; }
WARNINGS=()

b64() { base64 < "$1" | tr -d '\n\r'; }          # one-line base64, works on GNU / Git Bash / macOS

kc() {  # kc <context> <kubectl args...>
  local ctx="$1"; shift
  if [[ -n "$ctx" ]]; then kubectl --context "$ctx" "$@"; else kubectl "$@"; fi
}

side_var() { local v="${1^^}_$2"; echo "${!v}"; }   # side_var red SERVICE -> $RED_SERVICE

mkdir -p "$OUT_DIR"
CERT_DIR="$OUT_DIR/certs"
mkdir -p "$CERT_DIR"
CLIENT_KEY="$CERT_DIR/$CLIENT_NAME.key"
CLIENT_CSR="$CERT_DIR/$CLIENT_NAME.csr"
CLIENT_CRT="$CERT_DIR/$CLIENT_NAME.crt"
CLIENT_CNF="$CERT_DIR/$CLIENT_NAME-client.conf"

# ============================ 1. TOOLS ========================================
step "1. Checking tools"
for t in openssl base64; do command -v "$t" >/dev/null || die "$t not found"; ok "$t"; done
HAVE_CURL=false;   command -v curl    >/dev/null && HAVE_CURL=true   && ok "curl"    || warn "curl not found - endpoint test skipped"
HAVE_KUBECTL=false; command -v kubectl >/dev/null && HAVE_KUBECTL=true && ok "kubectl" || warn "kubectl not found - no auto-detect / apply"
$APPLY && ! $HAVE_KUBECTL && die "--apply needs kubectl"

# ============================ 2. ROOT CA ======================================
step "2. Checking root CA"
[[ -f "$CA_CERT" ]] || die "CA cert not found: $CA_CERT (set CA_DIR=...)"
[[ -f "$CA_KEY"  ]] || die "CA key not found: $CA_KEY - you cannot sign without the original root-ca.key. Do NOT regenerate the CA; NiFi trusts the existing one."
ok "CA cert : $(openssl x509 -in "$CA_CERT" -noout -subject)"
ok "CA valid: until $(openssl x509 -in "$CA_CERT" -noout -enddate | cut -d= -f2)"

# key belongs to cert?
ca_crt_mod=$(openssl x509 -in "$CA_CERT" -noout -pubkey | openssl md5)
ca_key_mod=$(openssl pkey -in "$CA_KEY" -pubout 2>/dev/null | openssl md5)
[[ "$ca_crt_mod" == "$ca_key_mod" ]] || die "root-ca.key does not match root-ca.crt"
ok "root-ca.key matches root-ca.crt"

# CA really signed what NiFi is serving now?
for side in ${SIDES//,/ }; do
  url=$(side_var "$side" TEST_URL)
  [[ -z "$url" ]] && { warn "${side^^}: no TEST_URL - skipped check that NiFi uses this CA"; continue; }
  hostport="${url#https://}"; hostport="${hostport%%/*}"
  srv_crt="$CERT_DIR/${side}-served.crt"
  if openssl s_client -connect "$hostport" -showcerts </dev/null 2>/dev/null \
       | awk '/-----BEGIN CERTIFICATE-----/{f=1} f{print} /-----END CERTIFICATE-----/{exit}' > "$srv_crt" \
     && [[ -s "$srv_crt" ]]; then
    if openssl verify -CAfile "$CA_CERT" "$srv_crt" >/dev/null 2>&1; then
      ok "${side^^}: NiFi cert at $hostport is signed by this CA"
      sans=$(openssl x509 -in "$srv_crt" -noout -ext subjectAltName 2>/dev/null | tail -n +2 | tr -d ' ')
      ok "${side^^}: SANs: $sans"
      srvname=$(side_var "$side" SERVER_NAME)
      [[ "$sans" == *"DNS:$srvname"* ]] || warn "${side^^}: serverName '$srvname' is NOT in NiFi's SANs - set ${side^^}_SERVER_NAME to one of: $sans"
    else
      die "${side^^}: NiFi cert at $hostport is NOT signed by $CA_CERT - wrong CA folder?"
    fi
  else
    warn "${side^^}: could not connect to $hostport - CA match not checked"
  fi
done

# ============================ 3. CLIENT CERT ==================================
step "3. Prometheus client certificate"
if [[ -f "$CLIENT_CRT" && -f "$CLIENT_KEY" && "$FORCE" != true ]]; then
  ok "Exists, reusing (use --force to recreate): $CLIENT_CRT"
else
  cat > "$CLIENT_CNF" <<EOF
[req]
distinguished_name = req_distinguished_name
req_extensions     = v3_req
prompt             = no
[req_distinguished_name]
# OU first, CN last -> NiFi shows identity as "CN=prometheus, OU=monitoring"
OU = ${CLIENT_OU}
CN = ${CLIENT_CN}
[v3_req]
basicConstraints = CA:FALSE
keyUsage         = digitalSignature, keyEncipherment
extendedKeyUsage = clientAuth
EOF
  openssl req -new -nodes -newkey rsa:2048 -keyout "$CLIENT_KEY" -out "$CLIENT_CSR" -config "$CLIENT_CNF" 2>/dev/null
  openssl x509 -req -in "$CLIENT_CSR" -CA "$CA_CERT" -CAkey "$CA_KEY" -CAcreateserial \
    -CAserial "$CERT_DIR/root-ca.srl" -out "$CLIENT_CRT" -days "$CLIENT_DAYS" -sha256 \
    -extensions v3_req -extfile "$CLIENT_CNF" 2>/dev/null
  chmod 600 "$CLIENT_KEY" 2>/dev/null || true
  ok "Created $CLIENT_CRT"
fi
openssl verify -CAfile "$CA_CERT" "$CLIENT_CRT" >/dev/null || die "Client cert does not verify against CA"
SUBJECT=$(openssl x509 -in "$CLIENT_CRT" -noout -subject -nameopt RFC2253 | sed 's/^subject=//')
SUBJECT_NIFI=$(openssl x509 -in "$CLIENT_CRT" -noout -subject -nameopt RFC2253,sep_comma_plus_space | sed 's/^subject=//')
ok "Verified against CA"
ok "Subject : $SUBJECT"
ok "Expires : $(openssl x509 -in "$CLIENT_CRT" -noout -enddate | cut -d= -f2)"
openssl x509 -in "$CLIENT_CRT" -noout -ext extendedKeyUsage 2>/dev/null | grep -q "Client Authentication" \
  && ok "extendedKeyUsage: clientAuth" || die "clientAuth missing from cert"

# ============================ 4. ENDPOINT TEST ================================
step "4. Testing NiFi metrics endpoint with the client cert"
declare -A TEST_RESULT
for side in ${SIDES//,/ }; do
  url=$(side_var "$side" TEST_URL)
  if [[ -z "$url" ]] || ! $HAVE_CURL; then TEST_RESULT[$side]="not tested"; warn "${side^^}: endpoint test skipped"; continue; fi
  code=$(curl -s -o "$CERT_DIR/${side}-metrics.txt" -w '%{http_code}' --max-time 20 \
           --cacert "$CA_CERT" --cert "$CLIENT_CRT" --key "$CLIENT_KEY" \
           ${SKIP_HOST_CHECK:+-k} "$url$METRICS_PATH" 2>/dev/null || true)
  case "$code" in
    200) n=$(grep -c '^nifi_' "$CERT_DIR/${side}-metrics.txt" || true)
         ok "${side^^}: HTTP 200 - $n nifi_ metric lines"; TEST_RESULT[$side]="OK (200)"
         grep -v '^#' "$CERT_DIR/${side}-metrics.txt" | cut -d'{' -f1 | cut -d' ' -f1 | sort -u > "$OUT_DIR/${side}-metric-names.txt"
         ok "${side^^}: metric names saved to $OUT_DIR/${side}-metric-names.txt" ;;
    401|403) warn "${side^^}: HTTP $code - cert accepted by TLS but NiFi user/policy missing (see step 8)"; TEST_RESULT[$side]="needs NiFi user ($code)" ;;
    000) warn "${side^^}: no HTTP response - TLS/hostname problem or unreachable. If the cert is valid only for DNS names, retry with SKIP_HOST_CHECK=1"; TEST_RESULT[$side]="no response" ;;
    *)   warn "${side^^}: HTTP $code"; TEST_RESULT[$side]="HTTP $code" ;;
  esac
done

# ============================ 5 + 6. YAML =====================================
step "5/6. Detecting cluster settings and writing YAML"

for side in ${SIDES//,/ }; do
  ctx=$(side_var "$side" CONTEXT)
  svc=$(side_var "$side" SERVICE)
  srvname=$(side_var "$side" SERVER_NAME)
  labels=$(side_var "$side" SERVICE_LABELS)
  portname="$SERVICE_PORT_NAME"
  promsel="$PROM_SELECTOR_LABEL"
  dir="$OUT_DIR/$side"; mkdir -p "$dir"

  reachable=false
  if $HAVE_KUBECTL && kc "$ctx" -n "$NIFI_NS" get svc "$svc" >/dev/null 2>&1; then
    reachable=true
    ok "${side^^}: found Service $NIFI_NS/$svc${ctx:+ (context $ctx)}"
    if [[ -z "$labels" ]]; then
      labels=$(kc "$ctx" -n "$NIFI_NS" get svc "$svc" \
        -o go-template='{{range $k,$v := .metadata.labels}}{{$k}}={{$v}},{{end}}' | sed 's/,$//')
      ok "${side^^}: Service labels: ${labels:-<none>}"
    fi
    if [[ -z "$portname" ]]; then
      portname=$(kc "$ctx" -n "$NIFI_NS" get svc "$svc" -o jsonpath="{.spec.ports[?(@.port==$NIFI_PORT)].name}")
      [[ -z "$portname" ]] && portname=$(kc "$ctx" -n "$NIFI_NS" get svc "$svc" -o jsonpath="{.spec.ports[?(@.targetPort==$NIFI_PORT)].name}")
      ok "${side^^}: port name for $NIFI_PORT: ${portname:-<none>}"
    fi
    if [[ -z "$promsel" ]]; then
      promsel=$(kc "$ctx" get prometheus -A -o jsonpath='{.items[0].spec.serviceMonitorSelector.matchLabels}' 2>/dev/null \
                | tr -d '{}"' | cut -d, -f1 | sed 's/:/=/')
      [[ -n "$promsel" ]] && ok "${side^^}: Prometheus serviceMonitorSelector: $promsel" \
                          || ok "${side^^}: Prometheus selects ALL ServiceMonitors (or none found)"
      nssel=$(kc "$ctx" get prometheus -A -o jsonpath='{.items[0].spec.serviceMonitorNamespaceSelector}' 2>/dev/null || true)
      [[ -n "$nssel" && "$nssel" != "{}" ]] && warn "${side^^}: Prometheus serviceMonitorNamespaceSelector=$nssel - make sure namespace $NIFI_NS matches"
    fi
  else
    warn "${side^^}: cannot reach Service $NIFI_NS/$svc via kubectl${ctx:+ (context $ctx)} - using configured values"
  fi

  [[ -z "$labels"   ]] && { labels="app=$svc"; warn "${side^^}: Service labels unknown - using '$labels' (CHECK nifi-servicemonitor.yaml)"; }
  [[ -z "$portname" ]] && { portname="https";  warn "${side^^}: port name unknown - using '$portname' (CHECK nifi-servicemonitor.yaml)"; }

  # matchLabels block
  match=""
  IFS=',' read -ra kvs <<< "$labels"
  for kv in "${kvs[@]}"; do match+="      ${kv%%=*}: \"${kv#*=}\""$'\n'; done
  sm_labels=""
  [[ -n "$promsel" ]] && sm_labels="    ${promsel%%=*}: \"${promsel#*=}\""$'\n'

  # ---- Secret ----
  cat > "$dir/nifi-scrape-tls-secret.yaml" <<EOF
# Client certificate for Prometheus -> NiFi ($side). CONTAINS A PRIVATE KEY - DO NOT COMMIT.
apiVersion: v1
kind: Secret
metadata:
  name: ${SECRET_NAME}
  namespace: ${NIFI_NS}
  labels:
    app.kubernetes.io/part-of: nifi-monitoring
type: Opaque
data:
  ca.crt: $(b64 "$CA_CERT")
  tls.crt: $(b64 "$CLIENT_CRT")
  tls.key: $(b64 "$CLIENT_KEY")
EOF

  # ---- ServiceMonitor ----
  cat > "$dir/nifi-servicemonitor.yaml" <<EOF
apiVersion: monitoring.coreos.com/v1
kind: ServiceMonitor
metadata:
  name: nifi-${side}
  namespace: ${NIFI_NS}
  labels:
    app.kubernetes.io/part-of: nifi-monitoring
${sm_labels}spec:
  namespaceSelector:
    matchNames: [${NIFI_NS}]
  selector:
    matchLabels:
${match}  endpoints:
    - port: ${portname}
      scheme: https
      path: ${METRICS_PATH}
      interval: ${SCRAPE_INTERVAL}
      scrapeTimeout: 20s
      tlsConfig:
        ca:
          secret: { name: ${SECRET_NAME}, key: ca.crt }
        cert:
          secret: { name: ${SECRET_NAME}, key: tls.crt }
        keySecret: { name: ${SECRET_NAME}, key: tls.key }
        serverName: ${srvname}
      relabelings:
        - targetLabel: nifi_instance
          replacement: nifi-${side}
        - targetLabel: domain
          replacement: ${side}
EOF
  ok "${side^^}: wrote $dir/nifi-scrape-tls-secret.yaml"
  ok "${side^^}: wrote $dir/nifi-servicemonitor.yaml"

  # ============================ 7. APPLY =======================================
  if $APPLY; then
    $reachable || die "${side^^}: --apply requested but cluster not reachable${ctx:+ (context $ctx)}"
    kc "$ctx" apply -f "$dir/nifi-scrape-tls-secret.yaml"
    kc "$ctx" apply -f "$dir/nifi-servicemonitor.yaml"
    ok "${side^^}: applied"
  fi
done

# keep secrets out of git
cat > "$OUT_DIR/.gitignore" <<'EOF'
# Generated by setup-prometheus-nifi-scrape.sh - contains private keys
*
!.gitignore
EOF

# ============================ 8. SUMMARY =====================================
step "8. Remaining MANUAL steps in NiFi UI (on each of: ${SIDES^^})"
cat <<EOF
  a) ☰ -> Users -> + (Add user)
       Identity: ${SUBJECT_NIFI}
       (if nifi.properties has nifi.security.identity.mapping.*, use the mapped value, often: ${CLIENT_CN})
  b) ☰ -> Policies -> "view the user interface" -> add that user
  c) Re-run without --force to re-test:  ./$(basename "$0")
     -> step 4 should show HTTP 200

  Then: Prometheus UI -> Status -> Targets -> nifi-red / nifi-black should be UP
EOF

step "Result"
for side in ${SIDES//,/ }; do echo "  ${side^^}: endpoint test = ${TEST_RESULT[$side]:-n/a}"; done
echo "  Files      : $OUT_DIR/"
echo "  Applied    : $APPLY"
if (( ${#WARNINGS[@]} )); then
  echo -e "\n${c_warn}Warnings to review:${c_off}"
  for w in "${WARNINGS[@]}"; do echo "  - $w"; done
fi
echo -e "\n${c_warn}Do NOT commit $OUT_DIR (private key inside). A .gitignore was written there.${c_off}"
