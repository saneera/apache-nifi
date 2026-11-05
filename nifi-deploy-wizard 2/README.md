# Flow Deployer — NiFi deployment wizard

A step-by-step web UI that deploys NiFi flows **directly through the NiFi and NiFi Registry REST APIs**.
The backend is a Java port of the deploy script: same steps, same order, same skip-if-unchanged logic.
No Kubernetes Job and no shell script needed.

```
 Browser (Vue 3 + TS) ──REST + SSE──▶ Spring Boot ──▶ NiFi API      (auth, process groups, versions, services, params)
                                                 └──▶ Registry API  (buckets, flows, versions)
```

- **Runs on the server.** Closing the tab, a laptop going to sleep or a refresh doesn't stop a deployment.
  Reopening the page jumps back to a running deployment; history replays the full log.
- **One deployment per environment at a time** (409 if someone else is already deploying to it).
- **Cancel** stops after the flow currently being deployed. A NiFi version change is never interrupted half-way.
- **Audit trail.** Who, when, which flows, the result → `/data/audit.jsonl` + `AUDIT` logger.
- **Credentials stay server-side.** Sensitive parameter values are never sent to the browser and can't be set from it.

## Wizard steps

| Step | What happens |
|---|---|
| 1 Target | Pick Black / Red NiFi and run the connection check. Recent deployments are listed here; click one to view it. |
| 2 Registry & bucket | Pick the registry client. If the bucket is missing, **create it and capture its ID** (find-or-create). |
| 3 Flows | Each `/flows/*.json` is hashed and compared with the Registry (`flow-hash:` in the description) → New / Changed / Unchanged |
| 4 Parameters | Review or override non-sensitive parameter values |
| 5 Review | Shows what will happen to each flow |
| 6 Deploy | Live progress for each flow and stage, controller-service polling, log, summary, cancel |

## The deploy, mapped to the script

`backend/.../deploy/DeploymentRunner.java`

| Script | Java | NiFi / Registry call |
|---|---|---|
| `wait_for_nifi`, `authenticate` | `NifiService.token` | `POST /nifi-api/access/token` (cached; re-login on 401) |
| `get_root_pg`, `get_next_position` | `rootProcessGroupId`, `nextPosition` | `GET /flow/process-groups/root`, `GET /flow/process-groups/{root}` |
| `cache_registry_flows` | `RegistryService.bucketFlows` | `GET /buckets/{b}/flows` |
| `prepare_flow`, `inject_parameters` | `FlowPreparer.prepare` | RPG target URIs → `rpg-url`; overrides into parameter contexts |
| `calculate_hash` | `FlowPreparer.hash` | sha256(file [+ overrides]) |
| `find_pg` | `findProcessGroup` | child PG with the flow's name |
| *skip if hash equal and PG exists* | | |
| `create_registry_flow` | `createFlow` | `POST /buckets/{b}/flows` |
| `get_latest_version` | `latestVersion` | `GET …/versions/latest` |
| `upload_registry_version` | `uploadVersion` | `POST …/versions` |
| `update_registry_hash` | `updateFlowHash` | `PUT /buckets/{b}/flows/{f}` |
| `import_flow` | `importVersionedFlow` | `POST /process-groups/{root}/process-groups` |
| `update_flow_version` | `changeVersion` | `POST /versions/update-requests/process-groups/{pg}` → poll → delete |
| `enable_controller_services` | `enableControllerServices` + poll | `PUT /flow/process-groups/{pg}/controller-services`, poll ≤30 |
| `update_parameter_contexts` | `updateParameterContext` | `POST /parameter-contexts/{id}/update-requests` → poll → delete |
| `control_pg_state` | `setRunState` (+ RPG transmit) | `PUT /flow/process-groups/{pg}` `{state: RUNNING}` |

Behaviour switches (`deployer.deploy.*` in application.yml): poll interval, controller-service poll limit,
whether services stuck in DISABLED fail the flow, parameter sync on/off, RPG transmission, stop on first failure.

## Layout

```
frontend/   Vue 3 + TypeScript + Vite + Pinia (no UI library; fonts bundled — air-gap friendly)
  src/api/       types.ts (contract) · http.ts (real) · mock.ts (in-browser simulation)
  src/stores/    wizard.ts — all wizard state, reattach-on-reload
  src/steps/     one component per step
backend/    Spring Boot 3.4 / Java 21 (no extra dependencies beyond Spring Boot starters)
  nifi/          NifiService, RegistryService, RestClients, Poller
  flows/         FlowCatalog (reads /flows), FlowStatusService
  deploy/        DeploymentRunner (the script), FlowPreparer, Deployment (events), DeploymentManager, AuditLog
  web/           ApiController, DeploymentController (REST + SSE), Dtos
k8s/deployer.yaml   ConfigMap, audit PVC, Deployment, Service (no RBAC needed)
Dockerfile          one image: UI built into the jar
```

## Run it

**UI only, simulated backend:**
```bash
cd frontend && npm install && npm run dev:mock          # http://localhost:5173
```

**Full stack locally** (needs network access to NiFi/Registry):
```bash
cd backend
FLOWS_DIR=/path/to/flows AUDIT_FILE=./audit.jsonl \
BLACK_NIFI_URL=https://… BLACK_REGISTRY_URL=https://… BLACK_NIFI_USERNAME=… BLACK_NIFI_PASSWORD=… \
mvn spring-boot:run
cd ../frontend && npm run dev                           # proxies /api → :8080
```

**Tests:** `cd backend && mvn test` (FlowPreparer: RPG rewrite, overrides, hashing).

**Image + cluster:**
```bash
docker build -t your-registry.local/flow-deployer:1.0.0 .
kubectl apply -f k8s/deployer.yaml                      # after editing names/URLs/image/volumes
```

## Before first real use

1. **Try it on a non-production NiFi first.** The API calls follow NiFi 2.x
   (e.g. `versionControlInformation.version` is a string). Check the first runs closely.
2. **Hash format.** If your old script stored `flow-hash:` values computed differently, every flow shows
   **Changed** once. After one deploy they line up.
3. **`prepare_flow` / `inject_parameters`.** `FlowPreparer` rewrites RPG target URIs and applies overrides.
   If your script did anything else (other URL rewrites, env-specific values), add it there.
4. **Put authentication in front** (oauth2-proxy / ingress auth) and pass `X-Forwarded-User`. The app has no
   login of its own. Anyone who can reach it can deploy, and the audit log will say "anonymous".
5. **TLS.** Add the cluster CA to the JVM truststore rather than using `insecure-skip-tls-verify`.
6. **Single replica.** Running deployments and the per-environment lock are in memory. If the pod restarts
   mid-deployment, that run is lost (the audit log shows "started" with no "finished"). Check NiFi and redeploy.
