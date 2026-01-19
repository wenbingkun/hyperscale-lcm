import { test, expect } from '@playwright/test';

test.describe('Discovery Page', () => {
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
