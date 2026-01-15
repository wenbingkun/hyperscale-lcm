#!/bin/bash
set -e

# Create certs directory
mkdir -p certs
cd certs

echo "🔒 Generating Certificate Authority (CA)..."
# Generate CA Key
openssl genrsa -out ca.key 4096
# Generate CA Certificate
openssl req -new -x509 -key ca.key -sha256 -subj "/C=US/ST=State/L=City/O=Hyperscale/OU=Org/CN=HyperscaleRootCA" -days 365 -out ca.pem

echo "🔒 Generating Server Certificate (Core)..."
# Generate Server Key
openssl genrsa -out server.key 4096
# Generate Server CSR
openssl req -new -key server.key -out server.csr -config <(cat /etc/ssl/openssl.cnf <(printf "\n[SAN]\nsubjectAltName=DNS:localhost,DNS:core,IP:127.0.0.1")) -subj "/C=US/ST=State/L=City/O=Hyperscale/OU=Core/CN=localhost" -reqexts SAN
# Sign Server Certificate
openssl x509 -req -in server.csr -CA ca.pem -CAkey ca.key -CAcreateserial -out server.pem -days 365 -sha256 -extfile <(cat /etc/ssl/openssl.cnf <(printf "\n[SAN]\nsubjectAltName=DNS:localhost,DNS:core,IP:127.0.0.1")) -extensions SAN

echo "🔒 Generating Client Certificate (Satellite)..."
# Generate Client Key
openssl genrsa -out client.key 4096
# Generate Client CSR
openssl req -new -key client.key -out client.csr -subj "/C=US/ST=State/L=City/O=Hyperscale/OU=Satellite/CN=satellite-client"
# Sign Client Certificate
openssl x509 -req -in client.csr -CA ca.pem -CAkey ca.key -CAcreateserial -out client.pem -days 365 -sha256

# Convert Server Key to PKCS#8 for Java (Quarkus prefers this or JKS, but PEM works with recent versions)
# For Quarkus netty/vertx, PEM is fine usually, but let's have pkcs8 just in case
openssl pkcs8 -topk8 -inform PEM -outform PEM -in server.key -out server-pkcs8.key -nocrypt

echo "✅ Certificates generated in certs/"
ls -l *.pem
