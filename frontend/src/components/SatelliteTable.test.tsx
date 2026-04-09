import { render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { SatelliteTable } from './SatelliteTable';
import { fetchSatellites } from '../api/client';

vi.mock('../api/client', async () => {
    const actual = await vi.importActual<typeof import('../api/client')>('../api/client');
    return {
        ...actual,
        fetchSatellites: vi.fn(),
    };
});

const mockedFetchSatellites = vi.mocked(fetchSatellites);

describe('SatelliteTable', () => {
    beforeEach(() => {
        vi.spyOn(globalThis, 'setInterval').mockImplementation(
            () => 0 as unknown as ReturnType<typeof setInterval>,
        );
        vi.spyOn(globalThis, 'clearInterval').mockImplementation(() => undefined);
    });

    afterEach(() => {
        vi.restoreAllMocks();
    });

    it('renders discovered satellites after the initial load', async () => {
        mockedFetchSatellites.mockResolvedValue([
            {
                id: 'sat-1',
                hostname: 'gpu-worker-01',
                ipAddress: '10.0.0.11',
                osVersion: 'Ubuntu 24.04',
                agentVersion: '1.0.0',
                status: 'ONLINE',
                online: true,
                powerState: 'On',
                systemTemperatureCelsius: 54,
                lastHeartbeat: '2026-04-09T04:00:00Z',
            },
        ]);

        render(
            <MemoryRouter>
                <SatelliteTable />
            </MemoryRouter>,
        );

        expect(screen.getByText('Scanning Network...')).toBeInTheDocument();

        await waitFor(() => expect(mockedFetchSatellites).toHaveBeenCalledTimes(1));

        expect(screen.getByText('gpu-worker-01')).toBeInTheDocument();
        expect(screen.getByText('10.0.0.11')).toBeInTheDocument();
        expect(screen.getByText('On')).toBeInTheDocument();
        expect(screen.getByText('54°C')).toBeInTheDocument();
        expect(screen.getByRole('link', { name: 'gpu-worker-01' })).toHaveAttribute('href', '/satellites/sat-1');
    });

    it('renders an empty state when no satellites are returned', async () => {
        mockedFetchSatellites.mockResolvedValue([]);

        render(
            <MemoryRouter>
                <SatelliteTable />
            </MemoryRouter>,
        );

        await waitFor(() => expect(mockedFetchSatellites).toHaveBeenCalledTimes(1));

        expect(screen.getByText('No satellites discovered yet.')).toBeInTheDocument();
    });
});
