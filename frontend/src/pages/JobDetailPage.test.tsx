import { render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { JobDetailPage } from './JobDetailPage';
import { fetchJobs } from '../api/client';
import { useWebSocketContext } from '../contexts/WebSocketContext';
import type { WebSocketMessage } from '../hooks/useWebSocket';

vi.mock('../api/client', async () => {
    const actual = await vi.importActual<typeof import('../api/client')>('../api/client');
    return {
        ...actual,
        fetchJobs: vi.fn(),
    };
});

vi.mock('../contexts/WebSocketContext', () => ({
    useWebSocketContext: vi.fn(),
}));

const mockedFetchJobs = vi.mocked(fetchJobs);
const mockedUseWebSocketContext = vi.mocked(useWebSocketContext);

function createWebSocketContext(lastEvent: WebSocketMessage | null = null) {
    return {
        isConnected: true,
        onlineNodes: 0,
        alerts: [],
        lastEvent,
        clearAlerts: vi.fn(),
        dismissAlert: vi.fn(),
    };
}

describe('JobDetailPage', () => {
    beforeEach(() => {
        mockedUseWebSocketContext.mockReturnValue(createWebSocketContext());
    });

    it('refreshes the job detail when a matching job status event arrives', async () => {
        let webSocketContext = createWebSocketContext();
        mockedUseWebSocketContext.mockImplementation(() => webSocketContext);
        mockedFetchJobs
            .mockResolvedValueOnce([
                {
                    id: 'job-123',
                    name: 'LLM-Training',
                    status: 'SCHEDULED',
                    assignedNodeId: 'node-1',
                    scheduledAt: '2026-04-08T12:00:00Z',
                },
            ])
            .mockResolvedValueOnce([
                {
                    id: 'job-123',
                    name: 'LLM-Training',
                    status: 'COMPLETED',
                    assignedNodeId: 'node-1',
                    scheduledAt: '2026-04-08T12:00:00Z',
                    completedAt: '2026-04-08T12:05:00Z',
                    exitCode: 0,
                },
            ]);

        const { rerender } = render(
            <MemoryRouter initialEntries={['/jobs/job-123']}>
                <Routes>
                    <Route path="/jobs/:id" element={<JobDetailPage />} />
                </Routes>
            </MemoryRouter>,
        );

        expect(await screen.findByText('Scheduled')).toBeInTheDocument();

        webSocketContext = createWebSocketContext({
            type: 'JOB_STATUS',
            payload: { jobId: 'job-123', status: 'COMPLETED' },
        });

        rerender(
            <MemoryRouter initialEntries={['/jobs/job-123']}>
                <Routes>
                    <Route path="/jobs/:id" element={<JobDetailPage />} />
                </Routes>
            </MemoryRouter>,
        );

        await waitFor(() => expect(mockedFetchJobs).toHaveBeenCalledTimes(2));
        expect(screen.getByText('Completed')).toBeInTheDocument();
        expect(screen.getByText('0')).toBeInTheDocument();
    });
});
