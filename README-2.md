# Openfire TLS with a common CA (Kustomize + Argo CD)

One **HFC2 non-prod CA** is stored in git. On every start, each Openfire's init container creates its own certificate and **signs it with that CA**, using that environment's domain and IP. The chat traffic gateway trusts only the CA, so the same `ca.crt` works in every environment.

- No per-environment certificate files to create or rotate.
- No kubectl, vault or extra cluster tools: everything goes through git, Kustomize and Argo CD.
- A new environment only needs its domain and node IP in its overlay.

> **Non-prod only.** With the CA key in git, anyone with read access to the repo can create certificates the gateway will trust. Use a separate CA, held outside git, for production.

## Contents

1. [How it works](#how-it-works)
2. [Step 1: Create the CA (once)](#step-1-create-the-ca-once)
3. [Step 2: Openfire base](#step-2-openfire-base)
4. [Step 3: Openfire overlays](#step-3-openfire-overlays)
5. [Step 4: Gateway base and overlays](#step-4-gateway-base-and-overlays)
6. [Step 5: Gateway code and local runs](#step-5-gateway-code-and-local-runs)
7. [Step 6: Roll out with Argo](#step-6-roll-out-with-argo)
8. [Step 7: Verify, enforce and capture evidence](#step-7-verify-enforce-and-capture-evidence)
9. [Certificate lifecycle: attach, expiry, renewal](#certificate-lifecycle-attach-expiry-renewal)
10. [Troubleshooting](#troubleshooting)
11. [Adding a new environment](#adding-a-new-environment)

---

## How it works

```
                  HFC2 Non-Prod CA  (ca.p12 + ca.crt in the Openfire repo base)
                          │ signs, inside each pod, on every start
          ┌───────────────┴────────────────┐
          ▼                                ▼
  Openfire dev-1                     Openfire enmf
  CN/SAN openfire-xmpp-dev1          CN/SAN openfire-xmpp-sil3
  IP 10.11.253.93                    IP 172.27.3.23
          ▲                                ▲
          │ TLS (STARTTLS on 5222)         │
  chat-traffic-gateway dev-1         chat-traffic-gateway enmf
  trusts ca.crt                      trusts ca.crt   (same file, gateway repo base)
```

| File | Repo | Location | Used by |
| --- | --- | --- | --- |
| `ca.p12` (CA cert + key) | Openfire | base | Openfire init container, to sign |
| `ca.crt` | Openfire | base | Openfire truststore |
| `ca.crt` | Gateway | `base-manifests` | Gateway trust |
| `ca.key` | none | keep a copy outside git (team share) | Only to rebuild `ca.p12` |

---

## Step 1: Create the CA (once)

In Git Bash, outside both repos:

```bash
mkdir -p /c/hfc2-pki && cd /c/hfc2-pki
export MSYS_NO_PATHCONV=1   # stops Git Bash turning "/C=AU..." into a Windows path

# 1. CA certificate + key (valid 10 years)
openssl req -x509 -newkey rsa:4096 -nodes -sha256 -days 3650 \
  -keyout ca.key -out ca.crt \
  -subj "/C=AU/ST=SA/L=Adelaide/O=Babcock/OU=IT/CN=HFC2 Non-Prod CA" \
  -addext "basicConstraints=critical,CA:TRUE" \
  -addext "keyUsage=critical,keyCertSign,cRLSign"

# 2. Bundle for keytool in the Openfire pod (alias must be hfc2-ca)
openssl pkcs12 -export -in ca.crt -inkey ca.key -name hfc2-ca \
  -out ca.p12 -passout pass:changeit

# 3. Check
openssl x509 -in ca.crt -noout -subject -dates
```

You now have `ca.crt` and `ca.p12` to commit, and `ca.key` to keep safe outside the repos.

If `keytool` in the Openfire image later fails to read `ca.p12` (older Java), rebuild it with `-legacy` added to the `openssl pkcs12` command.

---

## Step 2: Openfire base

### 2a. Add the CA files

Copy `ca.p12` and `ca.crt` into the Openfire base folder and add to the base `kustomization.yaml`:

```yaml
secretGenerator:
  - name: openfire-ca
    files:
      - ca.p12
      - ca.crt
    literals:
      - capass=changeit      # password used for ca.p12 in Step 1
      - storepass=changeit   # Openfire keystore password (Openfire default)
```

Kustomize adds a hash to the Secret name and updates every reference in the Deployment automatically.

### 2b. Replace the `generate-keystore` init container in `openfire-deployment.yaml`

```yaml
        - name: generate-keystore
          image: openfire-image
          command: ["sh", "-c"]
          args:
            - |
              set -e
              SECURITY_DIR="/conf-out/security"
              DEFAULTS_DIR="/usr/local/openfire/resources/security_org"
              KEYSTORE="$SECURITY_DIR/keystore"
              TRUSTSTORE="$SECURITY_DIR/truststore"
              CA_DIR="/ca"
              mkdir -p "$SECURITY_DIR"

              # Keep the existing seeding of default files
              for f in client.truststore java.security; do
                if [ ! -f "$SECURITY_DIR/$f" ] && [ -f "$DEFAULTS_DIR/$f" ]; then
                  cp -f "$DEFAULTS_DIR/$f" "$SECURITY_DIR/$f"
                  chown openfire:openfire "$SECURITY_DIR/$f"
                  echo "Seeded default $f"
                fi
              done

              # Fresh CA-signed certificate on every start
              rm -f "$KEYSTORE" "$TRUSTSTORE" /tmp/openfire.csr /tmp/openfire.crt

              # 1. New key pair for this environment
              keytool -genkeypair -noprompt -alias "$OPENFIRE_FQDN" \
                -keyalg RSA -keysize 2048 -validity 825 \
                -dname "CN=$OPENFIRE_FQDN, OU=IT, O=Babcock, L=Adelaide, ST=SA, C=AU" \
                -keystore "$KEYSTORE" -storetype JKS \
                -storepass "$STOREPASS" -keypass "$STOREPASS"

              # 2. Certificate request
              keytool -certreq -alias "$OPENFIRE_FQDN" \
                -keystore "$KEYSTORE" -storepass "$STOREPASS" -file /tmp/openfire.csr

              # 3. Sign it with the common CA, with this environment's names and IP
              keytool -gencert -rfc -alias hfc2-ca \
                -keystore "$CA_DIR/ca.p12" -storetype PKCS12 -storepass "$CA_PASS" \
                -infile /tmp/openfire.csr -outfile /tmp/openfire.crt -validity 825 \
                -ext "SAN=dns:$OPENFIRE_FQDN,dns:openfire-service,dns:openfire-service.$POD_NAMESPACE.svc.cluster.local,ip:$OPENFIRE_NODE_IP" \
                -ext "KU=digitalSignature,keyEncipherment" \
                -ext "EKU=serverAuth" \
                -ext "BC=ca:false"

              # 4. Install the signed cert (CA first so the chain links up)
              keytool -importcert -noprompt -alias hfc2-ca -file "$CA_DIR/ca.crt" \
                -keystore "$KEYSTORE" -storepass "$STOREPASS"
              keytool -importcert -noprompt -alias "$OPENFIRE_FQDN" -file /tmp/openfire.crt \
                -keystore "$KEYSTORE" -storepass "$STOREPASS"
              keytool -delete -alias hfc2-ca -keystore "$KEYSTORE" -storepass "$STOREPASS"

              # 5. Truststore trusts the CA
              keytool -importcert -noprompt -alias hfc2-ca -file "$CA_DIR/ca.crt" \
                -keystore "$TRUSTSTORE" -storetype JKS -storepass "$STOREPASS"

              chown openfire:openfire "$KEYSTORE" "$TRUSTSTORE"
              rm -f /tmp/openfire.csr /tmp/openfire.crt
              echo "Issued CA-signed certificate for $OPENFIRE_FQDN ($OPENFIRE_NODE_IP)"
          env:
            - name: STOREPASS
              valueFrom:
                secretKeyRef:
                  name: openfire-ca
                  key: storepass
            - name: CA_PASS
              valueFrom:
                secretKeyRef:
                  name: openfire-ca
                  key: capass
            - name: POD_NAMESPACE
              valueFrom:
                fieldRef:
                  fieldPath: metadata.namespace
          envFrom:
            - configMapRef:
                name: openfire-env
          volumeMounts:
            - name: openfire-config
              mountPath: /conf-out
            - name: openfire-ca
              mountPath: /ca
              readOnly: true
```

### 2c. Add the volume

Under the Deployment's `volumes:`, next to `openfire-config`:

```yaml
        - name: openfire-ca
          secret:
            secretName: openfire-ca
```

Only the init container mounts the CA. The Openfire container itself never sees the CA key.

---

## Step 3: Openfire overlays

Each environment only sets its own name and IP in its `openfire-env` ConfigMap:

| Overlay | `OPENFIRE_FQDN` | `OPENFIRE_NODE_IP` |
| --- | --- | --- |
| dev-1 | `openfire-xmpp-dev1` | `10.11.253.93` |
| enmf | `openfire-xmpp-sil3` | `172.27.3.23` |

```yaml
OPENFIRE_FQDN: "openfire-xmpp-dev1"     # must equal the XMPP domain
OPENFIRE_NODE_IP: "10.11.253.93"        # the IP clients connect to
```

Check each overlay before committing:

```bash
kustomize build overlays/dev-1 | grep -nE "openfire-ca|OPENFIRE_FQDN|OPENFIRE_NODE_IP"
```

---

## Step 4: Gateway base and overlays

### 4a. Base

Copy `ca.crt` (**not** `ca.p12`) to `base-manifests/ca.crt` and add to `base-manifests/kustomization.yaml`:

```yaml
configMapGenerator:
  - name: openfire-ca
    files:
      - ca.crt
```

In the base `chat-traffic-gateway` Deployment:

```yaml
spec:
  template:
    spec:
      containers:
        - name: chat-traffic-gateway
          volumeMounts:
            - name: openfire-ca
              mountPath: /etc/openfire-certs
              readOnly: true
      volumes:
        - name: openfire-ca
          configMap:
            name: openfire-ca
```

### 4b. Each overlay

Add two literals to `chat-traffic-gateway-config`:

```yaml
      - chat-gateway.openfire.tls.enabled=true
      - chat-gateway.openfire.tls.trustCert=file:/etc/openfire-certs/ca.crt
```

Also remove any per-overlay `openfire.crt` / `openfire-cert` generator added earlier, and `-Djavax.net.debug=ssl:handshake:verbose` from `jvmOptions` in dev-1.

```bash
kustomize build overlays/enmf | grep -nE "openfire-ca|tls\."
```

---

## Step 5: Gateway code and local runs

No Java changes. `buildTrustManager()` loads every certificate in the `trustCert` file, and trusting a CA works the same as trusting one cert:

```java
builder.setSecurityMode(ConnectionConfiguration.SecurityMode.required)
       .setCustomX509TrustManager(buildTrustManager());
```

> Use `setCustomX509TrustManager`, **not** `setCustomSSLContext`. With Smack 4.4.8 the custom SSLContext was ignored and the JVM default truststore was used, causing `PKIX path building failed`.

Local runs in IntelliJ:

1. Replace `src/main/resources/openfire.crt` with `ca.crt`.
2. In `application.yaml`:
   ```yaml
   chat-gateway:
     openfire:
       tls:
         enabled: true
         trustCert: "classpath:ca.crt"   # quoted, no space after "classpath:"
   ```

The overlays override this with the `file:` path in the cluster.

---

## Step 6: Roll out with Argo

Three merges, in this order, so the gateway never loses its connection:

1. **Gateway, transition trust.** Trust the CA *and* every environment's current self-signed Openfire cert, then merge Step 4:
   ```bash
   cat /c/hfc2-pki/ca.crt <dev-1 openfire.crt> <enmf openfire.crt> > base-manifests/ca.crt
   ```
   After the sync, the gateway still connects to the old certs.
2. **Openfire base + overlays.** Merge Steps 2 and 3 together. Argo restarts Openfire in each environment, which now serves a CA-signed cert. The gateway reconnects using the CA in its bundle.
3. **Gateway, final trust.** Once Step 7 passes everywhere, set `base-manifests/ca.crt` back to the CA alone and merge.

In dev, if a few minutes of downtime is fine, skip the bundle in merge 1 and use `ca.crt` alone.

---

## Step 7: Verify, enforce and capture evidence

Per environment, after merge 2:

- [ ] Init container log ends with `Issued CA-signed certificate for <domain> (<ip>)` (Argo UI → pod logs → `generate-keystore`).
- [ ] Openfire serves the CA-signed cert:
  ```bash
  openssl s_client -connect 10.11.253.93:30522 -starttls xmpp -servername openfire-xmpp-dev1 \
    -CAfile /c/hfc2-pki/ca.crt </dev/null 2>/dev/null | grep "Verify return code"
  # expected: Verify return code: 0 (ok)
  ```
- [ ] Gateway log shows `Connected to Openfire: secure=true, authenticated=true`.
- [ ] A normal flow through Bruno (create room, send message) works.
- [ ] Openfire admin console: *Server Settings → Client Connections → Advanced → STARTTLS* = **Required** (stored in MySQL, survives restarts).
- [ ] Negative test: a client with `tls.enabled=false` is rejected.
- [ ] Negative test: `trustCert` pointing at a different cert fails with `PKIX path building failed`.
- [ ] Screenshots attached to the story.

Gajim users get a warning after each Openfire restart (the cert is reissued each start). Install `ca.crt` as a trusted root on their machines to stop it.

---

## Certificate lifecycle: attach, expiry, renewal

### How a certificate gets attached

Every time an Openfire pod starts, this happens before Openfire itself runs:

```
Argo syncs / pod restarts
        │
        ▼
init container "generate-keystore"
  1. deletes the old keystore
  2. creates a new key pair for OPENFIRE_FQDN
  3. signs it with the CA from the openfire-ca Secret (/ca/ca.p12)
     → SAN: domain, openfire-service names, OPENFIRE_NODE_IP
     → valid 825 days from now
  4. writes keystore (Openfire's cert + CA chain) and truststore (CA)
        │
        ▼
Openfire container starts and serves that cert on 5222 (STARTTLS) and 9091
        │
        ▼
Gateway connects → checks the cert was signed by ca.crt and names openfire-xmpp-<env>
  → trusted → TLS + login
```

On the gateway side, nothing is attached at runtime. `ca.crt` comes from the `openfire-ca` ConfigMap, mounted at `/etc/openfire-certs/ca.crt` and loaded by `buildTrustManager()` at startup.

### What expires, and when

| Certificate | Valid for | Renewed how | Expires in practice? |
| --- | --- | --- | --- |
| Openfire cert (per env) | 825 days | Automatically, on **every pod start** | Only if a pod runs 825 days with no restart |
| HFC2 Non-Prod CA | 10 years | **Manually**, using the steps below | Yes, once, at the date in `ca.crt` |

Check the dates any time:

```bash
# CA
openssl x509 -in ca.crt -noout -enddate

# Openfire cert currently being served
openssl s_client -connect 10.11.253.93:30522 -starttls xmpp -servername openfire-xmpp-dev1 \
  </dev/null 2>/dev/null | openssl x509 -noout -enddate
```

### What happens when something expires

**The CA expires:** the gateway stops trusting **every** Openfire cert signed by it, including ones issued that day. In **all** environments at once:

- Gateway log: `SSLHandshakeException: PKIX path building failed` (or `CertificateExpiredException`), `Failed to connect system user`.
- Gateway state goes `NON_OPERATIONAL` and the reconnect task keeps failing.
- Restarting Openfire does **not** help: it would keep signing with the expired CA.

**An Openfire cert expires** (pod not restarted for 825 days): the same errors, but only in that environment. Fix: restart the Openfire pod (Argo → Restart, or any redeploy). It reissues the cert.

### What to do: renewing the CA

Do this **about a month before** the CA expiry date, with no downtime:

1. **Create the new CA** exactly as in [Step 1](#step-1-create-the-ca-once), into a new folder (e.g. `/c/hfc2-pki/2036`). Keep the alias `hfc2-ca`.
2. **Gateway: trust both CAs.** Merge:
   ```bash
   cat old/ca.crt new/ca.crt > base-manifests/ca.crt
   ```
   Argo restarts the gateway; it still connects (old CA still valid).
3. **Openfire: switch to the new CA.** Replace `ca.p12` and `ca.crt` in the Openfire base with the new ones (and `capass` if it changed). Merge. Argo restarts Openfire, which now signs with the new CA; the gateway already trusts it.
4. **Verify** with [Step 7](#step-7-verify-enforce-and-capture-evidence), using the new `ca.crt`.
5. **Gateway: drop the old CA.** Set `base-manifests/ca.crt` to the new CA only and merge.
6. Update local runs (`src/main/resources/ca.crt`) and Gajim users' trusted root.

**Already expired?** Same steps, but expect the gateway to be down until step 3 finishes. You can do steps 2 and 3 in one go.

### Early warning

Add this inside the loop in `buildTrustManager()`, so every gateway start logs a warning in the last 90 days:

```java
long daysLeft = Duration.between(Instant.now(), x.getNotAfter().toInstant()).toDays();
if (daysLeft < 90) {
    log.warn("Trusted certificate {} expires in {} days - renew the HFC2 CA (see README)",
             x.getSubjectX500Principal(), daysLeft);
}
```

Also put the CA expiry date in the team calendar with a reminder one month before.

---

## Troubleshooting

| Symptom | Cause | Fix |
| --- | --- | --- |
| Init container fails at `-gencert` with "alias does not exist" | `ca.p12` built without `-name hfc2-ca` | Rebuild `ca.p12` as in Step 1 |
| Init container can't read `ca.p12` | Older Java in the Openfire image | Rebuild `ca.p12` with `-legacy` |
| Init container: `OPENFIRE_NODE_IP` empty / SAN error | Missing in that overlay's `openfire-env` | Add it (Step 3) |
| Gateway: `PKIX path building failed` | Gateway doesn't trust the CA | `trustCert` must point at `ca.crt`; use `setCustomX509TrustManager` |
| Gateway: `No subject alternative names matching ...` | `OPENFIRE_FQDN` ≠ gateway `domain` | Make them equal |
| YAML error "block composed value at the same line as key" | Space after `classpath:` | Use `"classpath:ca.crt"` |
| All envs suddenly fail with `PKIX path building failed` / `CertificateExpiredException` | CA expired | [Renew the CA](#what-to-do-renewing-the-ca) |
| One env fails with `CertificateExpiredException` | Openfire pod not restarted for 825 days | Restart the Openfire pod |
| Need to see what Java receives | — | VM option `-Djavax.net.debug=ssl:handshake:verbose`, look for `Consuming server Certificate` |

---

## Adding a new environment

1. Create its Openfire overlay with `OPENFIRE_FQDN` and `OPENFIRE_NODE_IP`.
2. Add the two TLS literals to its gateway overlay.
3. Merge. Nothing else: the CA in both bases already covers it.

---

## Open questions

- Should plugin calls move from HTTP 30090 to HTTPS 30092 now the cert carries the IP SAN, and should NodePorts 30090 / 30091 be removed?
- Move the Openfire keystore password off `changeit`? Change it in Openfire and in `storepass` together.
- Production: which CA, held outside git, will be used?
- Who owns CA renewal, and is the expiry date in the team calendar?
