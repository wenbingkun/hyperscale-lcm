#!/usr/bin/env bash
# build.sh - 构建所有服务镜像

set -euo pipefail

usage() {
  cat <<'USAGE'
Usage:
  DOCKER_NAMESPACE=<dockerhub-user> scripts/build.sh [tag]

Arguments:
  tag                 Image tag to build. Defaults to DOCKER_TAG or v0.1.0.

Environment:
  DOCKER_NAMESPACE    Required image namespace.
  DOCKER_TAG          Optional default tag when no argument is provided.
  ALLOW_LATEST=true   Allow building latest for dev-only local experiments.

Examples:
  DOCKER_NAMESPACE=my-dockerhub-user scripts/build.sh v0.1.0
  DOCKER_NAMESPACE=demo DOCKER_TAG=v0.1.0 scripts/build.sh
USAGE
}

if [[ "${1:-}" == "-h" || "${1:-}" == "--help" ]]; then
  usage
  exit 0
fi

# 获取版本和命名空间
VERSION="${1:-${DOCKER_TAG:-v0.1.0}}"
NAMESPACE="${DOCKER_NAMESPACE:-}"

if [[ -z "$NAMESPACE" ]]; then
  echo "DOCKER_NAMESPACE is required." >&2
  usage >&2
  exit 2
fi

if [[ "$VERSION" == "latest" && "${ALLOW_LATEST:-false}" != "true" ]]; then
  echo "Refusing to build the mutable latest tag. Use a fixed release tag, or set ALLOW_LATEST=true for dev-only experiments." >&2
  exit 2
fi

echo "🔨 Building Hyperscale LCM services..."
echo "📦 Version: $VERSION"
echo "📦 Namespace: $NAMESPACE"

# 构建 Core 服务
echo ""
echo "🏗️  Building Core service..."
docker build -t "$NAMESPACE/lcm-core:$VERSION" ./core

# 构建 Satellite 服务
echo ""
echo "🏗️  Building Satellite service..."
docker build -t "$NAMESPACE/lcm-satellite:$VERSION" ./satellite

# 构建 Frontend
echo ""
echo "🏗️  Building Frontend..."
docker build -t "$NAMESPACE/lcm-frontend:$VERSION" ./frontend

echo ""
echo "✅ Build complete!"
echo ""
echo "📋 Built images:"
docker images --format '{{.Repository}}:{{.Tag}}' | grep -F "$NAMESPACE/lcm-"

echo ""
echo "🚀 To run with docker-compose:"
echo "  DOCKER_NAMESPACE=$NAMESPACE DOCKER_TAG=$VERSION docker-compose -f docker-compose.prod.yml up -d"
