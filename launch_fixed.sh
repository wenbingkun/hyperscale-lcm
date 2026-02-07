#!/bin/bash
set -e
echo "🚀 Launching Fixed Services..."

# Start Core
cd core
export GRPC_CERT_PATH=$(readlink -f ../certs/server.pem)
export GRPC_KEY_PATH=$(readlink -f ../certs/server-pkcs8.key)
export GRPC_TRUSTSTORE_PATH=$(readlink -f ../certs/truststore.jks)
echo "Starting Core with certs=$GRPC_CERT_PATH"
nohup ./gradlew quarkusDev > ../core.log 2>&1 &
echo "Core started (PID $!)"
cd ..

# Start Satellite
cd satellite
echo "Starting Satellite..."
nohup go run ./cmd/satellite > ../satellite.log 2>&1 &
echo "Satellite started (PID $!)"
cd ..
