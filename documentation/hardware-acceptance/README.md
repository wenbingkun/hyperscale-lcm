# Hardware Acceptance — Phase 7

This directory tracks real-world Redfish/BMC acceptance for Phase 7 hardening.

## Layout

- `matrix.yaml` — machine-readable summary of every accepted machine: vendor, model, firmware, auth mode, claim/rotate/reset results, last verified date, owner.
- `<vendor>-<model>.md` — per-machine narrative: setup notes, fixture deltas, observed quirks, links to mock fixtures or recordings.

## Update rules

- Each accepted run must update `matrix.yaml` and add or refresh the per-machine markdown.
- Firmware upgrades create a new row; the previous row is retained for 90 days then archived.
- Destructive `power-actions` (`ForceOff`, `ForceRestart`) only run inside an explicitly noted maintenance window.
- If a run is partial (e.g., claim succeeds but reset fails), record both outcomes — never overwrite a failure with a success from a different attempt.

## Reporting workflow

1. Cut a fresh BMC session against the target machine.
2. Run `/api/bmc/devices/{id}/claim` and capture the response.
3. Run `/api/bmc/devices/{id}/capabilities` and copy the snapshot into the per-machine markdown.
4. Run `/api/bmc/devices/{id}/power-actions?dryRun=true` for each Phase 7 action and record the resolved target URI.
5. Only after a maintenance approval, run a real `power-actions` against `On`/`GracefulShutdown` and record the response code + `taskLocation`.
6. Update `matrix.yaml` with the verification timestamp and submit the change as part of the same PR that touches Phase 7 code.
