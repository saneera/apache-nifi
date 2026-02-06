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


openssl s_client -connect 172.27.3.12:30074 -servername 172.27.3.12


apiVersion: v1
kind: Service
metadata:
  name: nifi-black-s2s
  namespace: nifi-black
spec:
  type: NodePort
  selector:
    app: nifi-black
  ports:
    - name: s2s-raw
      port: 10000
      targetPort: 10000
      nodePort: 31000   # pick a free nodeport
      protocol: TCP



apiVersion: networking.istio.io/v1beta1
kind: VirtualService
metadata:
  name: nifi-black-9443
  namespace: nifi-black
spec:
  gateways:
  - istio-system/public-gateway
  tcp:
  - match:
    - port: 9443
    route:
    - destination:
        host: nifi-black.nifi-black.svc.cluster.local
        port:
          number: 9443
