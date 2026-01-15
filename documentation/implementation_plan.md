# Phase 8 Implementation Plan: Backend Hardening (Security)
**Goal**: Secure the communication channels (mTLS) and manage database schema evolution professionally (Flyway).

## 1. mTLS Implementation (Mutual TLS)
Secure the gRPC channel between Core (Server) and Satellite (Client) so that only authorized Satellites can connect.

### Infrastructure (Certificates)
- Create `certs/` directory in root.
- Create `gen_certs.sh` script to generate:
    - **CA (Certificate Authority)**: `ca.key`, `ca.pem`
    - **Server (Core)**: `server.key`, `server.pem` (Signed by CA, SAN=localhost,core)
    - **Client (Satellite)**: `client.key`, `client.pem` (Signed by CA, CN=satellite)

### Core Service (Quarkus)
- **Config**: Enable SSL/TLS for gRPC.
    - `quarkus.grpc.server.ssl.certificate=certs/server.pem`
    - `quarkus.grpc.server.ssl.key=certs/server.key`
    - `quarkus.grpc.server.ssl.trust-store=certs/truststore.jks` (or trust CA pem)
    - `quarkus.grpc.server.ssl.client-auth=required` (Enforce mTLS)

### Satellite Service (Go)
- **Client**: Load `client.pem`, `client.key`, and `ca.pem`.
- **DialOption**: Use `credentials.NewTLS`.

## 2. Database Migrations (Flyway)
Move away from `hibernate.hbm2ddl.auto = update` to versioned SQL scripts.

### Core Service
- **Dependency**: Add `quarkus-flyway`.
- **Config**:
    - `quarkus.flyway.migrate-at-start=true`
    - `quarkus.hibernate-orm.database.generation=validate`
- **Migration Script**:
    - Create `src/main/resources/db/migration/V1.0.0__Init.sql`.
    - Content: Extract current schema (Satellite, Node tables).

## Verification Plan
1.  **mTLS Verify**:
    - Start Core with SSL.
    - Start Satellite *without* certs -> Connection Rejected.
    - Start Satellite *with* certs -> Connection Accepted.
2.  **Flyway Verify**:
    - `drop-and-create` logic is gone.
    - Start Core -> Flyway executes `V1.0.0`.
    - DB Tables exist and are correct.
