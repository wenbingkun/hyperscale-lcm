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

## Validation

```bash
go test ./...
```

## Notes

- Satellite does not talk to Kafka directly; status and trace data flow back to Core over the gRPC stream and are then forwarded by Core.
- PXE support is available, but the full DHCP option `66/67` and image-management closure is still a remaining delivery task.
