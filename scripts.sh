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
