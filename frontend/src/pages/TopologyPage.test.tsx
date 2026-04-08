import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { TopologyPage } from './TopologyPage';
import { fetchJobs, fetchSatellites } from '../api/client';
import { useWebSocketContext } from '../contexts/WebSocketContext';
import type { WebSocketMessage } from '../hooks/useWebSocket';

vi.mock('../api/client', async () => {
    const actual = await vi.importActual<typeof import('../api/client')>('../api/client');
    return {
        ...actual,
        fetchJobs: vi.fn(),
        fetchSatellites: vi.fn(),
    };
});

vi.mock('../contexts/WebSocketContext', () => ({
    useWebSocketContext: vi.fn(),
}));

const mockedFetchJobs = vi.mocked(fetchJobs);
const mockedFetchSatellites = vi.mocked(fetchSatellites);
const mockedUseWebSocketContext = vi.mocked(useWebSocketContext);

const topologyNodes = [
    {
        id: 'node-1',
        hostname: 'gpu-a100-01',
        ipAddress: '10.0.0.21',
        osVersion: 'ubuntu',
        agentVersion: '1.0.0',
        status: 'ONLINE',
        online: true,
        gpuCount: 4,
        gpuModel: 'A100',
        rackId: 'rack-01',
        zoneId: 'zone-a',
        gpuTopology: 'NVSwitch',
        nvlinkBandwidthGbps: 900,
        ibFabricId: 'fabric-a',
        systemModel: 'HGX-A100',
    },
    {
        id: 'node-2',
        hostname: 'gpu-idle-01',
        ipAddress: '10.0.0.22',
        osVersion: 'ubuntu',
        agentVersion: '1.0.0',
        status: 'ONLINE',
        online: true,
        gpuCount: 2,
        gpuModel: 'A100',
        rackId: 'rack-02',
        zoneId: 'zone-a',
        gpuTopology: 'NVLink',
        nvlinkBandwidthGbps: 600,
        ibFabricId: 'fabric-a',
        systemModel: 'HGX-A100',
    },
    {
        id: 'node-3',
        hostname: 'gpu-offline-01',
        ipAddress: '10.0.1.21',
        osVersion: 'ubuntu',
        agentVersion: '1.0.0',
        status: 'OFFLINE',
        online: false,
        gpuCount: 8,
        gpuModel: 'H100',
        rackId: 'rack-09',
        zoneId: 'zone-b',
        gpuTopology: 'PCIe',
        nvlinkBandwidthGbps: 0,
        ibFabricId: 'fabric-b',
        systemModel: 'HGX-H100',
    },
];

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

describe('TopologyPage', () => {
    beforeEach(() => {
        vi.spyOn(globalThis, 'setInterval').mockImplementation(
            () => 0 as unknown as ReturnType<typeof setInterval>,
        );
        vi.spyOn(globalThis, 'clearInterval').mockImplementation(() => undefined);
        mockedUseWebSocketContext.mockReturnValue(createWebSocketContext());
        mockedFetchSatellites.mockResolvedValue(topologyNodes);
    });

    it('renders zone, rack, NVLink and IB fabric allocation details from live data', async () => {
        mockedFetchJobs.mockResolvedValue([
            {
                id: 'job-1',
                name: 'LLM-Training',
                status: 'RUNNING',
                assignedNodeId: 'node-1',
                requiredGpuCount: 2,
            },
            {
                id: 'job-2',
                name: 'Vision-Training',
                status: 'SCHEDULED',
                assignedNodeId: 'node-3',
                requiredGpuCount: 3,
            },
        ]);

        render(<TopologyPage />);

        expect(await screen.findByText('Zone zone-a')).toBeInTheDocument();
        expect(screen.getByText('rack-01')).toBeInTheDocument();
        expect(screen.getByText('IB Fabric Overview')).toBeInTheDocument();
        expect(screen.getByText('fabric-a')).toBeInTheDocument();
        expect(screen.getByText('900 GB/s NVLink')).toBeInTheDocument();
        expect(screen.getByText('Allocated 2 / 4 GPUs')).toBeInTheDocument();
        expect(screen.getByText(/LLM-Training · 2 GPU/)).toBeInTheDocument();
        expect(screen.getAllByTestId('gpu-slot-node-1')).toHaveLength(4);
        expect(screen.getAllByTestId('gpu-slot-node-3')).toHaveLength(8);
    });

    it('filters idle nodes and refreshes immediately when websocket job events arrive', async () => {
        let webSocketContext = createWebSocketContext();
        mockedUseWebSocketContext.mockImplementation(() => webSocketContext);

        mockedFetchJobs
            .mockResolvedValueOnce([
                {
                    id: 'job-1',
                    name: 'LLM-Training',
                    status: 'RUNNING',
                    assignedNodeId: 'node-1',
                    requiredGpuCount: 2,
                },
            ])
            .mockResolvedValueOnce([]);

        const user = userEvent.setup();
        const { rerender } = render(<TopologyPage />);

        expect(await screen.findByText('gpu-a100-01')).toBeInTheDocument();

        await user.click(screen.getByRole('button', { name: /idle/i }));
        expect(screen.getByText('gpu-idle-01')).toBeInTheDocument();
        expect(screen.queryByText('gpu-a100-01')).not.toBeInTheDocument();
        expect(screen.queryByText('gpu-offline-01')).not.toBeInTheDocument();

        webSocketContext = createWebSocketContext({
            type: 'JOB_STATUS',
            payload: { jobId: 'job-1', status: 'COMPLETED' },
        });

        rerender(<TopologyPage />);

        await waitFor(() => expect(mockedFetchJobs).toHaveBeenCalledTimes(2));
        expect(screen.getByText('No active GPU allocations are being tracked right now.')).toBeInTheDocument();
    });
});
