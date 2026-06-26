# NiFi Kustomize Deployment

Two NiFi instances across two Kubernetes clusters with mTLS site-to-site communication.

## Structure

```
nifi-kustomize/
├── base/                          # shared resources
│   ├── kustomization.yaml
│   ├── namespace.yaml
│   ├── rbac.yaml                  # ServiceAccount + Role for cert job
│   ├── password-secret.yaml       # keystore password (override in overlay)
│   ├── cert-generation-job.yaml   # auto cert generation with skip/expiry logic
│   ├── statefulset.yaml
│   ├── service-headless.yaml
│   ├── service-nodeport.yaml
│   ├── configmap.yaml
│   └── pv-pvc.yaml
└── overlays/
    ├── cluster-a/                 # NiFi Red (172.27.3.23)
    │   ├── kustomization.yaml
    │   └── patches/
    │       ├── patch-statefulset.yaml
    │       ├── patch-configmap.yaml
    │       ├── patch-job.yaml
    │       └── patch-password-secret.yaml
    └── cluster-b/                 # NiFi Black (172.27.3.12)
        ├── kustomization.yaml
        └── patches/
            ├── patch-statefulset.yaml
            ├── patch-configmap.yaml
            ├── patch-job.yaml
            └── patch-password-secret.yaml
```

## Before You Deploy

Update these values in each overlay:

### cluster-a
| File | Field | Value |
|------|-------|-------|
| patches/patch-job.yaml | image | your internal registry |
| patches/patch-statefulset.yaml | kubernetes.io/hostname | actual node name |
| patches/patch-password-secret.yaml | password | your keystore password |

### cluster-b
Same as above but for cluster-b node.

## ArgoCD Sync Waves

Resources deploy in order:

```
Wave 0 → RBAC + password secret
Wave 1 → cert-generation Job (skips if certs valid, regenerates if expired/missing)
Wave 2 → NiFi StatefulSet + Services + PVCs
```

## Cert Generation Logic

The Job runs on every ArgoCD sync but is smart:

- Secrets **missing** → generate certs, create secrets
- Secrets **exist + valid > 30 days** → skip everything
- Secrets **exist + expiring < 30 days** → delete old secrets, regenerate
- After regeneration → NiFi StatefulSets are automatically restarted

## Deploy

```bash
# Cluster A (NiFi Red)
kubectl apply -k overlays/cluster-a/ --context=cluster-a

# Cluster B (NiFi Black)
kubectl apply -k overlays/cluster-b/ --context=cluster-b
```

## Verify

```bash
# Check job completed
kubectl get jobs -n inspire-silrelease

# Check secrets created
kubectl get secrets -n inspire-silrelease | grep nifi

# Check NiFi pod running
kubectl get pods -n inspire-silrelease

# Check cert contents
kubectl get secret nifi-red-certs -n inspire-silrelease \
  -o jsonpath='{.data.keystore\.p12}' | base64 -d > /tmp/ks.p12
keytool -list -v -keystore /tmp/ks.p12 -storetype PKCS12 -storepass <password>
```

## Data Persistence

NiFi data is stored on the node's local disk via hostPath:

| Repository | hostPath | PVC |
|------------|----------|-----|
| FlowFile | /data/nifi/flowfiles | nifi-flowfiles-pvc |
| Content | /data/nifi/content | nifi-content-pvc |
| Provenance | /data/nifi/provenance | nifi-provenance-pvc |
| Database | /data/nifi/database | nifi-database-pvc |

NiFi is pinned to a specific node via nodeAffinity to ensure data persists across pod restarts.
