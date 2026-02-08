#!/bin/bash

# Configuration
NIFI_A_IP="172.27.3.23"
NIFI_B_IP="172.27.3.24"
PASS="password123456"
ADMIN_DN="CN=admin, OU=NIFI"

# 1. Clean and Create Workspace
mkdir -p nifi-certs-out
rm -rf nifi-certs-out/*

# 2. Generate JKS with SAN
# The -n flag sets the Common Name (CN).
# The --subjectAlternativeNames flag adds the IPs to the SAN field.
./nifi-toolkit-1.28.1/bin/tls-toolkit.sh standalone \
  -n "$NIFI_A_IP, $NIFI_B_IP" \
  --subjectAlternativeNames "$NIFI_A_IP, $NIFI_B_IP, localhost" \
  -C "$ADMIN_DN" \
  -o ./nifi-certs-out \
  -S "$PASS" -P "$PASS" -K "$PASS" \
  --nifiDnPrefix "CN=" --nifiDnSuffix ", OU=NIFI"

# 3. Convert NiFi A to PKCS12
echo "Converting NiFi A..."
keytool -importkeystore -srckeystore "./nifi-certs-out/$NIFI_A_IP/keystore.jks" \
  -destkeystore "./nifi-certs-out/$NIFI_A_IP/keystore.p12" -srcstoretype JKS -deststoretype PKCS12 \
  -srcstorepass "$PASS" -deststorepass "$PASS" -srckeypass "$PASS" -destkeypass "$PASS" -noprompt

keytool -importkeystore -srckeystore "./nifi-certs-out/$NIFI_A_IP/truststore.jks" \
  -destkeystore "./nifi-certs-out/$NIFI_A_IP/truststore.p12" -srcstoretype JKS -deststoretype PKCS12 \
  -srcstorepass "$PASS" -deststorepass "$PASS" -noprompt

# 4. Convert NiFi B to PKCS12
echo "Converting NiFi B..."
keytool -importkeystore -srckeystore "./nifi-certs-out/$NIFI_B_IP/keystore.jks" \
  -destkeystore "./nifi-certs-out/$NIFI_B_IP/keystore.p12" -srcstoretype JKS -deststoretype PKCS12 \
  -srcstorepass "$PASS" -deststorepass "$PASS" -srckeypass "$PASS" -destkeypass "$PASS" -noprompt

keytool -importkeystore -srckeystore "./nifi-certs-out/$NIFI_B_IP/truststore.jks" \
  -destkeystore "./nifi-certs-out/$NIFI_B_IP/truststore.p12" -srcstoretype JKS -deststoretype PKCS12 \
  -srcstorepass "$PASS" -deststorepass "$PASS" -noprompt

echo "DONE! Check ./nifi-certs-out"


keytool -list -v \
  -keystore /opt/nifi/nifi-current/conf/keystore.p12 \
  -storetype PKCS12 \
  -storepass <keystore-password> | grep -A10 "SubjectAlternativeName"


  openssl s_client -connect 172.27.3.12:9443 -servername 172.27.3.12


apiVersion: networking.istio.io/v1beta1
kind: ServiceEntry
metadata:
  name: nifi-black-nodeport
  namespace: <red-nifi-namespace>
spec:
  hosts:
  - nifi-black.external
  location: MESH_EXTERNAL
  resolution: STATIC
  ports:
  - number: 30074
    name: tls-nifi
    protocol: TLS
  endpoints:
  - address: 172.27.3.12


  ----

  apiVersion: networking.istio.io/v1beta1
  kind: DestinationRule
  metadata:
    name: nifi-black-nodeport-dr
    namespace: <red-nifi-namespace>
  spec:
    host: nifi-black.external
    trafficPolicy:
      tls:
        mode: DISABLE
