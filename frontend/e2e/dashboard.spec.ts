import { expect, test } from './fixtures/test-fixtures';

test.describe('Dashboard Page', () => {
    test.beforeEach(async ({ authenticatedPage }) => {
        await authenticatedPage.goto('/');
        await expect(authenticatedPage.getByText('Total Nodes')).toBeVisible();
    });

    test('renders the main stat cards', async ({ authenticatedPage }) => {
        await expect(authenticatedPage.getByText('GPU Capacity')).toBeVisible();
        await expect(authenticatedPage.getByText('Active Jobs')).toBeVisible();
        await expect(authenticatedPage.getByText('Network')).toBeVisible();
    });

    test('shows the mocked cluster stat value', async ({ authenticatedPage }) => {
        await expect(authenticatedPage.getByText('42', { exact: true })).toBeVisible();
    });

    test('renders the satellite table with mocked data', async ({ authenticatedPage }) => {
        await expect(authenticatedPage.getByRole('link', { name: 'gpu-node-alpha' })).toBeVisible();
        await expect(authenticatedPage.getByText('10.0.1.10')).toBeVisible();
    });

    test('shows the job submission form', async ({ authenticatedPage }) => {
        await expect(authenticatedPage.getByText('Submit AI Training Job')).toBeVisible();
        await expect(authenticatedPage.getByPlaceholder('e.g. LLM-Training-v1')).toBeVisible();
    });

    test('navigates to the satellites page from the top navigation', async ({ authenticatedPage }) => {
        await authenticatedPage.getByRole('link', { name: 'Satellites' }).click();

        await expect(authenticatedPage).toHaveURL(/\/satellites$/);
        await expect(authenticatedPage.getByText('Satellite Fleet')).toBeVisible();
    });
});
