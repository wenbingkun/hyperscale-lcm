# Hyperscale LCM Frontend

Main React application for the Hyperscale LCM control plane.

## Scope

This app is the primary operator UI and currently includes:

- Login and auth persistence
- Dashboard overview
- Jobs list and job detail
- Discovery workflow and claim actions
- Credential profile management
- Satellites and tenants views
- Topology visualization grouped by zone, rack, and IB fabric

## Stack

- React 19
- TypeScript
- Vite
- React Router
- Recharts
- Vitest + React Testing Library
- Playwright

## Local Development

```bash
npm install
npm run dev
```

Default dev server: `http://localhost:5173`

## Validation

```bash
npm test
npm run lint
npm run build
```

Browser-level regression coverage is available through:

```bash
npm run test:e2e
```

## Notes

- The frontend consumes REST APIs from Core and subscribes to WebSocket events for live updates.
- The current automated unit coverage focuses on auth, dashboard, discovery, jobs, job detail, topology, and job submission flows.
- Playwright coverage exists but is still intentionally lighter than the unit/component suite.
