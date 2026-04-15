# Hyperscale LCM Satellite

Satellite is the edge agent that runs close to managed nodes or within a data center zone.

## Responsibilities

- Register with Core and maintain heartbeat over gRPC + mTLS
- Collect Redfish inventory and heartbeat payloads
- Discover devices through DHCP listener and network scanning
- Execute dispatched workloads through Docker, Shell, Ansible, or SSH
- Expose PXE / TFTP / iPXE / Cloud-Init endpoints for bare-metal provisioning flows
- Emit OpenTelemetry spans and preserve trace context across execution callbacks

## Stack

- Go 1.24
- gRPC client with mTLS
- Docker SDK
- Redfish session-aware HTTP transport
- OpenTelemetry
- TFTP / PXE support

## Local Run

```bash
export LCM_CORE_ADDR=localhost:8080
export LCM_CERTS_DIR=../certs
go run ./cmd/satellite
```

Useful optional environment variables:

- `LCM_DISCOVERY_IFACE`
- `LCM_MOCK_HOSTNAME`
- `LCM_BMC_IP`
- `LCM_BMC_USER`
- `LCM_BMC_PASSWORD`
- `LCM_BMC_AUTH_MODE`
- `LCM_BMC_SESSION_TTL_SECONDS_MAX`
- `LCM_REDFISH_TEMPLATE_DIR`
- `LCM_REDFISH_TEMPLATE_NAME`
- `LCM_PXE_TFTP_ADDR`
- `LCM_PXE_TFTP_ROOT`
- `LCM_PXE_HTTP_ADDR`
- `LCM_PXE_DHCP_PROXY_ENABLED`
- `LCM_PXE_DHCP_PROXY_ADDR`
- `LCM_PXE_BOOT_SERVER_HOST`
- `LCM_PXE_DHCP_BOOTFILE`
- `LCM_PXE_DHCP_IPXE_SCRIPT_URL`
- `LCM_PXE_IMAGE_DIR`
- `LCM_PXE_INSTALL_REPO_URL`
- `LCM_PXE_BOOT_KERNEL_URL`
- `LCM_PXE_BOOT_INITRD_URL`
- `LCM_PXE_BOOT_KERNEL_ARGS`
- `LCM_PXE_KICKSTART_TEMPLATE`

`LCM_BMC_AUTH_MODE` supports `BASIC_ONLY`, `SESSION_PREFERRED`, and `SESSION_ONLY`. The default is `SESSION_PREFERRED`.

`LCM_BMC_SESSION_TTL_SECONDS_MAX` caps local Redfish session reuse TTL in seconds. The default is `1800`.

## Validation

```bash
go test ./...
```

## Notes

- Satellite does not talk to Kafka directly; status and trace data flow back to Core over the gRPC stream and are then forwarded by Core.
- PXE now includes a lightweight DHCP proxy that advertises `Option 66/67`, defaults to `undionly.kpxe` for legacy PXE clients, and chainloads iPXE clients to the Satellite-hosted `/ipxe` script.
- PXE image management is exposed on the Satellite HTTP server through `GET /api/images`, `POST /api/images` (multipart field `file`), and `DELETE /api/images/{name}`. Uploaded images are stored under `LCM_PXE_IMAGE_DIR` and surfaced centrally by Core at `/api/images`.
- `/ipxe` now renders a full boot flow that hands off to `inst.ks=http://<satellite>/kickstart`, and `/kickstart` supports node-specific rendering from the booting host's `mac` / `hostname` parameters. `LCM_PXE_KICKSTART_TEMPLATE` can point to a custom Go template file.
- The remaining PXE delivery work is primarily real hardware validation and installer asset tuning per environment.
