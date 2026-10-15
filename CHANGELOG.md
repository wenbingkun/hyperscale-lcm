# Changelog

All notable changes to Hyperscale LCM are documented in this file.

The format follows Keep a Changelog, and this project uses semver-style release tags.

## [Unreleased]

### Added

- Offline/restricted-network deployment bundle runbook and helper script.
- Non-destructive compose deployment preflight script for Step 5 acceptance readiness.

### Changed

- Scheduling correctness (M2 batch D / M3): placement now subtracts what SCHEDULED/RUNNING/UNKNOWN jobs already hold and reserves capacity atomically (row lock on the target Satellite), enforces GPU model, and leaves infeasible jobs PENDING; the sweeper retries PENDING jobs and fails them with an explanation after `lcm.jobs.pending-timeout`. Node specs are restored from the database at startup. **Fix:** `MockNodeSpecsProvider` was an enabled CDI alternative in every profile and overrode real Satellite specs with a fixed 32-core node; it is now test-only. Tokens carrying `tenant_id` bind submissions to that tenant (403 on mismatch) and confine non-admin reads. `POST /api/v1/allocations` is disabled by default (`lcm.allocations.enabled`) because it entered the default Docker path.
- Satellite execution correctness (M2 batch C): Docker jobs now wait for the container to exit and report its real exit code and tail-capped output (`COMPLETED` only for exit 0), time out via `LCM_DOCKER_TIMEOUT` (default 1h) and always remove the container; Docker payload may be `{"image","cmd"}`. The Satellite no longer re-executes a repeated `command_id` (in-process ledger) and replays unsent terminal statuses after the command stream reconnects. No protocol or Core change.
- Durable dispatch (M2 batch B): a sweeper redelivers jobs whose Satellite was offline, fails jobs that were never delivered, and marks delivered jobs without a result as the new `UNKNOWN` status (never re-sent automatically); `POST /api/jobs/{id}/resolve` records the verified outcome. Dispatch is claimed in the database so a job is handed to a command stream at most once. New Flyway V2.10.0 and `lcm.jobs.*` settings.
- Job state guards (M2 batch A, see `documentation/JOB_LIFECYCLE_CONTRACT.md`): final states can no longer be overwritten by late or repeated callbacks; `DELETE /api/jobs/{id}` now only cancels PENDING/SCHEDULED jobs (409 for RUNNING or finished jobs, with an audit entry); cancelled jobs are not dispatched; a result arriving after cancellation is recorded in the audit log instead of changing the status.
- Added a single-host lab path (`docker-compose.lab.yml`, `scripts/lab_prepare.sh`, `scripts/lab_bootstrap.sh`, `documentation/runbooks/lab-quickstart.md`): dev login, fixed-address SSH target, per-Satellite certificate. The real-backend Playwright config can target a deployed frontend via `LCM_REAL_BASE_URL`.
- Satellite no longer starts PXE services or network discovery (DHCP listener / ARP scan) by default; opt in with `LCM_PXE_ENABLED=true` / `LCM_DISCOVERY_ENABLED=true`. SSH task jobs are re-checked right before dispatch (device still approved, address unchanged, payload host matches) and are failed instead of sent when the target changed.
- Satellite registration is now keyed by the TLS client certificate fingerprint: the first approved registration binds it to the device, later registrations with the same certificate keep the approval and the Satellite ID across IP changes, and other certificates are rejected. Added `scripts/issue_satellite_cert.sh` and Flyway V2.9.0 (nullable columns).
- Frontend: job form can submit an SSH maintenance task against an approved device; the job detail page shows target and result output as plain text. Added an opt-in real-backend Playwright acceptance (`playwright.real.config.ts`).
- SSH jobs now run fixed read-only tasks (`SYSTEM_INFO`) against approved devices (`targetDeviceId` + `task`); the Core builds a credential-free payload, the Satellite resolves the key and `known_hosts` locally, output is stored in `job.result_output`, and submission is audited. Inline password/private-key SSH payloads are rejected unless `lcm.ssh.inline-payload.enabled=true` (dev/test). Satellite image now ships `bash` and `openssh-client`.
- Production gRPC now uses a dual-port layout: plaintext 8080 for REST/health/metrics and HTTPS 8443 with required client certificates for Satellite gRPC; plaintext gRPC is rejected by `GrpcTlsEnforcer` (`lcm.grpc.require-tls`). Default deployment image tag moves to the unreleased `v0.1.1`; `v0.1.0` images carry the old contract.
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
