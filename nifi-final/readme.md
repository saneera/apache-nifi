# NiFi Flow Deployment (GitOps with Argo CD)

## Overview

This project provides automated Apache NiFi flow deployment using:
•	Kubernetes Job
•	Argo CD (GitOps)
•	Kustomize
•	NiFi REST API

The deployment is:
•	Idempotent
•	Hash-based (deploys only when flow changes)
•	Safe (stops processors before replacement)
•	Environment-aware (injects Remote Process Group URLs)

⸻

## Architecture

```
Git Push
↓
Argo CD Sync
↓
Kubernetes Job (nifi-flow-deployer)
↓
NiFi REST API
↓
Flow Created / Updated

```
## Repository Structure

```
nifi-deployment/
│
├── deploy/
│   ├── kustomization.yaml
│   ├── job.yaml
│   └── flows/
│       ├── flow1.json
│       └── flow2.json
```

## Kustomize Configuration

Example kustomization.yaml:

```
configMapGenerator:
  - name: nifi-flow-files
    files:
      - flows/flow1.json
      - flows/flow2.json
```

This generates a ConfigMap containing all flow JSON files.

The Kubernetes Job mounts it to:

```
/flows
```

So inside the container:
```
/flows/flow1.json
/flows/flow2.json
```

## Deployment Workflow

### Step 1 – Add or Update Flow
1.	Add JSON file to:

``
deploy/flows/
``
2.	Update kustomization.yaml:

```
files:
  - flows/flow1.json
  - flows/flow2.json
  - flows/new-flow.json
```

3.	Commit and push:

```
git add .
git commit -m "Add new NiFi flow"
git push

```

### Step 2 – Sync Argo CD
1.	Open Argo CD
2.	Select NiFi deployment app
3.	Click Sync

Argo CD will:
•	Update ConfigMap (hash changes)
•	Recreate Job
•	Run deployment script


## Deployment Logic

For each /flows/*.json file:

1. Flow Does Not Exist

If process group is not found in NiFi:

→ Upload new flow

2. Flow Exists

Script performs:
1.	Calculate SHA256 hash of JSON file
2.	Read stored hash from process group comments
3.	Compare hashes

If Hash Matches

```
No changes detected. Skipping.
```

Flow is not redeployed.

If Hash Is Different

Safe replacement process:
1.	Stop processors
2.	Wait until runningCount = 0
3.	Disable controller services
4.	Delete old process group
5.	Upload new flow
6.	Store new hash in comments

Hash-Based Change Detection

After deployment, script stores:
```
flow-hash=<sha256>
```

In process group comments.

Next deployment compares against this value.


## Remote Process Group URL Injection

Before upload, script updates:

```
targetUris
```

Using environment variable:

```
TARGET_RPG_URL
```

This allows environment-specific URLs without modifying the flow manually

## Environment Variables Required

The Job must define:

```
NIFI_URL
USERNAME
PASSWORD
TARGET_RPG_URL
```

Examples:

```
env:
  - name: NIFI_URL
    value: "https://nifi-service:8443"
```

##Safe Replacement Process

When changes are detected:

Step
1.	Stop processors
2.	Wait until runningCount = 0
3.	Disable controller services
4.	Delete old process group
5.	Upload new flow
6.	Store new hash in comments

This prevents runtime failures and dependency issues.

## Handling No Flow Files

If /flows directory is empty:

```
No flow files found. Nothing to deploy.
```

Job exits successfully.


##Re-Running Deployment

Deployment triggers when:
•	Flow JSON changes
•	New flow added
•	Remote Process Group URL changes
•	ConfigMap hash changes

Process:
1.	Commit
2.	Push
3.	Argo CD Sync
4.	Job runs automatically

## Stopping a Stuck Job

If Argo CD shows:

```
waiting for completion of hook batch/job/nifi-flow-deployer
```

You can delete manually:

```
kubectl delete job nifi-flow-deployer -n <namespace>
```

Then re-sync



=========


Story 1 – Implement METAR Message Parser & Decoder

Title:
Implement METAR Weather Message Parsing and Decoding

Story Type:
User Story

Description:
As a system user,
I want the application to parse and decode METAR messages,
So that I can view structured, human-readable current weather conditions for an airport.

The system must accept raw METAR strings (ICAO standard format) and convert them into structured data fields such as wind, visibility, cloud cover, temperature, and pressure.

Example airport: Adelaide Airport

Example input:

```
METAR YPAD 020630Z 18012KT 9999 FEW020 25/14 Q1015
```


Acceptance Criteria
•	✅ System accepts a raw METAR string as input
•	✅ Extracts and validates:
•	Report type (METAR / SPECI)
•	ICAO station code
•	Observation time (UTC)
•	Wind direction, speed, gust
•	Visibility
•	Weather phenomena (if present)
•	Cloud layers
•	Temperature and dew point
•	QNH pressure
•	✅ Converts output into structured JSON format
•	✅ Handles invalid or malformed METAR messages gracefully
•	✅ Unit tests cover at least 80% parsing logic


Technical Notes
•	Follow ICAO METAR standard
•	Design parser to be reusable for mobile and web
•	Consider regex-based token extraction
•	Future enhancement: integrate live aviation weather API


====================


Story 2 – Implement TAF Message Parser & Forecast Decoder

Title:
Implement TAF Forecast Message Parsing and Decoding

Story Type:
User Story

Description:
As a system user,
I want the application to parse and decode TAF forecast messages,
So that I can view structured forecast weather data including change groups.

The system must interpret forecast periods and conditional changes such as TEMPO, FM, BECMG, and PROB.

Example airport: Adelaide Airport

Example input:

```
TAF YPAD 020500Z 0206/0312 18015KT 9999 SCT020 
TEMPO 0210/0214 4000 TSRA BKN015 
FM021600 22020G30KT 9999 BKN025
```

Acceptance Criteria
•	✅ System accepts raw TAF string
•	✅ Extracts:
•	Report type
•	ICAO station
•	Issue time
•	Valid period
•	Base forecast conditions
•	✅ Correctly parses change groups:
•	TEMPO
•	FM
•	BECMG
•	PROB (30/40)
•	✅ Structures forecast into timeline-based JSON format
•	✅ Handles multiple change groups
•	✅ Includes error handling for malformed input


Technical Notes
•	Use modular parsing strategy (base forecast + change groups)
•	Support multi-line TAF input
•	Design output suitable for UI timeline visualization
