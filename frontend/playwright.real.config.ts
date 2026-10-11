import { defineConfig, devices } from '@playwright/test';

// Acceptance run against a REAL Core + Satellite + sshd stack (no API mocks).
// Not part of the default `npx playwright test` run; see documentation/runbooks/deployment.md §5.3.
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
        baseURL: 'http://127.0.0.1:5173',
        screenshot: 'only-on-failure',
    },
    projects: [{ name: 'chromium', use: { ...devices['Desktop Chrome'] } }],
    webServer: {
        command: 'npm run dev -- --host 127.0.0.1 --port 5173',
        url: 'http://127.0.0.1:5173',
        reuseExistingServer: false,
        timeout: 120_000,
        env: { VITE_API_BASE: process.env.LCM_REAL_API_BASE ?? 'http://127.0.0.1:8080' },
    },
});
