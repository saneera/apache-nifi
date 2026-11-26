# NiFi Monitoring Setup – sil-direct (step by step)

This guide sets up Prometheus monitoring for NiFi Red and NiFi Black in **sil-direct**. The same steps work for **dev-1**. Swap `sil-direct` for `dev-1` and use `scripts-dev-1.sh`.

You will:

1. Create the certificates with the sil-direct script
2. Copy the NiFi TLS secrets into the NiFi app
3. Copy the Prometheus secret into the Prometheus app and add the scrape job
4. Commit, let Argo CD sync, and restart NiFi
5. Add the `prometheus` user in NiFi
6. Check it works

---

## Before you start

You need:

- Git Bash (Windows) or a Linux shell, with `openssl`, `keytool` (Java) and `base64`
- The `HFC2-Deploy` repo on the `develop` branch
- Access to Argo CD, and `kubectl` access to the sil-direct clusters
- Admin access to the NiFi Red and NiFi Black UIs

All paths below are from the repo root. Set these once in your shell:

```bash
NIFI=INSPIRE/Base/DeployandOrch/apache-nifi-instances
PROM=INSPIRE/Base/DeployandOrch/telemetry-stack/prometheus
ENV=sil-direct
```

## Folder layout

**NiFi** (`apache-nifi-instances`):

```
apache-nifi-instances/
├── argocd-files/
│   ├── dev-1/            Argo CD apps for dev-1 (red, black, registry)
│   └── sil-direct/       Argo CD apps for sil-direct (red, black, registry)
├── base-manifests/       nifi-red, nifi-black, nifi-registry
├── ca-creation/
│   ├── env/
│   │   ├── dev-1/        output of scripts-dev-1.sh
│   │   └── sil-direct/   output of scripts-sil-direct.sh
│   │       ├── root-ca.crt, root-ca.key, root-ca.srl
│   │       ├── red/          tls-secret.yaml + keystore files
│   │       ├── black/        tls-secret.yaml + keystore files
│   │       └── prometheus/   nifi-scrape-tls.yaml + client cert
│   ├── scripts-dev-1.sh
│   ├── scripts-sil-direct.sh
│   └── readme.md
└── env/
    ├── dev-1/            red, black, nifi-registry overlays
    └── sil-direct/       red, black, nifi-registry overlays
```

**Prometheus** (`telemetry-stack/prometheus`):

```
prometheus/
├── argocd-files/sil-direct/   application-enmf.yaml, application-rx.yaml, application-tx.yaml
├── base-manifests/            prometheus.yaml, kustomization.yaml, nifi-scrape-tls.yaml, ...
└── env/sil-direct/
    ├── enmf/   cm-override.yaml, kustomization.yaml, nifi-scrape-tls.yaml, pv-patch.yaml, resources.yaml
    ├── rx/
    └── tx/
```

Each Prometheus app (`enmf`, `rx`, `tx`) only scrapes the NiFi it can reach. Fill this table in for sil-direct:

| Prometheus app | Scrapes | Target |
| --- | --- | --- |
| `enmf` | NiFi Red | `172.27.3.23:30084` (working) |
| `<app on the Black cluster>` | NiFi Black | `<black IP>:<NodePort>` |

---

## Step 1 – Create the certificates

```bash
cd $NIFI/ca-creation
./scripts-sil-direct.sh
cd -
```

Everything is written to `ca-creation/env/sil-direct/`.

Check it worked:

```bash
openssl verify -CAfile $NIFI/ca-creation/env/$ENV/root-ca.crt \
  $NIFI/ca-creation/env/$ENV/red/red.crt \
  $NIFI/ca-creation/env/$ENV/black/black.crt \
  $NIFI/ca-creation/env/$ENV/prometheus/prometheus.crt
```

All three should say `OK`.

Good to know:

- The root CA is only made the first time. After that the script reuses `env/sil-direct/root-ca.*`.
- Every run makes **new** Red, Black and Prometheus certificates. If you run it, you must deploy all of them (steps 2 to 5).
- Never commit `root-ca.key` or any `.key` file. Check with:

  ```bash
  git status $NIFI/ca-creation/env/
  ```

  Nothing under `env/` should show as a new file. If it does, fix `.gitignore` before you commit.

## Step 2 – Copy the NiFi TLS secrets

These hold each NiFi's keystore and truststore. They are also used for Red to Black traffic, so **always update both together**.

```bash
cp $NIFI/ca-creation/env/$ENV/red/tls-secret.yaml   $NIFI/env/$ENV/red/tls-secret.yaml
cp $NIFI/ca-creation/env/$ENV/black/tls-secret.yaml $NIFI/env/$ENV/black/tls-secret.yaml
```

Open both files and check:

- The name is `nifi-red-tls-secret` (and `nifi-black-tls-secret`)
- There is no `namespace:` line. Kustomize adds it.

## Step 3 – Copy the Prometheus secret

Copy it into the Prometheus app that scrapes each NiFi:

```bash
cp $NIFI/ca-creation/env/$ENV/prometheus/nifi-scrape-tls.yaml $PROM/env/$ENV/enmf/nifi-scrape-tls.yaml
# repeat for the app on the Black cluster, e.g.
# cp $NIFI/ca-creation/env/$ENV/prometheus/nifi-scrape-tls.yaml $PROM/env/$ENV/<app>/nifi-scrape-tls.yaml
```

Open the copied file and set the namespace to `telemetry-stack`:

```yaml
metadata:
  name: nifi-scrape-tls
  namespace: telemetry-stack
```

Make sure it is listed in that app's `kustomization.yaml`:

```yaml
resources:
  - nifi-scrape-tls.yaml
```

## Step 4 – Check the secret is mounted

The Prometheus pod has to mount the secret. Open `$PROM/base-manifests/prometheus.yaml` (or the app's patch) and check the Deployment has:

```yaml
      containers:
        - name: prometheus-server
          volumeMounts:
            - name: nifi-tls
              mountPath: /etc/prometheus/nifi-tls
              readOnly: true
      volumes:
        - name: nifi-tls
          secret:
            secretName: nifi-scrape-tls
            defaultMode: 0444
```

Two things that matter:

- The container name must be `prometheus-server`.
- `defaultMode` must be `0444`. With `0440` Prometheus gets `permission denied`.

You only do this once. It is already in place for `enmf`.

## Step 5 – Add the scrape job

Add the job to that app's `cm-override.yaml`, under `scrape_configs:`.

For NiFi Red (`env/sil-direct/enmf/cm-override.yaml`):

```yaml
      - job_name: nifi-red
        scheme: https
        metrics_path: /nifi-api/flow/metrics/prometheus
        scrape_interval: 30s
        tls_config:
          ca_file:   /etc/prometheus/nifi-tls/ca.crt
          cert_file: /etc/prometheus/nifi-tls/tls.crt
          key_file:  /etc/prometheus/nifi-tls/tls.key
        static_configs:
          - targets: ['172.27.3.23:30084']
            labels: { nifi_instance: nifi-red, domain: red }
```

For NiFi Black, in the app on the Black cluster:

```yaml
      - job_name: nifi-black
        scheme: https
        metrics_path: /nifi-api/flow/metrics/prometheus
        scrape_interval: 30s
        tls_config:
          ca_file:   /etc/prometheus/nifi-tls/ca.crt
          cert_file: /etc/prometheus/nifi-tls/tls.crt
          key_file:  /etc/prometheus/nifi-tls/tls.key
        static_configs:
          - targets: ['<black IP>:<NodePort>']
            labels: { nifi_instance: nifi-black, domain: black }
```

The target IP must be in that NiFi's certificate. The script puts `RED_IP` and `BLACK_IP` in. If you use a different address, add it to the script and re-run step 1.

## Step 6 – Commit and sync

```bash
git add $NIFI/env/$ENV $PROM/env/$ENV
git status          # check no .key or root-ca files are staged
git commit -m "sil-direct: NiFi TLS certs and Prometheus NiFi scraping"
git push
```

Then in Argo CD, in this order:

1. Sync **apache-nifi-red** and **apache-nifi-black**.
2. Restart both NiFi pods so they load the new certificates. Do both in the same window.

   ```bash
   kubectl -n inspire-silrelease rollout restart statefulset nifi-red     # on the Red cluster
   kubectl -n inspire-silrelease rollout restart statefulset nifi-black   # on the Black cluster
   ```

3. Check Red and Black are still talking (Remote Process Group shows Transmitting, no certificate errors).
4. Sync the Prometheus apps (for example **prometheusservice-enmf**).
5. Restart Prometheus so it reads the new config:

   ```bash
   kubectl -n telemetry-stack rollout restart deploy/prometheus
   ```

## Step 7 – Add the prometheus user in NiFi

Do this once on **each** NiFi (Red and Black):

1. ☰ → **Users** → **+**
2. Identity: `CN=prometheus, OU=monitoring`
3. ☰ → **Policies** → **view the user interface** → add `prometheus`

If you skip this, the target shows `403`.

## Step 8 – Check it works

1. Open Prometheus. For enmf this is `http://172.27.3.23:30502/prometheus`.
2. **Status → Target health**, filter on `nifi-red`. It should show **1/1 up**.
3. **Query**: `up{job="nifi-red"}` should return `1`.

If it is not up, read the Prometheus pod logs (Argo CD → pod → **Logs** → container `prometheus-server`) and check the table below.

## Step 9 – Grafana

1. Grafana → **Dashboards** → **New** → **Import** → upload `nifi-dashboard.json`.
2. Set **Prometheus** to the data source, **NiFi job** to `nifi-red` (or `nifi-black`). Save.
3. The data source URL needs `/prometheus` on the end, e.g. `http://172.27.3.23:30502/prometheus`.

---

## If something goes wrong

| What you see | Fix |
| --- | --- |
| Script: `subject name is expected to be in the format...` | Git Bash path issue. The script must have `export MSYS_NO_PATHCONV=1` at the top |
| Script: `root-ca.crt: No such file` | The CA step failed. Check `CA_DIR`, `CA_KEY`, `CA_CRT` all point to the same folder |
| Argo CD: `could not find ServiceMonitor` | Don't use a ServiceMonitor. There is no Prometheus Operator. Use the scrape job (step 5) |
| Prometheus log: `permission denied` on `ca.crt` | `defaultMode: 0444` (step 4) |
| Target `403` | NiFi user or policy missing (step 7) |
| Target `x509: ...` | Wrong `ca.crt`, or the target IP is not in NiFi's certificate |
| Target `no such host` | Prometheus can't resolve that name. Use an IP it can reach |
| Target `connection reset` / `EOF` | Istio STRICT mTLS on the NiFi pod. Talk to the platform lead |
| Red and Black stop talking after deploy | Only one side got the new certs. Deploy both and restart both |
| Job not in Target health | Config not loaded. Check Argo synced, then restart Prometheus |

## Renewing certificates

The certificates last 825 days. To renew, repeat steps 1, 2, 3 and 6. The root CA stays the same, so you do not need to redo step 7.

## Adding a new environment

1. Copy `scripts-sil-direct.sh` to `scripts-<env>.sh`.
2. Change the env name and the Red/Black IPs and DNS names at the top.
3. Run it. It makes its own `ca-creation/env/<env>/` folder with its own root CA.
4. Follow steps 2 to 9 with `ENV=<env>`.
