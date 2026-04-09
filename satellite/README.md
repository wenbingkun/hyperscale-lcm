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
- Redfish client (`gofish`)
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

## Validation

```bash
go test ./...
```

## Notes

- Satellite does not talk to Kafka directly; status and trace data flow back to Core over the gRPC stream and are then forwarded by Core.
- PXE now includes a lightweight DHCP proxy that advertises `Option 66/67`, defaults to `undionly.kpxe` for legacy PXE clients, and chainloads iPXE clients to the Satellite-hosted `/ipxe` script.
- The remaining PXE delivery work is image management and node-specific boot / kickstart templates.
