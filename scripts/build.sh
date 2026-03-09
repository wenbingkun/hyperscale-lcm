#!/bin/bash
# build.sh - 构建所有服务镜像

set -e

echo "🔨 Building Hyperscale LCM services..."

# 获取版本和命名空间
VERSION=${1:-latest}
NAMESPACE=${DOCKER_NAMESPACE:-example}
echo "📦 Version: $VERSION"
echo "📦 Namespace: $NAMESPACE"

# 构建 Core 服务
echo ""
echo "🏗️  Building Core service..."
docker build -t $NAMESPACE/lcm-core:$VERSION ./core

# 构建 Satellite 服务
echo ""
echo "🏗️  Building Satellite service..."
docker build -t $NAMESPACE/lcm-satellite:$VERSION ./satellite

# 构建 Frontend
echo ""
echo "🏗️  Building Frontend..."
docker build -t $NAMESPACE/lcm-frontend:$VERSION ./frontend

echo ""
echo "✅ Build complete!"
echo ""
echo "📋 Built images:"
docker images | grep "$NAMESPACE/lcm-"

echo ""
echo "🚀 To run with docker-compose:"
echo "  DOCKER_NAMESPACE=$NAMESPACE DOCKER_TAG=$VERSION docker-compose -f docker-compose.prod.yml up -d"
