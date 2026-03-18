#!/bin/bash
set -e

echo "🚀 Launching Hyperscale LCM..."

# Check requirements
command -v docker >/dev/null 2>&1 || { echo >&2 "Docker is required but not installed. Aborting."; exit 1; }
command -v java >/dev/null 2>&1 || { echo >&2 "Java is required but not installed. Aborting."; exit 1; }
command -v go >/dev/null 2>&1 || { echo >&2 "Go is required but not installed. Aborting."; exit 1; }
command -v npm >/dev/null 2>&1 || { echo >&2 "NPM is required but not installed. Aborting."; exit 1; }

# 1. Infrastructure
echo "📦 Starting Infrastructure (Docker)..."
# We only start the infra services
docker-compose up -d postgres redis kafka jaeger prometheus

# Wait a bit for DB to be ready
echo "⏳ Waiting for infrastructure to initialize..."
sleep 5

# 2. Core Service
echo "☕ Starting Core Service (Java/Quarkus)..."
# Resolve absolute path for certs to avoid any CWD issues
export CERTS_ABS_PATH=$(readlink -f certs)
echo "   Using Certs Path: $CERTS_ABS_PATH"

export GRPC_CERT_PATH="$CERTS_ABS_PATH/server.pem"
export GRPC_KEY_PATH="$CERTS_ABS_PATH/server-pkcs8.key"
export GRPC_TRUSTSTORE_PATH="$CERTS_ABS_PATH/truststore.jks"

cd core
if [ ! -x "./gradlew" ]; then
    chmod +x gradlew
fi
# Using nohup to keep it running in background
nohup ./gradlew quarkusDev > ../core.log 2>&1 &
CORE_PID=$!
echo "   Core Service PID: $CORE_PID (Logs: core.log)"
cd ..

# 3. Satellite Service
echo "🛰️ Starting Satellite Service (Go)..."
cd satellite
# go run compiles and runs.
export LCM_CORE_ADDR=localhost:8080
export LCM_CERTS_DIR="../certs"
nohup go run ./cmd/satellite > ../satellite.log 2>&1 &
SAT_PID=$!
echo "   Satellite Service PID: $SAT_PID (Logs: satellite.log)"

echo "🛰️ Starting Mock Satellites..."
for i in {1..4}; do
    LCM_MOCK_HOSTNAME="mock-node-0$i" LCM_CORE_ADDR=localhost:8080 LCM_CERTS_DIR="../certs" nohup go run ./cmd/satellite > ../satellite_mock_$i.log 2>&1 &
    MOCK_PID=$!
    echo "   Mock Satellite $i PID: $MOCK_PID"
done
cd ..

# 4. Frontend
echo "⚛️ Starting Frontend (React)..."
cd frontend
if [ ! -d "node_modules" ]; then
    echo "   Installing dependencies (this may take a while)..."
    npm install
fi
echo "   Starting dev server..."
nohup npm run dev > ../frontend.log 2>&1 &
FRONT_PID=$!
echo "   Frontend PID: $FRONT_PID (Logs: frontend.log)"
cd ..

echo "✅ All services backgrounded!"
echo "   Core API: http://localhost:8080"
echo "   Frontend: http://localhost:5173"
echo "   Logs: core.log, satellite.log, frontend.log"
echo "   Run 'tail -f *.log' to monitor logs."

echo "⏳ Waiting 10s for Core to be fully ready before generating mock data..."
(sleep 10 && cd scripts/loadgen && go run main.go > ../../loadgen.log 2>&1 && echo "✅ Mock jobs generated (Logs: loadgen.log)") &

