import { expect, test } from './fixtures/test-fixtures';

test.describe('Discovery Page', () => {
    test.beforeEach(async ({ authenticatedPage }) => {
        await authenticatedPage.goto('/discovery');
        await expect(authenticatedPage.getByText('Device Discovery')).toBeVisible();
    });

    test('loads the discovery table', async ({ authenticatedPage }) => {
        await expect(authenticatedPage.getByRole('columnheader', { name: 'Discovery' })).toBeVisible();
        await expect(authenticatedPage.getByRole('columnheader', { name: 'Endpoint' })).toBeVisible();
        await expect(authenticatedPage.getByRole('columnheader', { name: 'Claim Plan' })).toBeVisible();
    });

    test('shows discovered device details', async ({ authenticatedPage }) => {
        await expect(authenticatedPage.getByText('192.168.1.100')).toBeVisible();
        await expect(authenticatedPage.getByText('AA:BB:CC:DD:EE:01')).toBeVisible();
        await expect(authenticatedPage.getByText('dell-r750-bmc')).toBeVisible();
    });

    test('filters the list by search query', async ({ authenticatedPage }) => {
        await authenticatedPage
            .getByPlaceholder('Search by IP, host, vendor, model, profile, template')
            .fill('dell-r750-bmc');

        await expect(authenticatedPage.locator('tbody tr', { hasText: '192.168.1.101' })).toBeVisible();
        await expect(authenticatedPage.locator('tbody tr', { hasText: '192.168.1.100' })).toHaveCount(0);
    });

    test('approves a pending device and updates its status', async ({ authenticatedPage }) => {
        const deviceRow = authenticatedPage.locator('tbody tr', { hasText: '192.168.1.100' });

        await deviceRow.locator('button[title="Approve"]').click();

        await expect(deviceRow).toContainText('APPROVED');
        await expect(authenticatedPage.getByText('Device dev-001 approved.')).toBeVisible();
    });

    test('refreshes a claim plan for a discovered device', async ({ authenticatedPage }) => {
        const deviceRow = authenticatedPage.locator('tbody tr', { hasText: '192.168.1.100' });

        await deviceRow.getByRole('button', { name: 'Replan' }).click();

        await expect(deviceRow).toContainText('READY_TO_CLAIM');
        await expect(deviceRow).toContainText('Dell iDRAC Default');
    });
});
