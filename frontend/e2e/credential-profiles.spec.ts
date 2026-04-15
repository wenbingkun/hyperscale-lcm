import { expect, test } from './fixtures/test-fixtures';

test.describe('Credential Profiles Page', () => {
    test.beforeEach(async ({ authenticatedPage }) => {
        await authenticatedPage.goto('/credential-profiles');
        await expect(authenticatedPage.getByRole('heading', { name: 'Credential Profiles' })).toBeVisible();
    });

    test('renders the credential profile list', async ({ authenticatedPage }) => {
        await expect(authenticatedPage.getByText('Dell iDRAC Default')).toBeVisible();
        await expect(authenticatedPage.getByText('HPE iLO Session')).toBeVisible();
    });

    test('creates a new credential profile', async ({ authenticatedPage }) => {
        await authenticatedPage.getByPlaceholder('rack-a-openbmc').fill('OpenBMC Lab Profile');
        await authenticatedPage.getByPlaceholder('OpenBMC|Dell').fill('OpenBMC');
        await authenticatedPage.getByRole('button', { name: 'Create Profile' }).click();

        await expect(authenticatedPage.getByText('OpenBMC Lab Profile')).toBeVisible();
    });

    test('validates an existing credential profile', async ({ authenticatedPage }) => {
        await authenticatedPage.getByRole('button', { name: 'Validate' }).first().click();

        await expect(authenticatedPage.getByText('Ready to claim')).toBeVisible();
        await expect(authenticatedPage.getByText('All credentials are valid')).toBeVisible();
    });
});
