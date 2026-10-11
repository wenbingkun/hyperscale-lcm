import { expect, test } from '@playwright/test';

// Requires: Core + Satellite running, a registered Satellite/device at LCM_REAL_TARGET_IP
// (PENDING or already APPROVED) reachable by the Satellite over SSH with the lab key.
const user = process.env.LCM_REAL_USER ?? 'admin';
const password = process.env.LCM_REAL_PASSWORD ?? 'admin123';
const targetIp = process.env.LCM_REAL_TARGET_IP;

test('login → approve device → run SSH SYSTEM_INFO → see real output', async ({ page }) => {
    test.skip(!targetIp, 'LCM_REAL_TARGET_IP is required');

    await page.goto('/login');
    await page.locator('#login-username').fill(user);
    await page.locator('#login-password').fill(password);
    await page.locator('#login-tenant').fill('default');
    await page.locator('#login-submit').click();
    await expect(page).toHaveURL(/\/$/);

    await page.goto('/discovery');
    const row = page.locator('tbody tr', { hasText: targetIp! });
    await expect(row).toBeVisible();
    const approve = row.locator('button[title="Approve"]');
    if (await approve.count()) {
        await approve.click();
    }
    await expect(row).toContainText(/APPROVED|MANAGED/);

    await page.goto('/');
    await page.locator('#job-mode').selectOption('ssh');
    const value = await page.locator('#ssh-target option', { hasText: targetIp! }).first().getAttribute('value');
    await page.locator('#ssh-target').selectOption(value!);
    const jobName = `real-ssh-${Date.now()}`;
    await page.getByPlaceholder('e.g. LLM-Training-v1').fill(jobName);
    await page.getByRole('button', { name: /launch job/i }).click();
    await expect(page.getByRole('button', { name: /submitted/i })).toBeVisible();

    await page.goto('/jobs');
    await page.getByText(jobName).first().click();
    await expect(page).toHaveURL(/\/jobs\/[0-9a-f-]+$/);
    await expect(page.getByText('Completed', { exact: true })).toBeVisible({ timeout: 60_000 });
    const output = page.getByTestId('ssh-result-output');
    await expect(output).toContainText('Linux');
    await expect(output).toContainText('Filesystem');
    await expect(page.getByText(targetIp!).first()).toBeVisible();
});
