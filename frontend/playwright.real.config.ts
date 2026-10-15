import { defineConfig, devices } from '@playwright/test';

// Acceptance run against a REAL Core + Satellite + sshd stack (no API mocks).
// Not part of the default `npx playwright test` run; see documentation/runbooks/deployment.md §5.3.
// LCM_REAL_BASE_URL (e.g. http://127.0.0.1:5173) targets an already running, built frontend such as the
// lcm-frontend container of the lab stack; without it a Vite dev server is started on port 5173.
const externalBaseUrl = process.env.LCM_REAL_BASE_URL;

export default defineConfig({
    testDir: './e2e-real',
    outputDir: 'test-results-real',
    fullyParallel: false,
    workers: 1,
    retries: 0,
    reporter: [['list']],
    timeout: 120_000,
    expect: { timeout: 15_000 },
    use: {
        baseURL: externalBaseUrl ?? 'http://127.0.0.1:5173',
        screenshot: 'only-on-failure',
    },
    projects: [{ name: 'chromium', use: { ...devices['Desktop Chrome'] } }],
    webServer: externalBaseUrl ? undefined : {
        command: 'npm run dev -- --host 127.0.0.1 --port 5173',
        url: 'http://127.0.0.1:5173',
        reuseExistingServer: false,
        timeout: 120_000,
        env: { VITE_API_BASE: process.env.LCM_REAL_API_BASE ?? 'http://127.0.0.1:8080' },
    },
});
