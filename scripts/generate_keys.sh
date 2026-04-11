#!/bin/bash
set -e

echo "Generating certificates and keys for testing..."

# Generate gRPC TLS certs
mkdir -p certs
cd certs

# CA
openssl genpkey -algorithm RSA -out ca.key -pkeyopt rsa_keygen_bits:2048
openssl req -new -x509 -days 365 -key ca.key -out ca.pem -subj "/CN=LCM CA"

# Server
openssl genpkey -algorithm RSA -out server.key -pkeyopt rsa_keygen_bits:2048
openssl pkcs8 -topk8 -inform PEM -outform PEM -nocrypt -in server.key -out server-pkcs8.key
cat > server-ext.cnf <<'EOF'
subjectAltName = DNS:localhost, DNS:core, IP:127.0.0.1, IP:0:0:0:0:0:0:0:1
extendedKeyUsage = serverAuth
EOF
openssl req -new -key server.key -out server.csr -subj "/CN=localhost"
openssl x509 -req -days 365 -in server.csr -CA ca.pem -CAkey ca.key -CAcreateserial -out server.pem -extfile server-ext.cnf

# Client
openssl genpkey -algorithm RSA -out client.key -pkeyopt rsa_keygen_bits:2048
openssl req -new -key client.key -out client.csr -subj "/CN=satellite"
openssl x509 -req -days 365 -in client.csr -CA ca.pem -CAkey ca.key -CAcreateserial -out client.pem

# Cleanup CSR, Srl, and extension config
rm -f *.csr *.srl server-ext.cnf

# Generate truststore.jks for gRPC (explicit JKS format; JDK 17+ defaults to PKCS12)
rm -f truststore.jks
keytool -import -alias ca -file ca.pem -keystore truststore.jks -storepass changeit -noprompt -storetype JKS

cd ..

# Generate JWT keys
mkdir -p core/src/main/resources/META-INF/resources/
cd core/src/main/resources/META-INF/resources/
openssl genpkey -algorithm RSA -out privateKey.pem -pkeyopt rsa_keygen_bits:2048
openssl rsa -pubout -in privateKey.pem -out publicKey.pem
cd ../../../../../

echo "Keys and certs generated successfully!"
