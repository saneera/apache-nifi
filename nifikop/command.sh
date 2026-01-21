helm install nifikop \
    oci://ghcr.io/konpyutaika/helm-charts/nifikop \
    --namespace=nifi \
    --version 1.16.0 \
    --set image.tag=v1.16.0-release \
    --set resources.requests.memory=256Mi \
    --set resources.requests.cpu=250m \
    --set resources.limits.memory=256Mi \
    --set resources.limits.cpu=250m \
    --set namespaces={"nifi"}
