# nifi-deploy-gitops

Runs the NiFi deploy script (`deploy.sh`) as a **Kubernetes Job, managed by Argo CD**, for the Black and Red
NiFi instances. The script lives in Git as a plain `.sh` file, and **each flow file gets its own ConfigMap**.

```
nifi-deploy-gitops/
├── base/
│   ├── kustomization.yaml        script → ConfigMap (nifi-deploy-script)
│   ├── job.yaml                  the Job (Argo CD PostSync hook)
│   └── scripts/deploy.sh         the deploy script
├── overlays/
│   ├── black/
│   │   ├── kustomization.yaml    namespace, image, env ConfigMap
│   │   ├── deploy.env            NIFI_URL, REGISTRY_URL, TARGET_RPG_URL, REGISTRY_ID, REGISTRY_BUCKET_ID, START_FLOW
│   │   ├── params.env            PARAM_<name>=<value> overrides
│   │   └── flows/                *.json flow files + generated kustomization.yaml (one ConfigMap per file)
│   └── red/                      same layout
├── argocd/
│   ├── nifi-deploy-black.yaml    Argo CD Application → Black cluster
│   └── nifi-deploy-red.yaml      Argo CD Application → Red cluster
├── tools/sync-flows.sh           regenerates overlays/<env>/flows/kustomization.yaml
└── image/Dockerfile              optional runner image (bash + curl + jq)
```

## How a deploy happens

1. You change something in Git (a flow file, `params.env`, `deploy.sh`…) and merge.
2. Argo CD syncs the overlay. It applies the ConfigMaps: the script, one per flow, and the env. Each has a
   content hash in its name, so a change always produces a new ConfigMap.
3. After they are healthy, the **PostSync hook** runs the `nifi-deploy` Job. Its pod mounts:
   - `/scripts/deploy.sh` from the script ConfigMap
   - `/flows/*.json`, with every flow ConfigMap projected into one folder
   - the env ConfigMap plus the credentials Secret as environment variables
4. `deploy.sh` runs exactly as before: it skips unchanged flows (hash), imports or updates the rest,
   enables controller services, applies parameters and sets the run state.
5. The Job and its pod log stay until the next sync replaces them (`BeforeHookCreation`).

Watch a run: `kubectl -n hfc2-nifi logs -f job/nifi-deploy` (or the Job's Logs tab in Argo CD).

## Add, change or remove a flow

```bash
cp ~/exports/my-flow.json overlays/black/flows/
tools/sync-flows.sh overlays/black      # rewrites overlays/black/flows/kustomization.yaml
git add overlays/black/flows && git commit -m "black: add my-flow" && git push
```

`sync-flows.sh` checks each file. It must be valid JSON, under ~1 MiB (the ConfigMap limit) and have a safe
file name. It then writes one `configMapGenerator` entry and one projected-volume source per file. Never edit
the generated `flows/kustomization.yaml` by hand.

A flow larger than 1 MiB can't go in a ConfigMap. Bake it into an image, or use a PVC, for that flow.

## Configure an environment

- **`deploy.env`**: connection settings. `REGISTRY_ID` is the NiFi **Registry Client** id (it must be a
  *NiFi Registry* client, not a Git/Azure DevOps one). `REGISTRY_BUCKET_ID` is the bucket id; the wizard shows
  it when it creates a bucket.
- **`params.env`**: `PARAM_<name>=<value>`. See the notes below; **at least one PARAM_ line is required**.
- **Credentials**: the Job reads `username` / `password` from the existing Secret `nifi-single-user` in the
  namespace. Nothing secret is stored in Git. If your Secret has another name or other keys, patch `job.yaml`
  in the overlay.
- **Image**: set `images:` in the overlay's `kustomization.yaml` to an image that has `bash`, `curl` and `jq`
  (`image/Dockerfile` builds a minimal one).

## Argo CD setup

1. Push this repo to your Git server and register it in Argo CD.
2. Edit `repoURL`, `destination.server` (each cluster as Argo CD knows it) and `project` in `argocd/*.yaml`.
3. `kubectl apply -n argocd -f argocd/` and sync from the UI or `argocd app sync nifi-deploy-black`.

The Applications start in **manual sync**. Every sync runs the Job, so enable `automated` only once you're
happy, and keep `selfHeal: false`. `ServerSideApply=true` is needed because flow ConfigMaps can be larger
than the 256 KB annotation limit of client-side apply.

## About deploy.sh: please read before the first run

`base/scripts/deploy.sh` was **transcribed from a screen recording** of the original script. Diff it against
your original, or simply replace it with the original file. These lines were not readable in the video and
are marked `VERIFY`:

| Where | What to check |
|---|---|
| `build_param_json` | `gsub("_", ".", key)`: does `_` become `.` or `-` in parameter names? |
| `calculate_hash` | `LOCAL_HASH="${FLOW_HASH}_${PARAM_HASH}"`: separator `_` or `.`? |
| `enable_controller_services` | the ends of the four `TOTAL/ENABLED/DISABLED/ENABLING` lines were off-screen |

Behaviour of the script as it stands (kept unchanged on purpose, but worth knowing):

1. **No `PARAM_` variables means a silent failure.** `env | grep '^PARAM_'` finds nothing, and with
   `set -euo pipefail` the script exits with code 1 inside `build_param_json`, without a message. Tested.
2. **`inject_parameters` output is not used.** It writes `/tmp/flow.json`, but `FLOW_FILE` still points at the
   `prepare_flow` output. The PARAM_ overrides therefore don't change what is uploaded or hashed. They are
   applied later by `update_parameter_contexts`.
3. **`update_parameter_contexts` touches every parameter context in NiFi** that has a matching parameter
   name, not only the flows being deployed.
4. **`log_error` is called** (on a failed version update) but only `log_info`/`log_warn` are defined. The
   script still exits, but the message is lost.
5. **The curl calls have no `-f`**, so an HTTP error from NiFi or the Registry doesn't stop the script by itself.
6. **Living alongside the Flow Deployer wizard:** this script stores `flow-hash:<flow>_<params>` in the
   Registry, while the wizard stores `sha256(file)`. Whichever deployed last "wins". The other then sees the
   flow as changed and uploads one extra version. That's harmless, but expect it if you use both.

Happy to fix any of these. They're left as-is so the Job behaves exactly like your current script.
