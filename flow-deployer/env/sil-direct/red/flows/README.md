Copy the current RED flow files here (same names as in env/sil-direct/red/flows of the NiFi app):

    Circuit-Manager-version-1.json
    Property-Service-Publisher-version-1.json

kustomization.yaml in this folder already lists them. After adding/removing/renaming a flow file run:

    flow-deployer/tools/sync-flows.sh flow-deployer/env/sil-direct/red
