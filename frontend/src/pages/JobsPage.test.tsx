import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { JobsPage } from './JobsPage';
import { fetchJobs } from '../api/client';

vi.mock('../api/client', async () => {
    const actual = await vi.importActual<typeof import('../api/client')>('../api/client');
    return {
        ...actual,
        fetchJobs: vi.fn(),
    };
});

const mockedFetchJobs = vi.mocked(fetchJobs);

describe('JobsPage', () => {
    beforeEach(() => {
        vi.spyOn(globalThis, 'setInterval').mockImplementation(
            () => 0 as unknown as ReturnType<typeof setInterval>,
        );
        vi.spyOn(globalThis, 'clearInterval').mockImplementation(() => undefined);
    });

    it('renders jobs returned from the API and supports manual refresh', async () => {
        const user = userEvent.setup();

        mockedFetchJobs
            .mockResolvedValueOnce([
                {
                    id: 'job-1234567890',
                    name: 'LLM-Training',
                    status: 'RUNNING',
                    assignedNodeId: 'node-abcdef123456',
                    scheduledAt: '2026-04-08T12:00:00Z',
                },
            ])
            .mockResolvedValueOnce([
                {
                    id: 'job-2234567890',
                    name: 'Vision-Training',
                    status: 'SCHEDULED',
                    assignedNodeId: 'node-fedcba654321',
                    scheduledAt: '2026-04-08T13:00:00Z',
                },
            ]);

        render(
            <MemoryRouter>
                <JobsPage />
            </MemoryRouter>,
        );

        expect(await screen.findByText('LLM-Training')).toBeInTheDocument();
        expect(screen.getByText('RUNNING')).toBeInTheDocument();
        expect(screen.getByRole('link', { name: /LLM-Training/i })).toHaveAttribute('href', '/jobs/job-1234567890');

        await user.click(screen.getByRole('button'));

        await waitFor(() => expect(mockedFetchJobs).toHaveBeenCalledTimes(2));
        expect(screen.getByText('Vision-Training')).toBeInTheDocument();
        expect(screen.getByText('SCHEDULED')).toBeInTheDocument();
    });

    it('shows the empty state when there are no jobs in queue', async () => {
        mockedFetchJobs.mockResolvedValue([]);

        render(
            <MemoryRouter>
                <JobsPage />
            </MemoryRouter>,
        );

        expect(await screen.findByText('No active jobs found in queue.')).toBeInTheDocument();
    });
});
