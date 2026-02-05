cd nifi-certs
./bin/tls-toolkit.sh standalone \
  -n '172.27.3.23' \
  --subjectAlternativeNames '172.27.3.23,localhost,nifi-a,nifi-b' \
  -C 'CN=admin, OU=NIFI' \
  -o ./out \
  -S 'password123456' \
  -P 'password123456' \
  -K 'password123456' \
  --nifiDnPrefix 'CN=' \
  --nifiDnSuffix ', OU=NIFI'


Convert the Keystore

keytool -importkeystore \
  -srckeystore ./out/172.27.3.23/keystore.jks \
  -destkeystore ./out/172.27.3.23/keystore.p12 \
  -srcstoretype JKS \
  -deststoretype PKCS12 \
  -srcstorepass password123456 \
  -deststorepass password123456 \
  -srckeypass password123456 \
  -destkeypass password123456


Convert the Truststore

keytool -importkeystore \
  -srckeystore ./out/172.27.3.23/truststore.jks \
  -destkeystore ./out/172.27.3.23/truststore.p12 \
  -srcstoretype JKS \
  -deststoretype PKCS12 \
  -srcstorepass password123456 \
  -deststorepass password123456



=========

#!/bin/bash

# Configuration
NIFI_A_IP="172.27.3.23"
NIFI_B_IP="172.27.3.24"
PASS="password123456"
ADMIN_DN="CN=admin, OU=NIFI"

# 1. Clean and Create Workspace
mkdir -p nifi-certs-out
rm -rf nifi-certs-out/*

# 2. Generate JKS Certificates for both IPs using the same CA
# The toolkit automatically creates a shared CA when run in 'standalone' mode for multiple nodes
./bin/tls-toolkit.sh standalone \
  -n "$NIFI_A_IP, $NIFI_B_IP" \
  -C "$ADMIN_DN" \
  -o ./nifi-certs-out \
  -S "$PASS" -P "$PASS" -K "$PASS" \
  --nifiDnPrefix "CN=" --nifiDnSuffix ", OU=NIFI"

# 3. Convert JKS to PKCS12 for NiFi A
echo "Converting NiFi A to PKCS12..."
keytool -importkeystore -srckeystore "./nifi-certs-out/$NIFI_A_IP/keystore.jks" \
  -destkeystore "./nifi-certs-out/$NIFI_A_IP/keystore.p12" -srcstoretype JKS -deststoretype PKCS12 \
  -srcstorepass "$PASS" -deststorepass "$PASS" -srckeypass "$PASS" -destkeypass "$PASS" -noprompt

keytool -importkeystore -srckeystore "./nifi-certs-out/$NIFI_A_IP/truststore.jks" \
  -destkeystore "./nifi-certs-out/$NIFI_A_IP/truststore.p12" -srcstoretype JKS -deststoretype PKCS12 \
  -srcstorepass "$PASS" -deststorepass "$PASS" -noprompt

# 4. Convert JKS to PKCS12 for NiFi B
echo "Converting NiFi B to PKCS12..."
keytool -importkeystore -srckeystore "./nifi-certs-out/$NIFI_B_IP/keystore.jks" \
  -destkeystore "./nifi-certs-out/$NIFI_B_IP/keystore.p12" -srcstoretype JKS -deststoretype PKCS12 \
  -srcstorepass "$PASS" -deststorepass "$PASS" -srckeypass "$PASS" -destkeypass "$PASS" -noprompt

keytool -importkeystore -srckeystore "./nifi-certs-out/$NIFI_B_IP/truststore.jks" \
  -destkeystore "./nifi-certs-out/$NIFI_B_IP/truststore.p12" -srcstoretype JKS -deststoretype PKCS12 \
  -srcstorepass "$PASS" -deststorepass "$PASS" -noprompt

echo "------------------------------------------------"
echo "DONE! Files are in ./nifi-certs-out"
echo "NiFi A Identity: CN=$NIFI_A_IP, OU=NIFI"
echo "NiFi B Identity: CN=$NIFI_B_IP, OU=NIFI"
