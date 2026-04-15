import { expect, test } from './fixtures/test-fixtures';

test.describe('Satellites And Topology Pages', () => {
    test('renders the satellites fleet view', async ({ authenticatedPage }) => {
        await authenticatedPage.goto('/satellites');

        await expect(
            authenticatedPage.getByRole('heading', { name: 'Satellite Fleet', level: 2 }),
        ).toBeVisible();
        await expect(authenticatedPage.getByRole('link', { name: 'gpu-node-alpha' })).toBeVisible();
        await expect(authenticatedPage.getByText('10.0.1.11')).toBeVisible();
    });

    test('opens the satellite detail page and shows hardware information', async ({ authenticatedPage }) => {
        await authenticatedPage.goto('/satellites');
        await authenticatedPage.getByRole('link', { name: 'gpu-node-alpha' }).click();

        await expect(authenticatedPage).toHaveURL(/\/satellites\/sat-001$/);
        await expect(authenticatedPage.getByText('Node Information')).toBeVisible();
        await expect(authenticatedPage.getByText('Resources')).toBeVisible();
        await expect(authenticatedPage.getByText('32 Cores')).toBeVisible();
        await expect(authenticatedPage.getByText('256 GB')).toBeVisible();
        await expect(authenticatedPage.getByText('8x A100')).toBeVisible();
    });

    test('renders the topology visualization area', async ({ authenticatedPage }) => {
        await authenticatedPage.goto('/topology');

        await expect(authenticatedPage.getByText('Allocation Topology')).toBeVisible();
        await expect(authenticatedPage.getByText('IB Fabric Overview')).toBeVisible();
        await expect(authenticatedPage.locator('[data-testid="gpu-slot-sat-001"]').first()).toBeVisible();
    });

    test('shows the zone and rack hierarchy', async ({ authenticatedPage }) => {
        await authenticatedPage.goto('/topology');

        await expect(authenticatedPage.getByText('Zone zone-a')).toBeVisible();
        await expect(authenticatedPage.getByText('rack-01')).toBeVisible();
        await expect(authenticatedPage.getByText('rack-02')).toBeVisible();
    });
});
