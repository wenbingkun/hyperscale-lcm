#!/bin/bash
# dev-up.sh - 启动开发环境

set -e

echo "🚀 Starting Hyperscale LCM development environment..."

# 启动基础设施
echo "📦 Starting infrastructure (PostgreSQL, Redis, Kafka, Jaeger)..."
docker-compose up -d postgres redis kafka jaeger

# 等待服务就绪
echo "⏳ Waiting for services to be ready..."
sleep 5

# 检查服务状态
echo "✅ Checking service status..."
docker-compose ps

echo ""
echo "🎉 Development environment is ready!"
echo ""
echo "📋 Available services:"
echo "  - PostgreSQL: localhost:5432"
echo "  - Redis:      localhost:6379"
echo "  - Kafka:      localhost:9092"
echo "  - Jaeger UI:  http://localhost:16686"
echo ""
echo "🔧 To start Core service:"
echo "  cd core && ./gradlew quarkusDev"
echo ""
echo "📊 To stop environment:"
echo "  ./scripts/dev-down.sh"
