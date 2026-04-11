# Hyperscale LCM

Hyperscale LCM is a distributed lifecycle management platform for GPU and bare-metal clusters. The current mainline already covers discovery, claim planning, topology-aware scheduling, multi-mode job execution, real-time UI refresh, and baseline observability for large-scale cluster operations.

## Current Status

| Layer | Stack | Current Role |
|------|-------|--------------|
| Core | Java 21, Quarkus 3.6.4, Timefold 1.4.0 | API gateway, scheduler, Kafka/WebSocket orchestration, tenancy and quota control |
| Satellite | Go 1.24 | Edge agent, discovery, Redfish collection, Docker/Shell/Ansible/SSH execution, PXE/TFTP |
| Frontend | React 19, TypeScript, Vite | Dashboard, jobs, discovery, credential profiles, topology visualization |

### Implemented Capabilities

- Zone-aware partitioned scheduling with `clusterId` isolation
- GPU / NVLink / IB Fabric-aware job placement
- Docker, Shell, Ansible, and SSH execution modes
- Discovery, claim planning, credential profiles, Vault-backed secret refs, and Redfish template catalog
- WebSocket-driven updates for dashboard, jobs, job detail, and topology pages
- Prometheus, Grafana, OpenTelemetry, health probes, and trace continuity across `Satellite -> Kafka -> Core`

### Still In Progress

- AlertManager external notification channels
- Real hardware Redfish / BMC acceptance validation
- PXE / iPXE provisioning closure for full bare-metal reinstall automation
- Demo script and broader Playwright browser-level regression coverage

## Architecture

```text
Frontend (React)
  -> REST + WebSocket
Core (Quarkus)
  -> PostgreSQL + Redis
  -> Kafka (jobs.scheduled / jobs.execution / jobs.status / DLQ)
  -> gRPC + mTLS
Satellite (Go)
  -> Redfish / network discovery / PXE / Docker / Shell / Ansible / SSH
```

For the detailed architecture and scheduling design, see:

- `documentation/ENTERPRISE_LCM_ARCHITECTURE.md`
- `documentation/RESOURCE_SCHEDULING_DESIGN.md`

## Quick Start

### Prerequisites

- Java 21+
- Go 1.24+
- Node.js 20+
- Docker and Docker Compose

### Start Local Dependencies

```bash
docker-compose up -d postgres redis kafka jaeger prometheus
```

### Run Core

```bash
cd core
./gradlew quarkusDev
```

### Run Satellite

```bash
cd satellite
export LCM_CORE_ADDR=localhost:8080
export LCM_CERTS_DIR=../certs
go run ./cmd/satellite
```

### Run Frontend

```bash
cd frontend
npm install
npm run dev
```

You can also use `./start_all.sh` for a convenience local bootstrap path.

## Verification

The canonical CI and test matrix lives in `documentation/CI_CONTRACT.md`.

Common local checks:

```bash
./scripts/check_ci_contract.sh

cd frontend && npm test
cd frontend && npm run lint
cd frontend && npm run build

cd satellite && go test ./...
```

For Core validation, follow the environment-specific commands in `documentation/CI_CONTRACT.md` rather than duplicating them here.

## Documentation Map

| Document | Purpose |
|----------|---------|
| `DEVELOPMENT_ROADMAP.md` | Roadmap, completion checklist, sprint log |
| `documentation/PROJECT_ANALYSIS_AND_NEXT_STEPS.md` | Current-state analysis and next-step priorities |
| `documentation/TASK_COMPLETION_AUDIT.md` | Completion audit and weighted progress view |
| `documentation/CI_CONTRACT.md` | CI/test source of truth |
| `documentation/CI_FAILURE_PATTERNS.md` | CI troubleshooting patterns |
| `documentation/REDFISH_BMC_PHASE7_PLAN.md` | Phase 7 Redfish/BMC hardening plan |
| `documentation/hardware-acceptance/` | Real hardware Redfish/BMC acceptance matrix |

## Repository Layout

```text
hyperscale-lcm/
|- core/                     Quarkus control plane
|- satellite/                Go edge agent
|- frontend/                 Main React application
|- dashboard/                Legacy experimental UI sandbox
|- documentation/            Active design and status documents
|- helm/                     Helm chart
|- k8s/                      Raw Kubernetes manifests
|- scripts/                  Utility and verification scripts
|- certs/                    Local TLS / mTLS assets
|- docker-compose.yml        Local infrastructure stack
|- docker-compose.prod.yml   Production-oriented compose stack
```

## Security And Operations

- JWT + RBAC protect the REST API.
- gRPC traffic between Core and Satellite uses mTLS.
- Flyway manages schema evolution in Core.
- JaCoCo coverage verification is enforced in `core check`.
- Frontend uses `Vitest + React Testing Library`, with Playwright E2E still being expanded.

## License

Apache License 2.0
