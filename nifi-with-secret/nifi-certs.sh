#!/bin/bash

# --- Configuration ---
NAMESPACE="nifi"
SECRET_NAME="nifi-certs"
KEYSTORE_PASS="nifiKeystorePass"
KEY_PASS="nifiKeyPass"
TRUSTSTORE_PASS="nifiTruststorePass"
KEYSTORE_FILE="keystore.jks"
TRUSTSTORE_FILE="truststore.jks"
CERT_FILE="nifi-cert.cer"
CN_NAME="nifi.local"

# --- 1. Generate keystore with self-signed cert ---
keytool -genkeypair \
  -alias nifi-key \
  -keyalg RSA \
  -keysize 2048 \
  -keystore $KEYSTORE_FILE \
  -storepass $KEYSTORE_PASS \
  -keypass $KEY_PASS \
  -dname "CN=$CN_NAME, OU=Dev, O=MyOrg, L=City, S=State, C=AU"

# --- 2. Export cert from keystore ---
keytool -export \
  -alias nifi-key \
  -file $CERT_FILE \
  -keystore $KEYSTORE_FILE \
  -storepass $KEYSTORE_PASS

# --- 3. Create truststore and import cert ---
keytool -import \
  -alias nifi-key \
  -file $CERT_FILE \
  -keystore $TRUSTSTORE_FILE \
  -storepass $TRUSTSTORE_PASS \
  -noprompt

# --- 4. Create Kubernetes secret ---
kubectl create secret generic $SECRET_NAME \
  --namespace $NAMESPACE \
  --from-file=$KEYSTORE_FILE \
  --from-file=$TRUSTSTORE_FILE \
  --from-literal=keystorePass=$KEYSTORE_PASS \
  --from-literal=keyPass=$KEY_PASS \
  --from-literal=truststorePass=$TRUSTSTORE_PASS

echo "✅ Keystore, Truststore and Kubernetes secret '$SECRET_NAME' created in namespace '$NAMESPACE'."


keytool -list -v \
  -keystore /opt/nifi/certs/keystore.jks \
  -storepass nifiKeystorePass

keytool -list -v \
  -keystore /opt/nifi/certs/truststore.jks \
  -storepass nifiTruststorePass


docker run --rm \
  -v $(pwd):/out \
  apache/nifi-toolkit:1.27.0 \
  tls-toolkit standalone -p /out/tls.properties



  kubectl -n nifi create secret generic nifi-certs \
    --from-file=keystore.jks=keystore.jks \
    --from-literal=keystorePass=nifiKeystorePass \
    --from-literal=keyPass=nifiKeystorePass \
    --from-file=truststore.jks=truststore.jks \
    --from-literal=truststorePass=nifiTruststorePass

