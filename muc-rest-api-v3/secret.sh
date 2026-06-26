#!/usr/bin/env bash
set -euo pipefail

# Configuration
PASS="${NIFI_KEYSTORE_PASS:?'Set NIFI_KEYSTORE_PASS'}"
NS_A="inspire-silrelease"
NS_B="inspire-silrelease"
NODEPORT_RED_HOST="172.27.3.23"
NODEPORT_BLACK_HOST="172.27.3.12"
HEADLESS_SERVICE_BLACK="nifi-black-headless"
HEADLESS_SERVICE_RED="nifi-red-headless"

FQDN_A="nifi-red-0.${HEADLESS_SERVICE_RED}.${NS_A}.svc.cluster.local"
SERVICE_FQDN_A="nifi-red.${NS_A}.svc.cluster.local"
FQDN_B="nifi-black-0.${HEADLESS_SERVICE_BLACK}.${NS_B}.svc.cluster.local"
SERVICE_FQDN_B="nifi-black.${NS_B}.svc.cluster.local"

SAN_DNS_RED="SAN=dns:nifi-red-0,dns:${NS_A}-0,dns:${FQDN_A},dns:${SERVICE_FQDN_A},ip:${NODEPORT_RED_HOST}"
SAN_DNS_BLACK="SAN=dns:nifi-black-0,dns:${FQDN_B},dns:${SERVICE_FQDN_B},ip:${NODEPORT_BLACK_HOST}"

generate_keystore() {
  local alias=$1 keystore=$2 dname=$3 san=$4
  keytool -genkeypair -alias "$alias" \
    -keyalg RSA -keysize 2048 -validity 3650 \
    -keystore "$keystore" -storetype PKCS12 \
    -storepass "$PASS" -keypass "$PASS" \
    -dname "$dname" -ext "$san"
}

export_cert() {
  local alias=$1 file=$2 keystore=$3
  keytool -exportcert -alias "$alias" -file "$file" \
    -keystore "$keystore" -storepass "$PASS"
}

import_truststore() {
  local alias=$1 cert=$2 truststore=$3
  keytool -importcert -trustcacerts -noprompt \
    -alias "$alias" -file "$cert" \
    -keystore "$truststore" -storetype PKCS12 -storepass "$PASS"
}

# Cleanup
rm -f keystore-a.p12 keystore-b.p12 truststore-a.p12 truststore-b.p12 nifi-a.cer nifi-b.cer

echo "Creating PKCS12 Keystores..."
generate_keystore "nifi-a" "keystore-a.p12" "CN=${SERVICE_FQDN_A},OU=NIFI" "$SAN_DNS_RED"
generate_keystore "nifi-b" "keystore-b.p12" "CN=${SERVICE_FQDN_B},OU=NIFI" "$SAN_DNS_BLACK"

echo "Exporting Public Certificates..."
export_cert "nifi-a" "nifi-a.cer" "keystore-a.p12"
export_cert "nifi-b" "nifi-b.cer" "keystore-b.p12"

echo "Building PKCS12 Truststores..."
import_truststore "nifi-b" "nifi-b.cer" "truststore-a.p12"
import_truststore "nifi-a" "nifi-a.cer" "truststore-b.p12"

echo "Done! PKCS12 stores ready for K8s deployment."
