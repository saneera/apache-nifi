# NiFi 2.7 Secure GitOps Starter Repo

This repo deploys NiFi 2.7 with Zookeeper using Kustomize and ArgoCD. NiFi replicas and images are managed from the root kustomization, and NiFi waits for Zookeeper before starting.
