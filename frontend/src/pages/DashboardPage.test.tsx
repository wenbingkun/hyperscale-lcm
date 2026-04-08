import { render, screen, waitFor } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { DashboardPage } from './DashboardPage';
import { fetchClusterStats, fetchJobStats } from '../api/client';

vi.mock('../api/client', async () => {
    const actual = await vi.importActual<typeof import('../api/client')>('../api/client');
    return {
        ...actual,
        fetchClusterStats: vi.fn(),
        fetchJobStats: vi.fn(),
    };
});

vi.mock('../components/SatelliteTable', () => ({
    SatelliteTable: () => <div data-testid="satellite-table-stub">Satellite table stub</div>,
}));

vi.mock('../components/JobSubmissionForm', () => ({
    JobSubmissionForm: () => <div data-testid="job-form-stub">Job form stub</div>,
}));

vi.mock('recharts', () => ({
    ResponsiveContainer: () => <div data-testid="chart-container" />,
    AreaChart: () => null,
    Area: () => null,
    XAxis: () => null,
    YAxis: () => null,
    CartesianGrid: () => null,
    Tooltip: () => null,
}));

const mockedFetchClusterStats = vi.mocked(fetchClusterStats);
const mockedFetchJobStats = vi.mocked(fetchJobStats);

describe('DashboardPage', () => {
    beforeEach(() => {
        mockedFetchClusterStats.mockResolvedValue({
            onlineNodes: 1234,
            totalNodes: 1280,
            totalCpuCores: 65536,
            totalGpus: 4096,
            totalMemoryGb: 524288,
        });
        mockedFetchJobStats.mockResolvedValue({
            pending: 10,
            scheduled: 8,
            running: 42,
            completed: 120,
            failed: 3,
        });

        vi.spyOn(globalThis, 'setInterval').mockImplementation(
            () => 0 as unknown as ReturnType<typeof setInterval>,
        );
        vi.spyOn(globalThis, 'clearInterval').mockImplementation(() => undefined);
    });

    it('loads cluster stats and renders the dashboard summary cards', async () => {
        render(<DashboardPage />);

        await waitFor(() => expect(mockedFetchClusterStats).toHaveBeenCalledTimes(1));
        expect(mockedFetchJobStats).toHaveBeenCalledTimes(1);

        expect(screen.getByText('Total Nodes')).toBeInTheDocument();
        expect(screen.getByText('1,234')).toBeInTheDocument();
        expect(screen.getByText('42')).toBeInTheDocument();
        expect(screen.getByText('1234 Online')).toBeInTheDocument();
        expect(screen.getByTestId('chart-container')).toBeInTheDocument();
        expect(screen.getByTestId('satellite-table-stub')).toBeInTheDocument();
        expect(screen.getByTestId('job-form-stub')).toBeInTheDocument();
    });
});
