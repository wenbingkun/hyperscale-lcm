# Documentation Guide

> Updated: 2026-04-08

This directory now keeps only documents that still reflect the current `hyperscale-lcm` codebase or are needed for ongoing delivery.

## Source Of Truth

| Document | Purpose | When To Update |
|----------|---------|----------------|
| `../DEVELOPMENT_ROADMAP.md` | Delivery roadmap, milestone status, sprint log | After each completed roadmap item |
| `PROJECT_ANALYSIS_AND_NEXT_STEPS.md` | Current project status, capability summary, next-step priorities | After any major capability or priority shift |
| `TASK_COMPLETION_AUDIT.md` | Completion audit and weighted progress review | After milestone reviews or roadmap re-baseline |

## Engineering Rules

| Document | Purpose |
|----------|---------|
| `CI_CONTRACT.md` | Canonical CI/test verification matrix |
| `CI_FAILURE_PATTERNS.md` | Common CI failure signatures and triage guidance |

## Design And Feature Docs

| Document | Purpose |
|----------|---------|
| `ENTERPRISE_LCM_ARCHITECTURE.md` | Current hyperscale architecture view and deployment model |
| `RESOURCE_SCHEDULING_DESIGN.md` | Current scheduling pipeline, model, and constraint design |
| `REDFISH_ZERO_TOUCH_REFACTOR_PLAN.md` | Redfish/claim onboarding architecture and design principles |
| `REDFISH_ZERO_TOUCH_REFACTOR_TASKS.md` | Redfish/claim delivery checklist and remaining gaps |

## Supporting Assets

| Path | Purpose |
|------|---------|
| `cmdb/` | CMDB bootstrap mapping examples |
| `redfish-templates/` | Vendor-specific Redfish template samples |

## Cleanup Note

The following stale or duplicate documents were removed during the 2026-04-08 cleanup because their content had already been superseded by the roadmap, audit, and current-state documents:

- `CONSOLIDATED_REVIEW_AND_PLAN_2026-03-12.md`
- `HYPERSCALE_LCM_IMPLEMENTATION_PLAN.md`
- `PROJECT_DEEP_ANALYSIS_REPORT.md`
- `REFACTORING_FEASIBILITY_REPORT.md`
- `implementation_plan.md`
- `task.md`
- `walkthrough.md`

If a future review needs historical snapshots, create an explicit `documentation/archive/` directory instead of mixing snapshots with active guidance.
