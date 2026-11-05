# Flow Deployer — NiFi deployment wizard

A step-by-step web UI for the existing NiFi deploy script. The script keeps doing the deployment; the
wizard collects the choices, launches the script as a **Kubernetes Job**, and streams its progress back.

```
 Browser (Vue 3 + TS)  ──REST/SSE──▶  Spring Boot  ──▶  NiFi API / Registry API   (checks, buckets, flow status)
                                              └──────▶  Kubernetes API             (create Job, stream pod log)
                                                              └── Job runs deploy.sh (unchanged logic)
```

## Wizard steps

| Step | What happens | Backend call |
|---|---|---|
| 1 Target | Pick Black / Red NiFi, run connection check | `POST /api/environments/{id}/check` |
| 2 Registry & bucket | Pick registry client. If the expected bucket is missing, **create it and capture its ID** (find-or-create) | `GET …/buckets/resolve`, `POST …/buckets` |
| 3 Flows | Each `/flows/*.json` hashed and compared with the Registry (`flow-hash:` in the description) → New / Changed / Unchanged | `GET …/flows?bucketId=` |
| 4 Parameters | Review/override non-sensitive parameter values; sensitive ones stay in Secrets | `GET /api/flows/{name}/parameters` |
| 5 Review | Plan + the exact env vars the Job will get | — |
| 6 Deploy | Job created; live per-flow stage progress + log console | `POST /api/deployments`, SSE `GET /api/deployments/{job}/events` |

## Layout

```
frontend/          Vue 3 + TypeScript + Vite + Pinia (no UI library; fonts bundled locally — air-gap friendly)
  src/api/         types.ts (API contract), http.ts (real), mock.ts (in-browser mock)
  src/stores/      wizard.ts — all wizard state and step logic
  src/steps/       one component per step
backend/           Spring Boot 3.4 / Java 21 / fabric8 kubernetes-client
  config/          DeployerProperties (environments, flows dir, job template)
  nifi/            NifiService (token, checks, registry clients), RegistryService (buckets, flows)
  flows/           FlowCatalog (reads /flows, hashes, parameter contexts), FlowStatusService
  deploy/          DeploymentService (creates the Job), LogStreamService (SSE), ProgressParser
scripts/           script-changes.sh — additions to the deploy script (see below)
k8s/               deployer.yaml (SA, Role, ConfigMap, Deployment, Service), job-template.yaml
Dockerfile         one image: UI built into the jar
```

## Run it

**UI only, no backend (mock data):**
```bash
cd frontend && npm install && npm run dev:mock      # http://localhost:5173
```

**Full stack locally** (uses your `~/.kube/config` and needs network access to NiFi/Registry):
```bash
cd backend
FLOWS_DIR=/path/to/flows JOB_TEMPLATE=../k8s/job-template.yaml JOB_NAMESPACE=hfc2-nifi \
BLACK_NIFI_URL=https://… BLACK_NIFI_USERNAME=… BLACK_NIFI_PASSWORD=… \
mvn spring-boot:run
cd ../frontend && npm run dev                       # proxies /api → :8080
```

**Image + cluster:**
```bash
docker build -t your-registry.local/flow-deployer:1.0.0 .
kubectl apply -f k8s/deployer.yaml                  # after editing names/URLs/image
```

## Changes needed in the deploy script

`scripts/script-changes.sh` holds drop-in functions plus an annotated main loop. All of them are
**backwards compatible** — run without the new env vars, the script behaves as today.

1. **`find_or_create_bucket`** — resolves `BUCKET_NAME` → ID, creating the bucket if missing.
2. **`flow_selected`** — honours `FLOWS` so only the ticked flows deploy.
3. **`apply_param_overrides`** — applies `PARAM_OVERRIDES` (non-sensitive only; sensitive params are refused by the backend *and* skipped by the jq).
4. **`calculate_hash`** — `sha256` of the original flow file (+ overrides). The backend computes the same
   hash, so the UI's New/Changed/Unchanged matches the script. *After switching, every flow shows
   Changed once, because the Registry still holds hashes in the old format.*
5. **`progress` / `stage` / `summary_marker`** — prints `::progress flow=… stage=… status=…` lines.
   The UI uses these for the per-stage chips. Without them, `ProgressParser` falls back to
   recognising the existing log lines (Processing / Local hash / Poll n/30 / Done:), which is less exact.
6. **`DRY_RUN=true`** — compare only, change nothing.

## Security notes

- **Put authentication in front of it** (Ingress + OIDC/oauth2-proxy, or mTLS). The app has no login;
  anyone who can reach it can deploy.
- NiFi credentials live only in the Kubernetes Secret: the Job reads them via `secretKeyRef`, the backend
  via env vars. They are never sent to the browser, and sensitive parameter values are never returned by the API.
- The ServiceAccount can only create/read Jobs and read pods/logs in its own namespace.
- Prefer adding the cluster CA to the JVM truststore over `insecure-skip-tls-verify` (the `curl -k` equivalent).

## Things to align with your setup

- **`k8s/job-template.yaml`** → replace with your real Job (image, command, Secret names, how `/flows` is mounted).
- **Flows volume** → the deployer must read the same flow files the Job uses (PVC, ConfigMap, or baked into both images).
- **Registry auth** → the script calls the Registry without auth (`curl -k`). If yours is secured, set `registry-token`.
- **Env var names** → `NIFI_URL`, `REGISTRY_URL`, `TARGET_RPG_URL`, `REGISTRY_ID`, `REGISTRY_BUCKET_ID`, `START_FLOW`,
  `POSITION_STEP` match the script's CONFIG section; check against your copy.
