# NiFi Monitoring – Prometheus & Grafana (HFC2)

_Last updated: 2026-11-25_

## Overview

Prometheus now collects metrics from NiFi Red, and Grafana shows them on a dashboard. This lets us see if NiFi is up, how much data is moving, whether queues are backing up, and how much memory NiFi is using, without logging in to the NiFi UI.

NiFi 2.x has a built-in metrics page at `/nifi-api/flow/metrics/prometheus`. We did not need to install any plugin or reporting task in NiFi. Prometheus reads that page every 30 seconds.

NiFi Red is done and working. NiFi Black is still to do (see the last section).

## How it works

```
                 queries                    HTTPS + client cert
  +-------------+        +-------------------+  ------------->  +--------------------------+
  |  Grafana    | <----- |   Prometheus      |                  |  NiFi Red                |
  |  NiFi 2.x - |        |   ns telemetry-   |  <-------------  |  /nifi-api/flow/metrics/ |
  |  Flow Health|        |   stack, job      |     metrics      |  prometheus (port 30084) |
  +-------------+        |   nifi-red, 30s   |                  |  checks cert + policy    |
                         +-------------------+                  +--------------------------+
                                  ^                                         ^
                                  | mounted                                 | NiFi trusts this CA
                         +-------------------+      signs       +--------------------------+
                         | Secret            | <- - - - - - - - | Shared Root CA           |
                         | nifi-scrape-tls   |                  | made by scripts-sil2.sh  |
                         | /etc/prometheus/  |                  | signs NiFi + Prometheus  |
                         | nifi-tls          |                  +--------------------------+
                         +-------------------+
```

Prometheus calls NiFi, NiFi does not push. Prometheus shows its client certificate, NiFi checks it was signed by our root CA and that the `prometheus` user has the right policy, then sends back the metrics. Grafana only talks to Prometheus.

## What lives where

Almost everything is on the Prometheus side. NiFi only needs one user and one policy added in the UI.

| Part | Where | What it is |
| --- | --- | --- |
| Certificate script | `HFC2-Deploy/.../apache-nifi-instances/ca-creation/scripts-sil2.sh` | Creates the root CA, the Red and Black keystores and truststores, and the Prometheus client certificate |
| Generated files | `ca-creation/certs/` (not in git) | Output of the script, one folder per certificate |
| NiFi TLS secrets | NiFi app overlays, `red/tls-secret.yaml` and `black/tls-secret.yaml` | Keystore and truststore for each NiFi. Also used for Red to Black traffic |
| Prometheus app | Argo CD app `prometheusservice-enmf`, namespace `telemetry-stack` | Plain Prometheus (no Operator), container `prometheus-server` |
| Prometheus client cert secret | `nifi-scrape-tls` in `telemetry-stack` | `ca.crt`, `tls.crt`, `tls.key` used to call NiFi |
| Scrape job | `prometheus` ConfigMap, file `/etc/config/prometheus.yml` | Job `nifi-red` |
| Secret mount | Patch on the Prometheus Deployment | Mounts the secret at `/etc/prometheus/nifi-tls` |
| NiFi user | NiFi UI on each side | `CN=prometheus, OU=monitoring` with the policy **view the user interface** |
| Dashboard | Grafana, imported from `nifi-dashboard.json` | "NiFi 2.x - Flow Health" |

We do not use a ServiceMonitor. Our cluster has no Prometheus Operator, so the ServiceMonitor CRD does not exist and Argo CD fails the sync if one is added.

## Certificates

Prometheus logs in to NiFi with a client certificate, not a username and password. NiFi's API does not accept basic auth, and its login tokens expire after about 12 hours, so a certificate is the option that needs no upkeep.

All certificates come from one script and are signed by the same root CA (`NiFi-Shared-Root-CA`). Because NiFi already trusts that CA, any certificate it signs is accepted by NiFi.

Run it from `ca-creation` in Git Bash:

```bash
./scripts-sil2.sh
```

It writes everything under `certs/`:

```
certs/
  root-ca/      root-ca.key, root-ca.crt
  red/          red-keystore.p12, red-truststore.p12, tls-secret.yaml
  black/        black-keystore.p12, black-truststore.p12, tls-secret.yaml
  prometheus/   prometheus.crt, prometheus.key, nifi-scrape-tls.yaml
```

Things to know about the script:

- The root CA is only created on the first run. Later runs reuse `certs/root-ca/`. To force a new CA, run it with `--new-ca`. A new CA means both NiFi pods and Prometheus must all be redeployed together.
- Every run makes new Red, Black and Prometheus certificates. If you run it, deploy all of them.
- The Prometheus certificate is `CN=prometheus, OU=monitoring`. This is the name NiFi sees.
- The password can be passed in with `PASS='...' ./scripts-sil2.sh` so it does not have to live in the file.
- `certs/` has its own `.gitignore`. Do not commit it.
- Keep a copy of `root-ca.key` somewhere safe, such as the team vault. Without it, we cannot sign new certificates without redeploying NiFi.

See `README.md` next to the script for full details.

## Prometheus changes

Three changes in the `prometheusservice-enmf` repo. Argo CD syncs them.

**1. The secret.** Copy `certs/prometheus/nifi-scrape-tls.yaml` into the Prometheus app and set its namespace to `telemetry-stack`. A pod can only mount a secret from its own namespace.

**2. Mount the secret into the Prometheus pod.** Kustomize patch on the Deployment:

```yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: prometheus
spec:
  template:
    spec:
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

The container name must be `prometheus-server`. A wrong name adds a second, broken container. Keep `defaultMode: 0444`, because Prometheus runs as a non-root user and cannot read files mounted as `0440`.

**3. The scrape job.** Add under `scrape_configs:` in the `prometheus` ConfigMap:

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

The target is the Red node IP and NiFi's NodePort. This works because `172.27.3.23` is in the NiFi Red certificate. If the IP or port changes, update the job, and re-issue the certificate if the new IP is not in it.

Prometheus only reads its config at start-up. After a ConfigMap-only change, restart it:

```bash
kubectl -n telemetry-stack rollout restart deploy/prometheus
```

## NiFi changes

Nothing changes in the NiFi deployment. Do this once in the NiFi UI on each side:

1. ☰ → **Users** → **+**, identity `CN=prometheus, OU=monitoring`. If `nifi.properties` has identity mapping set up, use the mapped name instead.
2. ☰ → **Policies** → **view the user interface** → add the `prometheus` user.

That is all NiFi needs. NiFi does not push anything. It just answers when Prometheus calls.

## Checking it works

Open Prometheus at `http://172.27.3.23:30502/prometheus` (it runs under the `/prometheus` path).

1. **Status → Target health**, pick `nifi-red` in the pool filter. It should show **1/1 up**.
2. **Query**, run `up{job="nifi-red"}`. It should return `1`.
3. To see what NiFi sends, type `nifi_` in the query box and look at the list.

To check the certificate files inside the pod:

```bash
kubectl -n telemetry-stack exec deploy/prometheus -c prometheus-server -- ls -l /etc/prometheus/nifi-tls
```

The `nifi-red` entries that show up under the `kubernetes-pods` job on port 15020 are Istio sidecar stats, not NiFi. The real NiFi metrics are only under the `nifi-red` job.

## Grafana dashboard

The dashboard is in `nifi-dashboard.json`. To load it:

1. Grafana → **Dashboards** → **New** → **Import**.
2. Upload `nifi-dashboard.json`, click **Load**, then **Import**.
3. At the top of the dashboard, set **Prometheus** to our Prometheus data source, **NiFi job** to `nifi-red`, and **Pod regex** to `.*nifi-red.*`. Save.

If Grafana has no Prometheus data source yet, add one with the URL `http://172.27.3.23:30502/prometheus`. The `/prometheus` on the end matters. Without it the test fails with a 404.

What the dashboard shows:

| Row | Panels |
| --- | --- |
| Overview | NiFi up or down, FlowFiles in and out (last 5 min), total queued, active threads, heap used |
| Throughput | FlowFiles and bytes in and out per process group, plus a chosen key processor such as ListenTCP |
| Queues | Top 10 queues and how close each is to back-pressure |
| JVM and pod | Heap, threads, GC time, pod CPU and memory |
| Processors | Table of every processor's in and out. Processors with no output show red |

NiFi's in and out numbers are totals for the last 5 minutes, not running counters. Read them as "how much in the last 5 minutes".

If a panel shows "No data", the metric name in NiFi 2.7 may be different. Check the real names in Prometheus (`nifi_` in the query box) and fix the panel query.

## Troubleshooting

These are the problems we hit while setting this up, and the fix for each.

| What you see | Cause | Fix |
| --- | --- | --- |
| `subject name is expected to be in the format /type0=value0...` or `root-ca.crt: No such file` when running the script | Git Bash turns `/CN=...` into a Windows path, so openssl fails | The script now sets `export MSYS_NO_PATHCONV=1` at the top. Keep that line |
| Argo CD: `could not find monitoring.coreos.com/ServiceMonitor` and the whole NiFi app fails to sync | No Prometheus Operator in the cluster | Do not use a ServiceMonitor. Use the scrape job in the Prometheus ConfigMap |
| Prometheus log: `open /etc/prometheus/nifi-tls/ca.crt: permission denied` | Secret mounted with `defaultMode: 0440` | Use `0444`, or add `fsGroup: 65534` to the pod |
| Target error `403` | NiFi user or policy missing | Add `CN=prometheus, OU=monitoring` with **view the user interface** |
| Target error `x509: ...` | Wrong CA, or the target address is not in NiFi's certificate | Check `ca.crt` is our root CA and the IP or name is in the certificate SANs |
| Target error `no such host` | Name only resolves inside another cluster (for example `nifi-black...svc.cluster.local` from Red) | Use an address Prometheus can reach |
| Target error `connection reset` or `EOF` | Istio STRICT mTLS on the NiFi pod | Add a PeerAuthentication that allows port 8443. Check with the platform lead first |
| Job missing from Target health | Prometheus still running the old config | Check Argo synced, then restart the Prometheus deployment |

The Prometheus logs are the fastest place to look. In Argo CD, open the Prometheus pod, then **Logs**, container `prometheus-server`.

## Renewals and open items

The certificates last 825 days and the root CA lasts 10 years. To renew, run the script again (same CA), then deploy all three together: both NiFi `tls-secret.yaml` files, then `nifi-scrape-tls`, then restart both NiFi pods. Red and Black must be updated in the same window or Red to Black traffic stops.

Still to do:

- [ ] NiFi Black: add the `prometheus` user and policy in the Black NiFi UI.
- [ ] NiFi Black: decide how it is scraped. This Prometheus runs on the Red cluster, so it either needs Black's external address (if Red to Black monitoring traffic is approved) or Black gets its own Prometheus.
- [ ] Confirm the real NiFi 2.7 metric names and fix any dashboard panels that show "No data".
- [ ] Add alert rules: NiFi down, back-pressure over 80%, no traffic on ListenTCP for 10 minutes.
- [ ] Store `root-ca.key` in the team vault.
- [ ] Move the keystore password out of `scripts-sil2.sh` and keep secrets out of git where possible.
