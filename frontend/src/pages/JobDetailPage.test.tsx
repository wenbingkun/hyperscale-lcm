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
        expect(await screen.findByText('Completed')).toBeInTheDocument();
        expect(screen.getByText('0')).toBeInTheDocument();
    });

    it('shows SSH target and result output as plain text', async () => {
        mockedFetchJobs.mockResolvedValue([
            {
                id: 'job-ssh',
                name: 'ssh-info',
                status: 'COMPLETED',
                executionType: 'SSH',
                targetHost: '10.0.0.5',
                resultOutput: 'Linux lab <b>x</b>',
                exitCode: 0,
            },
        ]);

        render(
            <MemoryRouter initialEntries={['/jobs/job-ssh']}>
                <Routes>
                    <Route path="/jobs/:id" element={<JobDetailPage />} />
                </Routes>
            </MemoryRouter>,
        );

        expect(await screen.findByText('10.0.0.5')).toBeInTheDocument();
        expect(screen.getByTestId('ssh-result-output')).toHaveTextContent('Linux lab <b>x</b>');
    });

    it('flags an UNKNOWN job as needing manual verification', async () => {
        mockedFetchJobs.mockResolvedValue([
            {
                id: 'job-unknown',
                name: 'maybe-ran',
                status: 'UNKNOWN',
                assignedNodeId: 'node-1',
                errorMessage: 'no status reported in time',
            },
        ]);

        render(
            <MemoryRouter initialEntries={['/jobs/job-unknown']}>
                <Routes>
                    <Route path="/jobs/:id" element={<JobDetailPage />} />
                </Routes>
            </MemoryRouter>,
        );

        expect(await screen.findByText('Result unknown')).toBeInTheDocument();
        expect(screen.getByTestId('unknown-notice')).toHaveTextContent('no status reported in time');
    });
});
