import { test, expect, Page } from '@playwright/test';

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

test.describe('Satellites Page', () => {
    test.beforeEach(async ({ page }) => {
        await loginViaLocalStorage(page);
    });

    test('should display satellites page', async ({ page }) => {
        await page.goto('/satellites');

        await expect(page.locator('h2')).toContainText('Satellites');
    });

    test('should show satellites table', async ({ page }) => {
        await page.goto('/satellites');

        // Table should be visible
        await expect(page.getByRole('table')).toBeVisible();
    });
});

test.describe('Jobs Page', () => {
    test.beforeEach(async ({ page }) => {
        await loginViaLocalStorage(page);
    });

    test('should display jobs page', async ({ page }) => {
        await page.goto('/jobs');

        await expect(page.locator('h2')).toContainText('Jobs');
    });
});

test.describe('Tenants Page', () => {
    test.beforeEach(async ({ page }) => {
        await loginViaLocalStorage(page);
    });

    test('should display tenants page', async ({ page }) => {
        await page.goto('/tenants');

        await expect(page.locator('h2')).toContainText('Tenant Management');
    });

    test('should show new tenant button', async ({ page }) => {
        await page.goto('/tenants');

        await expect(page.getByRole('button', { name: /new tenant/i })).toBeVisible();
    });
});
