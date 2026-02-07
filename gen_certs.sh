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

# Create config for SAN
cat > server.conf <<EOF
[req]
distinguished_name = req_distinguished_name
req_extensions = v3_req
prompt = no

[req_distinguished_name]
C = US
ST = State
L = City
O = Hyperscale
OU = Core
CN = localhost

[v3_req]
keyUsage = keyEncipherment, dataEncipherment
extendedKeyUsage = serverAuth
subjectAltName = @alt_names

[alt_names]
DNS.1 = localhost
DNS.2 = core
IP.1 = 127.0.0.1
EOF

# Generate Server CSR using config
openssl req -new -key server.key -out server.csr -config server.conf

# Sign Server Certificate
openssl x509 -req -in server.csr -CA ca.pem -CAkey ca.key -CAcreateserial -out server.pem -days 365 -sha256 -extensions v3_req -extfile server.conf

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
