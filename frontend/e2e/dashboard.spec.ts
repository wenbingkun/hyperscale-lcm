import { test, expect, Page } from '@playwright/test';

// Helper to set auth token in localStorage before navigating
async function loginViaLocalStorage(page: Page) {
    await page.goto('/login');
    await page.evaluate(() => {
        const header = btoa(JSON.stringify({ alg: 'HS256', typ: 'JWT' }));
        const payload = btoa(JSON.stringify({
            upn: 'admin',
            groups: ['ADMIN'],
            tenant_id: 'default',
            exp: Math.floor(Date.now() / 1000) + 3600
        }));
        const token = `${header}.${payload}.test-signature`;
        localStorage.setItem('lcm_auth_token', token);
        localStorage.setItem('lcm_auth_user', JSON.stringify({
            username: 'admin',
            roles: ['ADMIN'],
            tenantId: 'default'
        }));
    });
}

test.describe('Dashboard Page', () => {
    test.beforeEach(async ({ page }) => {
        await loginViaLocalStorage(page);
    });

    test('should display dashboard header', async ({ page }) => {
        await page.goto('/');

        // Check page title
        await expect(page).toHaveTitle(/Hyperscale/i);

        // Check header is visible
        await expect(page.locator('h1')).toContainText('Hyperscale');
    });

    test('should display navigation links', async ({ page }) => {
        await page.goto('/');

        // Check navigation links exist
        await expect(page.getByRole('link', { name: /overview/i })).toBeVisible();
        await expect(page.getByRole('link', { name: /satellites/i })).toBeVisible();
        await expect(page.getByRole('link', { name: /jobs/i })).toBeVisible();
        await expect(page.getByRole('link', { name: /discovery/i })).toBeVisible();
        await expect(page.getByRole('link', { name: /tenants/i })).toBeVisible();
    });

    test('should navigate to satellites page', async ({ page }) => {
        await page.goto('/');

        await page.getByRole('link', { name: /satellites/i }).click();

        await expect(page).toHaveURL('/satellites');
        await expect(page.locator('h2')).toContainText('Satellites');
    });

    test('should navigate to jobs page', async ({ page }) => {
        await page.goto('/');

        await page.getByRole('link', { name: /jobs/i }).click();

        await expect(page).toHaveURL('/jobs');
        await expect(page.locator('h2')).toContainText('Jobs');
    });
});
