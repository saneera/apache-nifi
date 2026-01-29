#!/usr/bin/env bash
set -euo pipefail

########################################
# Configuration
########################################

NIFI_HOME=${NIFI_HOME:-/opt/nifi/nifi-current}
TLS_DIR="${NIFI_HOME}/keytool"

CA_KEY="${TLS_DIR}/ca.key"
CA_CERT="${TLS_DIR}/ca.crt"
CA_PASS="${NIFI_SENSITIVE_PROPS_KEY}"

KEYSTORE="${TLS_DIR}/keystore.p12"
TRUSTSTORE="${TLS_DIR}/truststore.p12"

STORE_PASS="${NIFI_SENSITIVE_PROPS_KEY}"
KEY_PASS="${NIFI_SENSITIVE_PROPS_KEY}"

NODE_DNS="${POD_NAME}.nifi.${POD_NAMESPACE}.svc.cluster.local"
SERVICE_DNS="nifi.${POD_NAMESPACE}.svc.cluster.local"

########################################
# Create CA (ONCE)
########################################

if [[ ! -f "${CA_CERT}" ]]; then
  echo "Creating NiFi Cluster CA"

  openssl genrsa -out "${CA_KEY}" 4096

  openssl req -x509 -new -nodes \
    -key "${CA_KEY}" \
    -sha256 \
    -days 3650 \
    -out "${CA_CERT}" \
    -subj "/CN=NiFi-Cluster-CA"
fi

########################################
# Create node keystore (per pod)
########################################

if [[ ! -f "${KEYSTORE}" ]]; then
  echo "Creating keystore for ${NODE_DNS}"

  # Generate keypair
  keytool -genkeypair \
    -alias nifi-node \
    -keyalg RSA \
    -keysize 2048 \
    -keystore "${KEYSTORE}" \
    -storetype PKCS12 \
    -storepass "${STORE_PASS}" \
    -keypass "${KEY_PASS}" \
    -dname "CN=${NODE_DNS}" \
    -ext "SAN=dns:${NODE_DNS},dns:${SERVICE_DNS},dns:localhost,ip:${POD_IP}"

  # Create CSR
  keytool -certreq \
    -alias nifi-node \
    -keystore "${KEYSTORE}" \
    -storepass "${STORE_PASS}" \
    -file "${TLS_DIR}/node.csr"

  # Sign CSR with CA
  openssl x509 -req \
    -in "${TLS_DIR}/node.csr" \
    -CA "${CA_CERT}" \
    -CAkey "${CA_KEY}" \
    -CAcreateserial \
    -out "${TLS_DIR}/node.crt" \
    -days 3650 \
    -sha256 \
    -extensions v3_req \
    -extfile <(cat <<EOF
[v3_req]
subjectAltName=DNS:${NODE_DNS},DNS:${SERVICE_DNS},DNS:localhost,IP:${POD_IP}
EOF
)

  # Import CA cert
  keytool -importcert -noprompt \
    -alias nifi-ca \
    -file "${CA_CERT}" \
    -keystore "${KEYSTORE}" \
    -storepass "${STORE_PASS}"

  # Import signed node cert
  keytool -importcert \
    -alias nifi-node \
    -file "${TLS_DIR}/node.crt" \
    -keystore "${KEYSTORE}" \
    -storepass "${STORE_PASS}"

  rm -f "${TLS_DIR}/node.csr" "${TLS_DIR}/node.crt"
fi

########################################
# Create truststore (shared)
########################################

if [[ ! -f "${TRUSTSTORE}" ]]; then
  echo "Creating truststore"

  keytool -importcert -noprompt \
    -alias nifi-ca \
    -file "${CA_CERT}" \
    -keystore "${TRUSTSTORE}" \
    -storetype PKCS12 \
    -storepass "${STORE_PASS}"
fi

########################################
# Final permissions
########################################

chown -R nifi:nifi "${TLS_DIR}"
chmod 600 "${KEYSTORE}" "${TRUSTSTORE}"

echo "TLS material ready for ${NODE_DNS}"



---

apiVersion: storage.k8s.io/v1
kind: StorageClass
metadata:
  name: local-path
  annotations:
    storageclass.kubernetes.io/is-default-class: "true"
provisioner: rancher.io/local-path
volumeBindingMode: WaitForFirstConsumer
reclaimPolicy: Delete




mkdir nifi-ca && cd nifi-ca

openssl genrsa -out ca.key 4096

openssl req -x509 -new -nodes \
  -key ca.key \
  -sha256 \
  -days 3650 \
  -out ca.crt \
  -subj "/CN=NiFi-Cluster-CA"

keytool -importcert -noprompt \
  -alias nifi-ca \
  -file ca.crt \
  -keystore truststore.p12 \
  -storetype PKCS12 \
  -storepass changeit



  kubectl create secret generic nifi-truststore-secret \
    --from-file=ca.crt=ca.crt \
    --from-file=truststore.p12=truststore.p12 \
    -n nifi \
    --dry-run=client -o yaml > nifi-truststore-secret.yaml


# Ensure TLS dir is writable for the NiFi user (UID/GID 1000)
# (matches your pod securityContext runAsUser/runAsGroup/fsGroup)
echo "Fixing permissions on ${TLS_DIR}"
chown -R 1000:1000 "${TLS_DIR}" 2>/dev/null || true
chmod -R 770 "${TLS_DIR}" 2>/dev/null || true


initContainers:
  - name: init-tls
    image: nifi-image
    securityContext:
      runAsUser: 0
      runAsGroup: 0
    command: ["/bin/sh","-c","/scripts/security.sh"]
    volumeMounts:
      - name: scripts
        mountPath: /scripts/security.sh
        subPath: security.sh
      - name: nifi-conf
        mountPath: /opt/nifi/nifi-current/tls


kubectl exec -n dev-1 nifi-0 -c nifi -- sh -c 'ls -l /opt/nifi/tls; sha256sum /opt/nifi/tls/keystore.p12 /opt/nifi/tls/truststore.p12 2>/dev/null || true'
kubectl exec -n dev-1 nifi-1 -c nifi -- sh -c 'ls -l /opt/nifi/tls; sha256sum /opt/nifi/tls/keystore.p12 /opt/nifi/tls/truststore.p12 2>/dev/null || true'



kubectl exec -n dev-1 nifi-0 -c nifi -- sh -c 'grep -E "nifi.security.(keystore|truststore)" -n /opt/nifi/nifi-current/conf/nifi.properties'
kubectl exec -n dev-1 nifi-1 -c nifi -- sh -c 'grep -E "nifi.security.(keystore|truststore)" -n /opt/nifi/nifi-current/conf/nifi.properties'

kubectl exec -n dev-1 nifi-0 -c nifi -- sh -c 'grep -E "nifi.cluster.protocol.is.secure|nifi.cluster.node.protocol.port" -n /opt/nifi/nifi-current/conf/nifi.properties'
kubectl exec -n dev-1 nifi-1 -c nifi -- sh -c 'grep -E "nifi.cluster.protocol.is.secure|nifi.cluster.node.protocol.port" -n /opt/nifi/nifi-current/conf/nifi.properties'


kubectl exec -n dev-1 nifi-0 -c nifi -- sh -c '
keytool -list -v -keystore /opt/nifi/tls/keystore.p12 -storetype PKCS12 -storepass "${KEYSTORE_PASSWORD:-changeit}" | grep -E "Owner:|SubjectAlternativeName" -A2
'



grep -E "nifi.web.proxy" /opt/nifi/nifi-current/conf/nifi.properties

keytool -list -v \
  -keystore /opt/nifi/tls/keystore.p12 \
  -storetype PKCS12 \
  -storepass changeit | grep -A2 "SubjectAlternativeName"


  SAN="dns:${POD_NAME}.${HEADLESS_SERVICE}.${NAMESPACE}.svc.cluster.local"
  SAN="${SAN},dns:${POD_NAME}"

  # Explicitly add both nodes (StatefulSet)
  SAN="${SAN},dns:nifi-0.${HEADLESS_SERVICE}.${NAMESPACE}.svc.cluster.local"
  SAN="${SAN},dns:nifi-1.${HEADLESS_SERVICE}.${NAMESPACE}.svc.cluster.local"
  SAN="${SAN},dns:nifi-0"
  SAN="${SAN},dns:nifi-1"


- name: nifi-data
  mountPath: /opt/nifi/nifi-current/flowfile_repository
- name: nifi-data
  mountPath: /opt/nifi/nifi-current/content_repository
- name: nifi-data
  mountPath: /opt/nifi/nifi-current/provenance_repository
- name: nifi-data
  mountPath: /opt/nifi/nifi-current/database_repository
- name: nifi-data
  mountPath: /opt/nifi/nifi-current/state
- name: nifi-data
  mountPath: /opt/nifi/nifi-current/conf



  curl -i -X POST "http://NODE_IP:30075/bom/service1" \
    -H "Content-Type: application/json" \
    -d '{"test":"service1"}'



    # Generate keypair
    keytool -genkeypair \
      -alias nifi \
      -keystore keystore.p12 \
      -storetype PKCS12 \
      -storepass "$PASSWORD" \
      -keypass "$PASSWORD" \
      -dname "CN=${HOSTNAME_FQDN}" \
      -ext "SAN=${SAN}"

    # Create CSR
    keytool -certreq \
      -alias nifi \
      -keystore keystore.p12 \
      -storepass "$PASSWORD" \
      -file nifi.csr

    # Sign CSR using CA
    openssl x509 -req \
      -in nifi.csr \
      -CA ca.crt \
      -CAkey ca.key \
      -CAcreateserial \
      -out nifi.crt \
      -days 3650 -sha256

    # Import CA into truststore
    keytool -importcert -noprompt \
      -alias nifi-ca \
      -file ca.crt \
      -keystore truststore.p12 \
      -storetype PKCS12 \
      -storepass "$PASSWORD"

    # Import signed cert back
    keytool -importcert -noprompt \
      -alias nifi \
      -file nifi.crt \
      -keystore keystore.p12 \
      -storepass "$PASSWORD"





      cat > make-keystore.sh <<'EOF'
      #!/usr/bin/env bash
      set -euo pipefail

      POD_FQDN="${1:?pod fqdn required}"
      POD_SHORT="${2:?pod short name required}"
      SERVICE_FQDN="${3:?service fqdn required}"
      PASSWORD="${4:?password required}"
      CA_CRT="${5:-ca.crt}"
      CA_KEY="${6:-ca.key}"

      OUT_DIR="out/${POD_SHORT}"
      mkdir -p "${OUT_DIR}"

      KEYSTORE="${OUT_DIR}/keystore.p12"
      CSR="${OUT_DIR}/nifi.csr"
      CRT="${OUT_DIR}/nifi.crt"
      P12CHAIN="${OUT_DIR}/nifi-fullchain.p12"

      SAN="DNS:${POD_SHORT},DNS:${POD_FQDN},DNS:${SERVICE_FQDN}"

      echo "==> Generating keystore for ${POD_SHORT}"
      echo "    CN  : ${POD_FQDN}"
      echo "    SAN : ${SAN}"

      # 1) Generate keypair (private key stays in keystore.p12)
      keytool -genkeypair \
        -alias nifi \
        -keyalg RSA \
        -keysize 2048 \
        -storetype PKCS12 \
        -keystore "${KEYSTORE}" \
        -storepass "${PASSWORD}" \
        -keypass "${PASSWORD}" \
        -dname "CN=${POD_FQDN}" \
        -ext "SAN=${SAN}" \
        -validity 3650

      # 2) Create CSR
      keytool -certreq \
        -alias nifi \
        -keystore "${KEYSTORE}" \
        -storepass "${PASSWORD}" \
        -file "${CSR}"

      # 3) Sign CSR with CA (requires ca.key locally)
      openssl x509 -req \
        -in "${CSR}" \
        -CA "${CA_CRT}" \
        -CAkey "${CA_KEY}" \
        -CAcreateserial \
        -out "${CRT}" \
        -days 3650 \
        -sha256

      # 4) Import CA into keystore
      keytool -importcert -noprompt \
        -alias nifi-ca \
        -file "${CA_CRT}" \
        -keystore "${KEYSTORE}" \
        -storetype PKCS12 \
        -storepass "${PASSWORD}"

      # 5) Import signed cert back into keystore
      keytool -importcert -noprompt \
        -alias nifi \
        -file "${CRT}" \
        -keystore "${KEYSTORE}" \
        -storetype PKCS12 \
        -storepass "${PASSWORD}"

      echo "==> Done: ${KEYSTORE}"
      echo "==> Verify SAN:"
      keytool -list -v -keystore "${KEYSTORE}" -storetype PKCS12 -storepass "${PASSWORD}" | grep -A3 -i "SubjectAlternativeName" || true
      EOF

      chmod +x make-keystore.sh

      BLACK_NS="nifi-black"
      BLACK_HEADLESS="nifi-black-headless"
      BLACK_SERVICE="nifi-black"
      BLACK_POD0="nifi-black-0"

      ./make-keystore.sh \
        "${BLACK_POD0}.${BLACK_HEADLESS}.${BLACK_NS}.svc.cluster.local" \
        "${BLACK_POD0}" \
        "${BLACK_SERVICE}.${BLACK_NS}.svc.cluster.local" \
        "$PASSWORD"

        RED_NS="nifi-red"
        RED_HEADLESS="nifi-red-headless"
        RED_SERVICE="nifi-red"
        RED_POD0="nifi-red-0"

        ./make-keystore.sh \
          "${RED_POD0}.${RED_HEADLESS}.${RED_NS}.svc.cluster.local" \
          "${RED_POD0}" \
          "${RED_SERVICE}.${RED_NS}.svc.cluster.local" \
          "$PASSWORD"


          cat > make-secret-yaml.sh <<'EOF'
          #!/usr/bin/env bash
          set -euo pipefail

          NAME="${1:?secret name}"
          NAMESPACE="${2:?namespace}"
          KEYSTORE_PATH="${3:?keystore.p12 path}"
          TRUSTSTORE_PATH="${4:?truststore.p12 path}"
          CA_CRT_PATH="${5:-ca.crt}"

          b64() { base64 -w0 "$1"; }

          cat <<YAML
          apiVersion: v1
          kind: Secret
          metadata:
            name: ${NAME}
            namespace: ${NAMESPACE}
          type: Opaque
          data:
            keystore.p12: $(b64 "${KEYSTORE_PATH}")
            truststore.p12: $(b64 "${TRUSTSTORE_PATH}")
            ca.crt: $(b64 "${CA_CRT_PATH}")
          YAML
          EOF

          chmod +x make-secret-yaml.sh


./make-secret-yaml.sh nifi-black-0-tls nifi-black out/nifi-black-0/keystore.p12 truststore.p12 ca.crt > nifi-black-0-tls-secret.yaml]]

./make-secret-yaml.sh nifi-red-0-tls nifi-red out/nifi-red-0/keystore.p12 truststore.p12 ca.crt > nifi-red-0-tls-secret.yaml


#!/bin/sh
set -e

TLS_DIR=/opt/nifi/certs

echo "Using existing TLS material"
echo "TLS dir: $TLS_DIR"

[ -f "$TLS_DIR/keystore.p12" ] || { echo "Missing keystore"; exit 1; }
[ -f "$TLS_DIR/truststore.p12" ] || { echo "Missing truststore"; exit 1; }

chmod 600 $TLS_DIR/*.p12 || true


===========

kubectl get pod -n nifi-black nifi-black-0 -o jsonpath='{.status.containerStatuses[*].name}{"\n"}{.status.containerStatuses[*].restartCount}{"\n"}
kubectl logs -n nifi-black nifi-black-0 -c nifi-black --previous --tail=200

kubectl logs -n nifi-black nifi-black-0 -c nifi-init-tls --tail=200

kubectl exec -n nifi-black -it nifi-black-0 -c nifi-black -- ls -l /opt/nifi/tls

kubectl exec -n nifi-black -it nifi-black-0 -c nifi-black -- \
  sh -c 'tail -200 /opt/nifi/nifi-current/logs/nifi-app.log; echo "----"; tail -200 /opt/nifi/nifi-current/logs/bootstrap.log'

  kubectl exec -n nifi-black -it nifi-black-0 -c nifi-black -- \
    grep -E "keystore|truststore" /opt/nifi/nifi-current/conf/nifi.properties



    kubectl logs -n nifi-black nifi-black-0 -c nifi-black --previous --tail=50

    kubectl describe pod -n nifi-black nifi-black-0 | sed -n '/Events:/,$p'




kubectl exec -n nifi-black -it nifi-black-0 -- bash -lc \
'keytool -list -keystore /opt/nifi/tls/keystore.p12 -storetype PKCS12 -storepass th1s1s3up34e5r3?7 >/dev/null && echo OK'


kubectl exec -n nifi-black -it nifi-black-0 -c nifi-black -- bash -lc '
echo "== TLS files ==";
ls -l /opt/nifi/tls;
echo "== nifi.properties security lines ==";
grep -nE "^nifi.security.(key|trust)store" /opt/nifi/nifi-current/conf/nifi.properties;
'


kubectl exec -n nifi-black -it nifi-black-0 -c nifi-black -- bash -lc '
keytool -list -keystore /opt/nifi/tls/truststore.p12 -storetype PKCS12 -storepass "th1s1s3up34e5r37"



'kubectl exec -n nifi-black -it nifi-black-0 -c nifi-black -- bash -lc '
 keytool -list -v -keystore /opt/nifi/tls/keystore.p12 -storetype PKCS12 -storepass "th1s1s3up34e5r37" | egrep -i "Alias name:|Entry type:|Owner:|Issuer:|SubjectAlternativeName|Valid from" -n
 '

kubectl exec -n nifi-black -it nifi-black-0 -c nifi-black -- bash -lc '
keytool -list -v -keystore /opt/nifi/tls/keystore.p12 -storetype PKCS12 -storepass "th1s1s3up34e5r37" | sed -n "/SubjectAlternativeName/,+8p"
'

kubectl exec -n nifi-black -it nifi-black-0 -c nifi-black -- bash -lc '
id && ls -l /opt/nifi/tls && stat -c "%U %G %a %n" /opt/nifi/tls/* || true
'


keytool -list -v -keystore out/nifi-black-0/keystore.p12 -storetype PKCS12 -storepass "th1s1s3up34e5r37" | grep -A3 -i "Subject Alternative"
