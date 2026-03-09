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

test.describe('Discovery Page', () => {
    test.beforeEach(async ({ page }) => {
        await loginViaLocalStorage(page);
    });

    test('should display discovery page header', async ({ page }) => {
        await page.goto('/discovery');

        await expect(page.locator('h2')).toContainText('Device Discovery');
    });

    test('should show scan network button', async ({ page }) => {
        await page.goto('/discovery');

        // Check scan button exists
        const scanButton = page.getByRole('button', { name: /scan network/i });
        await expect(scanButton).toBeVisible();
    });

    test('should open scan modal when clicking scan button', async ({ page }) => {
        await page.goto('/discovery');

        // Click scan button
        await page.getByRole('button', { name: /scan network/i }).click();

        // Modal should appear
        await expect(page.getByText('Start Network Scan')).toBeVisible();

        // Form fields should be visible
        await expect(page.getByPlaceholder(/192\.168/i)).toBeVisible();
        await expect(page.getByPlaceholder(/22,8080/i)).toBeVisible();
    });

    test('should close scan modal on cancel', async ({ page }) => {
        await page.goto('/discovery');

        await page.getByRole('button', { name: /scan network/i }).click();
        await expect(page.getByText('Start Network Scan')).toBeVisible();

        // Click cancel
        await page.getByRole('button', { name: /cancel/i }).click();

        // Modal should disappear
        await expect(page.getByText('Start Network Scan')).not.toBeVisible();
    });

    test('should display devices table', async ({ page }) => {
        await page.goto('/discovery');

        // Table headers should be visible
        await expect(page.getByRole('columnheader', { name: /status/i })).toBeVisible();
        await expect(page.getByRole('columnheader', { name: /ip address/i })).toBeVisible();
        await expect(page.getByRole('columnheader', { name: /hostname/i })).toBeVisible();
    });
});
