# Changelog

All notable changes to Hyperscale LCM are documented in this file.

The format follows Keep a Changelog, and this project uses semver-style release tags.

## [Unreleased]

### Added

- Offline/restricted-network deployment bundle runbook and helper script.
- Non-destructive compose deployment preflight script for Step 5 acceptance readiness.

### Changed

- Docker build contexts now exclude generated artifacts and local dependency/report directories.
- Local image build helper now defaults to a fixed release tag and rejects `latest` unless explicitly allowed for dev-only experiments.
- Production compose Prometheus image is pinned to `prom/prometheus:v2.53.5` instead of `latest`.

## [v0.1.0] - 2026-06-12

### Added

- Core, Satellite, and Frontend release images are published with semver tags.
- Production deployment runbook for docker-compose and Helm.
- Upgrade, backup, restore, mTLS rotation, and JWT key rotation runbook.
- Helm mTLS Secret wiring for Core and Satellite.
- Production compose Satellite service with mTLS cert mounts.

### Changed

- Helm and raw Kubernetes manifests use fixed `v0.1.0` image tags by default.
- Core/Satellite deployment manifests use `LCM_CORE_ADDR` on port `8080`, matching the shared Quarkus HTTP/gRPC server.
- Production compose now fails fast when required secrets are missing.
- Core prod profile now requires explicit datasource, Redis, Kafka, and gRPC certificate settings.

### Fixed

- Testcontainers and docker-java dependency drift against Docker Engine 29+.
- Helm Satellite environment variable mismatch that previously caused it to ignore the Core service address.
- Missing mTLS certificate mounts in production deployment manifests.
- Frontend `react-router-dom` dependency range now includes the patched 7.14.x line.
