# NiFi Flow Deployment Pipeline

This document describes the full deployment pipeline used to automatically deploy NiFi flows from Git into Apache NiFi using Kubernetes and NiFi Registry.

The pipeline connects the following components:

Git → ConfigMap → Kubernetes Job → NiFi Registry → NiFi

---

# Architecture Diagram

```mermaid
flowchart LR

A[Developer updates flow JSON] --> B[Git Repository]

B --> C[CI / GitOps Pipeline]

C --> D[ConfigMap created from flows directory]

D --> E[Kubernetes Job: NiFi Flow Deployer]

E --> F[NiFi Registry API]
E --> G[NiFi API]

F --> H[Create / Update Flow Version]
G --> I[Import / Update Process Group]
```

---

# Deployment Flow

```mermaid
sequenceDiagram

participant Dev as Developer
participant Git as Git Repository
participant CI as CI/CD Pipeline
participant K8s as Kubernetes Job
participant Reg as NiFi Registry
participant NiFi as NiFi

Dev->>Git: Commit flow.json
Git->>CI: Trigger deployment
CI->>K8s: Create ConfigMap + Run Job

K8s->>Reg: Check if flow exists

alt Flow does not exist
K8s->>Reg: Create flow
end

K8s->>K8s: Compare flow hash

alt Flow changed
K8s->>Reg: Create new version
end

K8s->>NiFi: Check Process Group

alt PG missing
K8s->>NiFi: Import flow
else PG exists
K8s->>NiFi: Update version if stale
end

NiFi-->>K8s: Deployment complete
```

---

# Kubernetes Job Overview

The deployment logic runs inside a Kubernetes Job which mounts a directory containing NiFi flow definitions.

Example directory mounted in the container:

```
/flows
  api-gateway.json
  ingestion.json
  analytics.json
```

Each JSON file represents a NiFi flow that will be deployed.

---

# ConfigMap Flow Mount

The flows directory is created from a Kubernetes ConfigMap.

Example configuration:

```yaml
apiVersion: batch/v1
kind: Job
metadata:
  name: nifi-flow-deployer
spec:
  template:
    spec:
      restartPolicy: Never
      containers:
      - name: deployer
        image: nifi-deployer:latest
        command: ["/scripts/deploy.sh"]
        volumeMounts:
        - name: flows
          mountPath: /flows

      volumes:
      - name: flows
        configMap:
          name: nifi-flows
```

---

# Flow Change Detection

To prevent unnecessary deployments, the pipeline calculates a hash of each flow JSON file.

Example:

```
jq -S '.' flow.json | sha256sum
```

This hash is stored inside the flow description in NiFi Registry:

```
flow-hash:abc123...
```

During the next deployment:

- If the hash matches → no new version is created
- If the hash differs → a new flow version is created

---

# Deployment Behaviour

| Scenario | Result |
|--------|--------|
| Flow does not exist | Flow created in Registry |
| Flow unchanged | Deployment skipped |
| Flow changed | New Registry version created |
| Process group missing | Flow imported into NiFi |
| Process group stale | NiFi upgraded to latest version |

---

# Benefits of This Pipeline

- Fully automated NiFi deployments
- Git-driven flow management
- Version control through NiFi Registry
- Idempotent deployments
- Compatible with Kubernetes and GitOps workflows

---

# Example Deployment Output

```
Scanning flows directory...

Deploying api-gateway.json
Flow changed → creating new version
Updating process group

Deploying ingestion.json
Flow unchanged → skipping

Deployment complete
```
