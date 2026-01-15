#!/bin/bash
# build.sh - 构建所有服务镜像

set -e

echo "🔨 Building Hyperscale LCM services..."

# 获取版本
VERSION=${1:-latest}
echo "📦 Version: $VERSION"

# 构建 Core 服务
echo ""
echo "🏗️  Building Core service..."
docker build -t hyperscale-lcm/core:$VERSION ./core

# 构建 Satellite 服务
echo ""
echo "🏗️  Building Satellite service..."
docker build -t hyperscale-lcm/satellite:$VERSION ./satellite

# 构建 Dashboard（可选）
if [ -d "dashboard" ]; then
  echo ""
  echo "🏗️  Building Dashboard..."
  cd dashboard
  npm install
  npm run build
  cd ..
fi

echo ""
echo "✅ Build complete!"
echo ""
echo "📋 Built images:"
docker images | grep hyperscale-lcm

echo ""
echo "🚀 To run with docker-compose:"
echo "  docker-compose up -d"
