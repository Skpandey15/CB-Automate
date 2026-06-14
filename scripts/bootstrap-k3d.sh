#!/usr/bin/env bash
set -euo pipefail

CLUSTER_NAME="compliance-buddy"
REGISTRY_NAME="k3d-cb-registry"
REGISTRY_PORT="5050"
NAMESPACE="cb-system"

log() { echo "[CB] $(date '+%Y-%m-%d %H:%M:%S') $*"; }

# 1. Prerequisites check
for cmd in k3d kubectl helm docker; do
    command -v "$cmd" &>/dev/null || { echo "ERROR: $cmd not found"; exit 1; }
done
log "Prerequisites OK"

# 2. Create local registry
if k3d registry list | grep -q "$REGISTRY_NAME"; then
    log "Registry $REGISTRY_NAME already exists"
else
    k3d registry create "$REGISTRY_NAME" --port "$REGISTRY_PORT"
    log "Created registry $REGISTRY_NAME:$REGISTRY_PORT"
fi

# 3. Create k3d cluster
if k3d cluster list | grep -q "$CLUSTER_NAME"; then
    log "Cluster $CLUSTER_NAME already exists"
else
    k3d cluster create "$CLUSTER_NAME" \
        --agents 2 \
        --registry-use "${REGISTRY_NAME}:${REGISTRY_PORT}" \
        --port "8080:80@loadbalancer" \
        --port "8443:443@loadbalancer" \
        --k3s-arg "--disable=traefik@server:0" \
        --wait
    log "Created cluster $CLUSTER_NAME"
fi

# 4. Configure kubectl
k3d kubeconfig merge "$CLUSTER_NAME" --kubeconfig-merge-default
kubectl config use-context "k3d-$CLUSTER_NAME"
log "kubectl configured"

# 5. Install NGINX Ingress
helm repo add ingress-nginx https://kubernetes.github.io/ingress-nginx --force-update
helm upgrade --install ingress-nginx ingress-nginx/ingress-nginx \
    --namespace ingress-nginx --create-namespace \
    --set controller.service.type=LoadBalancer \
    --wait --timeout 3m
log "NGINX Ingress installed"

# 6. Create namespace
kubectl apply -f k8s/base/namespace.yaml
log "Namespace $NAMESPACE created"

# 7. Build and push Docker images
ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"

build_and_push() {
    local module=$1
    local port=$2
    log "Building $module..."
    docker build -t "${REGISTRY_NAME}:${REGISTRY_PORT}/${module}:latest" \
        -f "$ROOT_DIR/${module}/Dockerfile" "$ROOT_DIR"
    docker push "${REGISTRY_NAME}:${REGISTRY_PORT}/${module}:latest"
    log "Pushed ${module}"
}

# Build Gradle fat jars first
log "Building Gradle modules..."
cd "$ROOT_DIR"
./gradlew :cb-scanner:bootJar :cb-agent:bootJar :cb-patcher:bootJar \
          :cb-pr:bootJar :cb-notifier:bootJar :cb-api:bootJar \
          --no-daemon --parallel
log "Gradle build complete"
cd "$ROOT_DIR"

build_and_push cb-scanner 8081
build_and_push cb-agent 8082
build_and_push cb-patcher 8083
build_and_push cb-pr 8084
build_and_push cb-notifier 8085
build_and_push cb-api 8080

log "Building Python embedder..."
docker build -t "${REGISTRY_NAME}:${REGISTRY_PORT}/cb-python-embedder:latest" \
    "$ROOT_DIR/cb-python-embedder"
docker push "${REGISTRY_NAME}:${REGISTRY_PORT}/cb-python-embedder:latest"
log "Python embedder pushed"

# 8. Apply secrets (user must populate secrets.yaml first)
if [ -f "$ROOT_DIR/k8s/base/secrets.yaml" ]; then
    kubectl apply -f "$ROOT_DIR/k8s/base/secrets.yaml"
    log "Secrets applied"
else
    log "WARNING: k8s/base/secrets.yaml not found."
    log "Copy k8s/base/secrets-template.yaml to k8s/base/secrets.yaml, fill in values, and run:"
    log "  kubectl apply -f k8s/base/secrets.yaml"
fi

# 9. Apply all manifests via kustomize
kubectl apply -k "$ROOT_DIR/k8s/base/"
log "Manifests applied"

# 10. Wait for rollout
log "Waiting for deployments to be ready..."
for dep in cb-mongodb cb-python-embedder cb-api cb-scanner cb-agent cb-patcher cb-pr cb-notifier; do
    kubectl rollout status deployment/"$dep" -n "$NAMESPACE" --timeout=120s || \
        log "WARNING: $dep not ready yet"
done

# 11. Add local hosts entry hint
log ""
log "========================================================="
log "Compliance Buddy is running!"
log ""
log "Add to /etc/hosts (or C:\\Windows\\System32\\drivers\\etc\\hosts):"
log "  127.0.0.1  compliance-buddy.local"
log ""
log "API:        http://compliance-buddy.local/api/v1"
log "Swagger UI: http://compliance-buddy.local/swagger-ui/index.html"
log "MongoDB:    kubectl port-forward svc/cb-mongodb 27017:27017 -n cb-system"
log ""
log "Default API key: dev-key-change-in-prod (set CB_API_KEYS secret)"
log "========================================================="
