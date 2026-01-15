# Hyperscale LCM Satellite (Edge Collector)

This component is the distributed edge agent designed to run in each data center zone.

## Responsibilities
1.  **High-Frequency Polling**: Polls BMCs (Redfish/IPMI) every 1-5 seconds.
2.  **Active Discovery**: Listens for DHCP requests and scans local subnets (`masscan` integration) to detect new devices.
3.  **Data Aggregation**: Compresses metrics and sends them to the Core via gRPC/Pulsar.
4.  **Zero-Touch Provisioning**: Acts as a PXE Boot Server / TFTP Server for bare metal provisioning.

## Tech Stack
*   **Language**: Go / Rust
*   **Protocol**: gRPC (Communication with Core)
*   **Database**: Embedded SQLite (for local caching when disconnected)
