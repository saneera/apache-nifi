# apache-nifi-flow-deployer

Deploys NiFi flows from Git into the Red and Black NiFi 2.7 instances. It runs `deployer.sh` as a Kubernetes **Job**,
managed by its **own Argo CD application** per environment and side. It is separate from the NiFi deployment itself,
so syncing it never restarts NiFi, and syncing NiFi never deploys flows.

```
DeployandOrch/apache-nifi-flow-deployer/
├── argocd-files/
│   ├── dev-1/
│   │   ├── argocd-apache-nifi-black-application.yaml
│   │   └── argocd-apache-nifi-red-application.yaml
│   └── sil-direct/
│       ├── argocd-apache-nifi-black-application.yaml
│       └── argocd-apache-nifi-red-application.yaml
├── base-manifests/
│   ├── deployer.sh              the deploy script (mounted into the Job from a ConfigMap)
│   ├── deployer-job.yaml        the Job (Argo CD PostSync hook)
│   └── kustomization.yaml       Job + script ConfigMap + nifi-toolkit image
└── env/
    ├── dev-1/
    │   ├── black/  flows/  env-deploy.properties  nifi-params.properties  kustomization.yaml
    │   └── red/    flows/  env-deploy.properties  nifi-params.properties  kustomization.yaml
    └── sil-direct/
        ├── black/  flows/  env-deploy.properties  env-params.properties   kustomization.yaml
        └── red/    flows/  env-deploy.properties  env-params.properties   kustomization.yaml
```

Each `env/<environment>/<side>` folder is one overlay, and one Argo CD application in `argocd-files/<environment>/`
points at it.

| File | What it holds |
|---|---|
| `flows/*.json` | The NiFi flow definitions to deploy (exported from dev, e.g. `Circuit-Manager-version-1.json`) |
| `env-deploy.properties` | Where to deploy: NiFi and Registry URLs, bucket, RPG target, `START_FLOW`, etc. |
| `env-params.properties` / `nifi-params.properties` | Parameter values for this environment (`PARAM_<name>=<value>`) |
| `kustomization.yaml` | Namespace, name prefix (`nifi-red-` / `nifi-black-`), and the ConfigMaps/Secret built from the files above |

---

## How a deployment runs

```
 Git (develop)  ──►  Argo CD sync of flow-deployer app  ──►  PostSync Job  ──►  deployer.sh
                                                                                   │
                     NiFi Registry  ◄── upload new version (only if the flow changed)
                     NiFi           ◄── import / upgrade process group, parameters, services, start
```

1. You commit a flow file or a properties change and **sync** the Argo CD application for that environment and side.
2. Argo CD applies the ConfigMaps, then creates the Job as a **PostSync hook**. The previous Job is deleted first
   (`BeforeHookCreation`), so every sync runs the script once.
3. The Job mounts the flow files at `/flows` and the script at `/scripts/deployer.sh`, loads the properties as
   environment variables, and runs the script.
4. The script exits 0 if everything deployed. Any NiFi or Registry error fails the Job, and the log shows the HTTP
   status and response.

### What `deployer.sh` does

**Setup (once per run)**

| Step | What happens |
|---|---|
| Wait for NiFi | Retries until the NiFi API answers. |
| Log in | Gets a token with `SINGLE_USER_CREDENTIALS_USERNAME` / `_PASSWORD`. |
| Registry client | Uses `REGISTRY_ID` if it exists in this NiFi. Otherwise it finds a **NiFi Registry** client pointing at the Registry or creates one. Git-type clients (Azure DevOps, GitHub) are ignored. |
| Bucket | Uses `REGISTRY_BUCKET_ID` if set. Otherwise it finds the bucket called `BUCKET_NAME`, or creates it. |
| Position | Works out where new process groups go on the canvas (see below). |
| Parameters | Collects all `PARAM_*` variables. |

**For each `flows/*.json` file**

1. **Prepare.** Rewrites Remote Process Group URLs to `TARGET_RPG_URL` and puts the `PARAM_` values into the flow's
   parameter context. Sensitive parameters are never touched.
2. **Change check.** Computes the SHA-256 of the flow file and compares it with the hash stored in the Registry
   flow's description (`flow-hash:<hash>`).
3. **Unchanged** (same hash and the process group already exists): no new version is created. Only changed
   `PARAM_` values are applied to the process group's parameter context. The flow counts as *Skipped*.
4. **Changed or new:**
   - creates the flow in the bucket if it isn't there yet,
   - uploads the next version to the Registry and stores the new hash,
   - **new process group:** imports it under the root canvas at that version,
   - **existing process group:** upgrades it to the new version through NiFi's version update request and waits
     for it to finish,
   - enables the group's controller services and waits until they're enabled,
   - applies changed `PARAM_` values to the group's parameter context,
   - starts the group if `START_FLOW=true` (stops it if `false`).
5. Prints a summary: **Total / Deployed / Skipped**.

The process group, the Registry flow and the parameter context are matched by the flow's **name** inside the JSON
(`flowContents.name`), not by the file name.

---

## Settings (`env-deploy.properties`)

| Variable | Required | Meaning |
|---|---|---|
| `NIFI_URL` | yes | NiFi URL, e.g. `https://172.27.3.12:30074` |
| `REGISTRY_URL` | yes | NiFi Registry URL the Job uses, e.g. `http://172.27.3.23:30099` |
| `TARGET_RPG_URL` | yes | URL that Remote Process Groups in the flows are pointed at (the other side's NiFi) |
| `SINGLE_USER_CREDENTIALS_USERNAME` / `_PASSWORD` | yes | NiFi login |
| `BUCKET_NAME` | yes* | Registry bucket, found or created. On an existing setup use the **existing** bucket's name. |
| `REGISTRY_BUCKET_ID` | no | Bucket id. Takes precedence over `BUCKET_NAME`. |
| `REGISTRY_ID` | no | Registry client id in NiFi. Leave empty on a fresh NiFi. |
| `REGISTRY_CLIENT_URL` | no | Registry URL as **NiFi** reaches it, if different from `REGISTRY_URL` |
| `REGISTRY_CLIENT_NAME` | no | Name for a client the script creates (default `NiFi Registry`) |
| `REGISTRY_SSL_CONTEXT_SERVICE_ID` | no | Only for an https Registry (see Fresh NiFi) |
| `START_FLOW` | no | `true` to start the flows after deploy (default `false`) |
| `POSITION_STEP` | no | Gap between new process groups (default `400`) |
| `POSITION_X` / `POSITION_Y` | no | Put new process groups at a fixed spot instead |
| `POLL_SECONDS` | no | Wait between status checks (default `5`) |

\* `BUCKET_NAME` or `REGISTRY_BUCKET_ID`.

### Parameters (`PARAM_*`)

```properties
PARAM_mq-host-name=mq.example.local
PARAM_mq-queue-name=HFC2.IN
PARAM_fragment_manager_url=http://172.27.3.23:30124
```

- `PARAM_<name>` sets the NiFi parameter `<name>`. Names match ignoring `-`, `_` and `.`, so
  `PARAM_fragment_manager_url` sets `fragment-manager-url`.
- Only parameters that exist in the flow's own parameter context are set. Unknown names are ignored.
- **Sensitive** parameters (passwords marked sensitive in NiFi) are never set by the script. Set them once in NiFi.
- Changing only a `PARAM_` value does **not** create a new flow version. The next run updates the parameter context.

### Where new process groups go

New groups are placed in the same row as the **right-most** existing group, `POSITION_STEP` to its right. With the
right-most group at x −4240, y −981, the log shows:

```
MAX_X=-4240
POS_X=-3840 POS_Y=-981
```

On an empty canvas they start at 0,0. Existing process groups are never moved.

---

## Day to day

**New flow, or a new version of a flow**
1. Export the flow from dev NiFi (Download flow definition) and put the JSON in `env/<environment>/<side>/flows/`.
   For a new version, replace the old file (e.g. `-version-1.json` → `-version-2.json`).
2. Make sure the new file is listed in that folder's `kustomization.yaml`.
3. Commit and push to `develop`.
4. Sync the matching Argo CD application, then check the Job log.

**Change a URL or parameter value:** edit `env-deploy.properties` or the params file, commit, and sync.

**Re-run without changes:** sync again. Unchanged flows are skipped, and parameters are re-applied.

**Logs**

```bash
kubectl -n inspire-silrelease get jobs | grep flow-deploy
kubectl -n inspire-silrelease logs -f job/<job-name>
```

Or open the Job in the Argo CD UI. The end of a good run looks like:

```
==== Deployment Summary ====
Total    : 2
Deployed : 1
Skipped  : 1
==== All flows deployed ====
```

---

## Fresh NiFi / fresh Registry

Nothing needs to be set up by hand for an **http** Registry:

| Needed | Done by the script |
|---|---|
| Registry client in NiFi | Created (`REGISTRY_ID` not needed) |
| Bucket | Created from `BUCKET_NAME` |
| Process groups, parameter contexts | Created by the import |
| Controller services | Enabled |

For an **https** Registry, NiFi needs a controller-level **SSL Context Service** first. The script uses the first
one it finds, or `REGISTRY_SSL_CONTEXT_SERVICE_ID`. Without it the Job stops before creating anything and says why.
After a fresh install, also set the **sensitive** parameter values in NiFi once.

---

## Troubleshooting

| Symptom in the log | Cause / fix |
|---|---|
| `Waiting for NiFi...` repeats | NiFi is down or `NIFI_URL` is wrong. The Job gives up after `activeDeadlineSeconds`. |
| HTTP 401 / login fails | Wrong `SINGLE_USER_CREDENTIALS_*` |
| `Error retrieving flow` (HTTP 400) on import | The Registry client in NiFi is invalid or points elsewhere. Clear `REGISTRY_ID`, or fix `REGISTRY_CLIENT_URL` to the address NiFi can reach. |
| A second bucket appeared | `BUCKET_NAME` doesn't match the existing bucket's name. Set it to the existing name, or use `REGISTRY_BUCKET_ID`. |
| Every flow uploads a new version on the first run | Expected once after moving to this script version (the hash format changed). Later runs skip unchanged flows. |
| `Timed out waiting for controller services` | A service is invalid in this environment (e.g. a missing sensitive value). Check it in NiFi. |
| A parameter didn't change | The name doesn't exist in the flow's context, or it is sensitive. |
| `Deployment FAILED while processing <flow>` | Look at the line just above it: it shows the failing call, HTTP status and response. |

The script can be run outside Kubernetes too: export the same variables, set `FLOWS_DIR` to a folder of flow files,
and run `bash deployer.sh` (needs `curl`, `jq`, `sha256sum`).
