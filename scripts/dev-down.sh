#!/bin/bash
# dev-down.sh - 关闭开发环境

set -e

echo "🛑 Stopping Hyperscale LCM development environment..."

# 停止所有服务
docker-compose down

echo "✅ Development environment stopped."
echo ""
echo "💡 To remove volumes (data):"
echo "  docker-compose down -v"
