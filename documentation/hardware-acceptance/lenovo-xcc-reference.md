# Lenovo XCC Reference Acceptance Template

> Sample / template entry. Replace with a real verification once the Lenovo XCC lab rig is wired up.

## Identity

| Field | Value |
|-------|-------|
| Vendor | Lenovo |
| Model | XCC (TBD) |
| BMC Firmware | TBD |
| Network Path | https://lenovo-xcc-lab.example/redfish/v1 |
| Owner | TBD |
| Last Verified | TBD |

## Auth modes verified

- [ ] BASIC_ONLY
- [ ] SESSION_PREFERRED
- [ ] SESSION_ONLY

## Claim & rotate

- [ ] `POST /api/bmc/devices/{id}/claim` — paste captured response.
- [ ] `POST /api/bmc/devices/{id}/rotate-credentials` — paste captured response.
- [ ] `GET /api/bmc/devices/{id}/capabilities` — record the capability snapshot returned to the API.

## Power actions (dry-run only by default)

| Action | dryRun result | targetUri | systemId | Notes |
|--------|---------------|-----------|----------|-------|
| On | TBD | TBD | TBD | |
| GracefulShutdown | TBD | TBD | TBD | |
| GracefulRestart | TBD | TBD | TBD | |
| ForceOff | TBD | TBD | TBD | Maintenance window only |
| ForceRestart | TBD | TBD | TBD | Maintenance window only |

## Observed quirks

- (Document any non-standard behavior, OEM extensions to ignore, vendor-specific fixture deltas, etc.)

## Mock fixture link

- `satellite/pkg/redfish/testdata/vendor-fixtures/lenovo-xcc.json` — current Lenovo XCC baseline used by Satellite fixture regression.
