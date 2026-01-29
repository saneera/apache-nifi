DNS:nifi-black-0
DNS:nifi-black-0.nifi-black-headless
DNS:nifi-black-0.nifi-black-headless.nifi-black.svc
DNS:nifi-black-0.nifi-black-headless.nifi-black.svc.cluster.local
DNS:nifi-black.nifi-black.svc
DNS:nifi-black.nifi-black.svc.cluster.local


keytool -genkeypair \
  -alias nifi \
  -keyalg RSA \
  -keysize 2048 \
  -storetype PKCS12 \
  -keystore keystore.p12 \
  -storepass changeit \
  -keypass changeit \
  -dname "CN=nifi-black-0.nifi-black-headless.nifi-black.svc.cluster.local" \
  -ext "SAN=DNS:nifi-black-0,DNS:nifi-black-0.nifi-black-headless,DNS:nifi-black-0.nifi-black-headless.nifi-black.svc,DNS:nifi-black-0.nifi-black-headless.nifi-black.svc.cluster.local,DNS:nifi-black.nifi-black.svc,DNS:nifi-black.nifi-black.svc.cluster.local" \
  -validity 3650


  keytool -certreq \
    -alias nifi \
    -keystore keystore.p12 \
    -storepass changeit \
    -file nifi.csr


    openssl x509 -req \
      -in nifi.csr \
      -CA ca.crt \
      -CAkey ca.key \
      -CAcreateserial \
      -out nifi.crt \
      -days 3650 \
      -sha256 \
      -copy_extensions copy


      # Import CA first
      keytool -importcert -noprompt \
        -alias nifi-ca \
        -file ca.crt \
        -keystore keystore.p12 \
        -storepass changeit

      # Import signed cert
      keytool -importcert -noprompt \
        -alias nifi \
        -file nifi.crt \
        -keystore keystore.p12 \
        -storepass changeit


keytool -importcert -noprompt \
  -alias nifi-cluster-ca \
  -file ca.crt \
  -keystore truststore.p12 \
  -storetype PKCS12 \
  -storepass changeit


  keytool -list -v \
    -keystore keystore.p12 \
    -storetype PKCS12 \
    -storepass changeit \
    -alias nifi | grep -A5 -i "SubjectAlternativeName"




    openssl s_client \
      -connect nifi-red.nifi-red.svc.cluster.local:8443 \
      -CAfile /opt/nifi/tls/ca.crt





      openssl s_client \
        -connect nifi-black.nifi-black.svc.cluster.local:8443 \
        -CAfile /opt/nifi/tls/ca.crt \
        -verify_return_error \
        -servername nifi-black.nifi-black.svc.cluster.local

        Verify return code: 0 (ok)


        curl -vk \
          --cacert /opt/nifi/tls/ca.crt \
          https://nifi-black.nifi-black.svc.cluster.local:8443/nifi-api/site-to-site


          {
            "controller": {
              "remoteSiteListeningPort": 10000,
              "siteToSiteSecure": true
            }
          }




================google


Saneera Yapa <saneera@gmail.com>
10:12 PM (1 hour ago)
to me

#!/bin/bash

# Configuration
PASS="changeit"
NS_A="namespace-a"
NS_B="namespace-b"
FQDN_A="nifi-a.$NS_A.svc.cluster.local"
FQDN_B="nifi-b.$NS_B.svc.cluster.local"

echo "Creating PKCS12 Keystore for NiFi A..."
keytool -genkeypair -alias nifi-a -keyalg RSA -keysize 2048 -validity 365 \
-keystore keystore-a.p12 -storetype PKCS12 -storepass $PASS -keypass $PASS \
-dname "CN=$FQDN_A, OU=NIFI" \
-ext san=dns:$FQDN_A,dns:nifi-a

echo "Creating PKCS12 Keystore for NiFi B..."
keytool -genkeypair -alias nifi-b -keyalg RSA -keysize 2048 -validity 365 \
-keystore keystore-b.p12 -storetype PKCS12 -storepass $PASS -keypass $PASS \
-dname "CN=$FQDN_B, OU=NIFI" \
-ext san=dns:$FQDN_B,dns:nifi-b

echo "Exporting Public Certificates..."
keytool -exportcert -alias nifi-a -file nifi-a.cer -keystore keystore-a.p12 -storepass $PASS
keytool -exportcert -alias nifi-b -file nifi-b.cer -keystore keystore-b.p12 -storepass $PASS

echo "Building PKCS12 Truststores..."
# Truststore A (imports B's public cert)
keytool -importcert -trustcacerts -noprompt -alias nifi-b -file nifi-b.cer \
-keystore truststore-a.p12 -storetype PKCS12 -storepass $PASS
# Truststore B (imports A's public cert)
keytool -importcert -trustcacerts -noprompt -alias nifi-a -file nifi-a.cer \
-keystore truststore-b.p12 -storetype PKCS12 -storepass $PASS

echo "Done! PKCS12 stores ready for K8s deployment."


Important NiFi Property Changes
When using PKCS12, you must update your nifi.properties to specify the different store type:
nifi.security.keystoreType=PKCS12
nifi.security.truststoreType=PKCS12
nifi.security.keystore=./conf/keystore-a.p12
nifi.security.truststore=./conf/truststore-a.p12
Verification
You can verify the content of these PKCS12 files using the DigiCert Certificate Inspector or simply run keytool -list -keystore keystore-a.p12 -storetype PKCS12.
Does your NiFi Helm Chart or Deployment YAML currently expect files ending in .jks, or can I provide the volumeMount configuration for these .p12files?



