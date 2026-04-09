# Openfire Deployment Design (Wiki)

## Overview

This deployment provisions an XMPP-based chat system using:
•	Openfire → XMPP server
•	MySQL → persistent database
•	Kubernetes (Kustomize + ArgoCD) → deployment orchestration


## Repository Structure

```
openfire/
├── argocd-files/
│   ├── dev-1/
│   └── sil-direct/
├── base-manifests/
│   ├── openfire-deployment.yaml
│   ├── openfire-service.yaml
│   ├── openfire-mysql.yaml
│   ├── openfire-mysql-service.yaml
│   ├── openfire-mysql-secret.yaml
│   ├── openfire-mysql-pv.yaml
│   ├── openfire-mysql-pvc.yaml
│   ├── openfire-conf-pv.yaml
│   ├── openfire-conf-pvc.yaml
│   ├── openfire-cm.yaml
│   └── kustomization.yaml
├── env/
│   ├── dev-1/
│   └── sil-direct/
```

Components

1. Openfire Deployment
   •	Image: openfire-image
   •	Ports:
   •	5222 → XMPP client
   •	7070 → HTTP binding
   •	9090 → Admin console
   •	Persistent storage:

```aiignore
/var/lib/openfire
```

MySQL Deployment
•	Image: mysql:8
•	Port: 3306
•	Credentials via Kubernetes Secret


Services

Openfire Service
•	Exposes:
•	5222 (XMPP)
•	7070 (HTTP)
•	9090 (Admin)

MySQL Service
•	NodePort: 30306
•	Internal access:

```aiignore
openfire-mysql:3306
```

4. Persistent Storage

Component
PVC
Mount Path
Openfire
openfire-conf-pvc
/var/lib/openfire
MySQL
openfire-mysql-pvc
/var/lib/mysql


## Secrets

```aiignore

MYSQL_ROOT_PASSWORD
MYSQL_DATABASE
MYSQL_USER
MYSQL_PASSWORD
```


Stored in:
```aiignore
openfire-mysql-secret.yaml
```

## Deployment Flow

Step 1: Apply Base Manifests

Handled via:

```

kustomization.yaml
```
Includes:
•	Storage (PV/PVC)
•	MySQL
•	Openfire
•	Services
•	ConfigMap


Step 2: Environment Overlay

Example:

```aiignore

env/dev-1/kustomization.yaml
```

Adds:
•	Image overrides
•	Namespace
•	Affinity rules


Step 3: ArgoCD

```aiignore
argocd-openfire-application.yaml

```

Deploys automatically from Git

## Deployment Architecture (Kubernetes Only)

```mermaid
flowchart TB

    subgraph Kubernetes Cluster

    %% Openfire
        subgraph Openfire
            OF_DEP[Deployment: Openfire]
            OF_POD[Openfire Pod]
            OF_DATA[(PVC: /var/lib/openfire)]

            OF_DEP --> OF_POD
            OF_POD --> OF_DATA
        end

    %% MySQL
        subgraph MySQL
            DB_DEP[Deployment: MySQL]
            DB_POD[MySQL Pod]
            DB_DATA[(PVC: /var/lib/mysql)]

            DB_DEP --> DB_POD
            DB_POD --> DB_DATA
        end

    %% Services
        subgraph Services
            OF_SVC[Openfire Service\n5222 / 7070 / 9090]
            DB_SVC[MySQL Service\n3306]
        end

    %% Secrets
        subgraph Secrets
            DB_SECRET[MySQL Secret]
        end

    end

%% Connections
    OF_SVC --> OF_POD
    OF_POD --> DB_SVC
    DB_SVC --> DB_POD

    DB_SECRET --> DB_POD

```


## Deployment + Storage View (More Infra Focused)

```mermaid

flowchart LR

%% -------------------
%% Openfire
%% -------------------
    subgraph Openfire Deployment
        OF_DEP[Deployment: Openfire]
        OF_RS[ReplicaSet]
        OF_POD[Pod: Openfire]

        OF_DEP --> OF_RS
        OF_RS --> OF_POD
    end

%% Openfire Storage
    OF_PVC[(PVC: openfire-data\n/var/lib/openfire)]
    OF_POD --> OF_PVC

%% -------------------
%% MySQL
%% -------------------
    subgraph MySQL Deployment
        DB_DEP[Deployment: MySQL]
        DB_RS[ReplicaSet]
        DB_POD[Pod: MySQL]

        DB_DEP --> DB_RS
        DB_RS --> DB_POD
    end

%% MySQL Storage
    DB_PVC[(PVC: mysql-data\n/var/lib/mysql)]
    DB_POD --> DB_PVC

%% -------------------
%% Services
%% -------------------
    OF_SVC[Service: Openfire\n5222 / 7070 / 9090]
    DB_SVC[Service: MySQL\n3306]

    OF_SVC --> OF_POD
    OF_POD --> DB_SVC
    DB_SVC --> DB_POD

%% -------------------
%% Secrets
%% -------------------
    DB_SECRET[Secret: MySQL Credentials]
    DB_SECRET --> DB_POD
```


args:
- |
  echo "Parsing DB URL..."

  DB_HOST=$(echo $OPENFIRE_DB_URL | sed -E 's|jdbc:mysql://([^:/]+).*|\1|')
  DB_PORT=$(echo $OPENFIRE_DB_URL | sed -E 's|.*:([0-9]+)/.*|\1|')

  echo "Waiting for MySQL at $DB_HOST:$DB_PORT..."

  until nc -z $DB_HOST $DB_PORT; do
  echo "MySQL not ready, retrying..."
  sleep 4
  done

  echo "MySQL is ready!"
