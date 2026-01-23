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
