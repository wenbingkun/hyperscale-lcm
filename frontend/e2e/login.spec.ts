import { expect, test } from '@playwright/test';
import { mockAllApiRoutes } from './fixtures/api-mocks';

test.describe('Login Page', () => {
    test('redirects unauthenticated users to /login', async ({ page }) => {
        await page.goto('/');

        await expect(page).toHaveURL(/\/login$/);
    });

    test('renders the login form fields', async ({ page }) => {
        await page.goto('/login');

        await expect(page.locator('#login-username')).toBeVisible();
        await expect(page.locator('#login-password')).toBeVisible();
        await expect(page.locator('#login-tenant')).toBeVisible();
        await expect(page.locator('#login-submit')).toBeVisible();
    });

    test('shows a validation error when required fields are empty', async ({ page }) => {
        await page.goto('/login');
        await page.locator('#login-submit').click();

        await expect(page.getByText('Please enter username and password')).toBeVisible();
    });

    test('logs in successfully and navigates to the dashboard', async ({ page }) => {
        await mockAllApiRoutes(page);
        await page.goto('/login');

        await page.locator('#login-username').fill('admin');
        await page.locator('#login-password').fill('admin123');
        await page.locator('#login-tenant').fill('default');
        await page.locator('#login-submit').click();

        await expect(page).toHaveURL(/\/$/);
        await expect(page.getByText('Total Nodes')).toBeVisible();
    });

    test('shows an error when login fails', async ({ page }) => {
        await mockAllApiRoutes(page, {
            loginStatus: 401,
            loginError: 'Invalid credentials',
        });
        await page.goto('/login');

        await page.locator('#login-username').fill('admin');
        await page.locator('#login-password').fill('wrong-password');
        await page.locator('#login-tenant').fill('default');
        await page.locator('#login-submit').click();

        await expect(page.getByText('Invalid credentials')).toBeVisible();
        await expect(page).toHaveURL(/\/login$/);
    });
});
