NiFi 2.7 Secure GitOps Starter Repository

This repository provides a GitOps-ready deployment of Apache NiFi 2.7 with a secured Zookeeper cluster using Kubernetes and Kustomize. It is designed for ArgoCD-based deployment.

⸻

Features
•	NiFi 2.7 deployment with HTTPS and TLS support
•	Zookeeper cluster for NiFi coordination
•	All images controlled from root kustomization.yaml
•	NiFi replicas configurable from root kustomization
•	Init container ensures NiFi waits for Zookeeper to be ready
•	No hardcoded volume mapping (optional PVCs can be added via overlays)
•	Fully GitOps-ready for ArgoCD

⸻

Directory Structure

nifi-gitops/
├── README.md
├── kustomization.yaml      # Root kustomization (images, NiFi replicas)
├── nifi/
│   └── base/              # NiFi manifests
├── zookeeper/
│   └── base/              # Zookeeper manifests
└── argocd/
└── app.yaml           # ArgoCD application


⸻

How to Deploy

1️⃣ Apply locally using kubectl

kubectl apply -k .

This will deploy Zookeeper first, then NiFi, using the init container to wait for readiness.

2️⃣ Deploy via ArgoCD
1.	Update argocd/app.yaml with your repo URL:

repoURL: https://github.com/<your-org>/nifi-gitops.git

	2.	Apply ArgoCD application:

kubectl apply -f argocd/app.yaml

ArgoCD will sync the root kustomization, deploying both Zookeeper and NiFi.

⸻

Configuration

Root kustomization (kustomization.yaml)
•	Images:
•	NiFi image/tag
•	Zookeeper image/tag
•	NiFi replicas

NiFi Base
•	Contains StatefulSet, Service, ConfigMap, Secret, and TLS Secret
•	Init container waits for Zookeeper

Zookeeper Base
•	Contains StatefulSet, Service, ConfigMap, and TLS Secret
•	Replicas remain defined in base

⸻

Notes
•	For production, replace secrets with SealedSecrets or Vault managed secrets
•	Optional: Add volume mapping and PVCs for NiFi data
•	Recommended to use overlays for dev/prod environment customization

⸻

This setup provides a clean, centralized, and fully configurable deployment of NiFi and Zookeeper using GitOps principles.
