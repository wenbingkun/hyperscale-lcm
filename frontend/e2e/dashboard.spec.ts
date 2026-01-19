import { test, expect } from '@playwright/test';

test.describe('Dashboard Page', () => {
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
