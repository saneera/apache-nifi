# flow-deployer — NiFi flow deployment as its own Argo CD app

Drop these two folders into the root of **HFC2-Deploy**, next to `env/` and `argocd/`:

```
HFC2-Deploy/
├── flow-deployer/
│   ├── base/
│   │   ├── kustomization.yaml          Job + script ConfigMap + nifi-toolkit image (Nexus)
│   │   ├── deployer-job.yaml           PostSync hook Job (same behaviour as your current one)
│   │   └── deployer.sh                 the updated deploy script
│   ├── env/sil-direct/
│   │   ├── red/
│   │   │   ├── kustomization.yaml      namespace inspire-silrelease, namePrefix nifi-red-
│   │   │   ├── env-deploy.properties   ← copy your values (URLs, ids, PARAM_*, START_FLOW)
│   │   │   ├── env-auth.properties     ← copy the 2 credential values (becomes a Secret)
│   │   │   └── flows/                  ← copy the *.json flow files; kustomization.yaml is generated
│   │   └── black/                      same layout
│   └── tools/sync-flows.sh             one ConfigMap per flow file
└── argocd/
    ├── flow-deployer-sil-direct-red.yaml
    └── flow-deployer-sil-direct-black.yaml
```

## What it creates (RED)

| Resource | Name |
|---|---|
| Job (PostSync hook) | `nifi-red-flow-deploy-job` |
| Script ConfigMap | `nifi-red-flow-deploy-script` (from `base/deployer.sh`) |
| Settings ConfigMap | `nifi-red-flow-deploy-env` (from `env-deploy.properties`) |
| Credentials **Secret** | `nifi-red-flow-deploy-auth` (from `env-auth.properties`) |
| One ConfigMap per flow | `nifi-red-flow-deploy-flow-circuit-manager`, `nifi-red-flow-deploy-flow-property-service-publisher` |

The flow ConfigMaps are named per flow **without the version**, so `Circuit-Manager-version-2.json` replaces
version 1 under the same name. All of them are projected into `/flows`, so the pod sees exactly the same files
as today. None of the names clash with the current deployer resources in the NiFi app.

## Set it up (RED; BLACK is the same)

```bash
# 1. your files into the new app
# (base/deployer.sh is already the updated script; nothing to copy)
cp env/sil-direct/red/flows/*.json                    flow-deployer/env/sil-direct/red/flows/
#    env-deploy.properties: copy your values over the CHANGE-ME lines
#    env-auth.properties:   copy SINGLE_USER_CREDENTIALS_USERNAME / _PASSWORD only

# 2. generate the per-flow ConfigMaps
flow-deployer/tools/sync-flows.sh flow-deployer/env/sil-direct/red

# 3. check it builds
kubectl kustomize flow-deployer/env/sil-direct/red

# 4. Argo CD app (edit repoURL / destination.server first)
kubectl apply -n argocd -f argocd/flow-deployer-sil-direct-red.yaml
```

## Take the deployer out of the NiFi app

So that only the new app runs deployments, remove from `env/sil-direct/red/kustomization.yaml`:

```yaml
resources:
  - deployer-job.yaml                        # remove

configMapGenerator:
  - name: nifi-red-flow-deployer-config      # remove (env-deploy.properties + env-auth.properties)
  - name: nifi-red-deploy-script             # remove (deployer.sh)
  - name: nifi-red-flow-config               # remove (flows/*.json)
```

Before removing them, check that `nifi-statefulset.yaml` and the base manifests don't reference those ConfigMaps
(they are deployer-only in what I saw, but `env-auth.properties` also contains `NIFI_SENSITIVE_PROPS_KEY`).
Then sync the NiFi app (with prune) and the old Job and ConfigMaps go away. Order: create and test the new app
first, then remove the old deployer. The names don't clash, so both can exist for a while; just don't sync
both at the same time.

## Day to day

- **New or changed flow**: put the `.json` in `env/sil-direct/<side>/flows/`, run `tools/sync-flows.sh`, commit,
  then sync `flow-deployer-sil-direct-<side>`.
- **Changed parameters or URLs**: edit `env-deploy.properties`, commit, sync.
- **Logs**: `kubectl -n inspire-silrelease logs -f job/nifi-red-flow-deploy-job`, or the Job in the Argo CD UI.
- Syncing the NiFi app no longer deploys flows, and syncing this app never restarts NiFi.

## Notes

- `base/deployer.sh` is the **updated** script (fixes listed at its top). If you'd rather start with your exact
  current script, copy yours over it; the overlays work with either.
- `activeDeadlineSeconds: 1800`: `wait_for_nifi` retries forever, so the Job now gives up after 30 min if NiFi is down.
- `GIT_COMMIT`: your old Job had a `GIT_COMMIT` env (folded in the editor). Add it back in
  `base/deployer-job.yaml` if `deployer.sh` uses it.
- Credentials are now a Secret instead of a ConfigMap. They are still in Git through `env-auth.properties`; moving
  them to Sealed Secrets or External Secrets would take them out of Git.
- If a flow file grows past ~256 KB, `ServerSideApply=true` (already set in the Applications) keeps Argo CD
  syncing it.
