# NiFi Control Plane UI

## Setup
npm install
npm run dev

## Configure
Edit .env:
- VITE_NIFI_URL
- VITE_REGISTRY_URL
- VITE_REGISTRY_BUCKET

## Features
- Dashboard with status badges + start/stop
- Registry with versions
- Upload + Monaco diff + hash compare
- Parameter contexts viewer
- Login (NiFi token)

## Notes
- For deploy trigger, set VITE_DEPLOY_URL and implement an endpoint.
