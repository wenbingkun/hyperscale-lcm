import { test, expect } from '@playwright/test';

test.describe('Satellites Page', () => {
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
    test('should display jobs page', async ({ page }) => {
        await page.goto('/jobs');

        await expect(page.locator('h2')).toContainText('Jobs');
    });

    test('should show job submission form', async ({ page }) => {
        await page.goto('/jobs');

        // Submit button should be visible
        await expect(page.getByRole('button', { name: /submit/i })).toBeVisible();
    });
});

test.describe('Tenants Page', () => {
    test('should display tenants page', async ({ page }) => {
        await page.goto('/tenants');

        await expect(page.locator('h2')).toContainText('Tenant Management');
    });

    test('should show new tenant button', async ({ page }) => {
        await page.goto('/tenants');

        await expect(page.getByRole('button', { name: /new tenant/i })).toBeVisible();
    });
});
