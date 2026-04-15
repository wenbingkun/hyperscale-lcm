import { expect, test } from './fixtures/test-fixtures';

test.describe('Jobs Page', () => {
    test('renders the job list with mocked jobs', async ({ authenticatedPage }) => {
        await authenticatedPage.goto('/jobs');

        await expect(authenticatedPage.getByText('Job Queues')).toBeVisible();
        await expect(authenticatedPage.getByRole('link', { name: 'llm-training-gpt4' })).toBeVisible();
        await expect(authenticatedPage.getByRole('link', { name: 'data-preprocessing' })).toBeVisible();
    });

    test('shows job status badges for multiple states', async ({ authenticatedPage }) => {
        await authenticatedPage.goto('/jobs');

        await expect(authenticatedPage.getByText('RUNNING')).toBeVisible();
        await expect(authenticatedPage.getByText('COMPLETED')).toBeVisible();
        await expect(authenticatedPage.getByText('FAILED', { exact: true })).toBeVisible();
    });

    test('submits a new job from the dashboard', async ({ authenticatedPage }) => {
        await authenticatedPage.goto('/');

        await authenticatedPage.getByPlaceholder('e.g. LLM-Training-v1').fill('playwright-smoke-job');
        await authenticatedPage.getByRole('button', { name: /launch job/i }).click();

        await expect(authenticatedPage.getByRole('button', { name: /submitted/i })).toBeVisible();
    });

    test('opens the job detail page from the jobs list', async ({ authenticatedPage }) => {
        await authenticatedPage.goto('/jobs');
        await authenticatedPage.getByRole('link', { name: 'llm-training-gpt4' }).click();

        await expect(authenticatedPage).toHaveURL(/\/jobs\/job-001$/);
        await expect(authenticatedPage.getByText('GPT-4 fine-tuning run')).toBeVisible();
        await expect(authenticatedPage.getByText('Execution Log')).toBeVisible();
    });
});
