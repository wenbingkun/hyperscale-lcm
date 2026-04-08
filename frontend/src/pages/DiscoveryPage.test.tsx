import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { DiscoveryPage } from './DiscoveryPage';
import {
    apiFetch,
    approveDiscoveredDevice,
    executeDiscoveryClaim,
    fetchDiscoveredDevices,
    fetchPendingDiscoveryCount,
    refreshDiscoveryClaimPlan,
} from '../api/client';

vi.mock('../api/client', async () => {
    const actual = await vi.importActual<typeof import('../api/client')>('../api/client');
    return {
        ...actual,
        apiFetch: vi.fn(),
        approveDiscoveredDevice: vi.fn(),
        executeDiscoveryClaim: vi.fn(),
        fetchDiscoveredDevices: vi.fn(),
        fetchPendingDiscoveryCount: vi.fn(),
        refreshDiscoveryClaimPlan: vi.fn(),
        rejectDiscoveredDevice: vi.fn(),
    };
});

const mockedApiFetch = vi.mocked(apiFetch);
const mockedApproveDiscoveredDevice = vi.mocked(approveDiscoveredDevice);
const mockedExecuteDiscoveryClaim = vi.mocked(executeDiscoveryClaim);
const mockedFetchDiscoveredDevices = vi.mocked(fetchDiscoveredDevices);
const mockedFetchPendingDiscoveryCount = vi.mocked(fetchPendingDiscoveryCount);
const mockedRefreshDiscoveryClaimPlan = vi.mocked(refreshDiscoveryClaimPlan);

const discoveredDevices = [
    {
        id: 'device-1',
        ipAddress: '10.0.0.10',
        hostname: 'node-10',
        discoveryMethod: 'ARP',
        status: 'PENDING',
        authStatus: 'AUTH_PENDING',
        claimStatus: 'READY_TO_CLAIM',
        inferredType: 'BMC',
        discoveredAt: '2026-04-08T00:00:00Z',
        manufacturerHint: 'Dell',
        modelHint: 'R760',
        credentialProfileName: 'idrac-default',
        recommendedRedfishTemplate: 'dell-idrac',
        claimMessage: 'Ready for automated first claim.',
        credentialSource: 'CMDB',
    },
    {
        id: 'device-2',
        ipAddress: '10.0.0.11',
        hostname: 'node-11',
        discoveryMethod: 'PING',
        status: 'APPROVED',
        authStatus: 'PROFILE_MATCHED',
        claimStatus: 'DISCOVERED',
        inferredType: 'SERVER',
        discoveredAt: '2026-04-08T01:30:00Z',
        manufacturerHint: 'HPE',
        modelHint: 'DL380',
        claimMessage: 'Waiting for auth verification.',
    },
];

describe('DiscoveryPage', () => {
    beforeEach(() => {
        mockedFetchDiscoveredDevices.mockResolvedValue(discoveredDevices);
        mockedFetchPendingDiscoveryCount.mockResolvedValue({ count: 1 });
        mockedRefreshDiscoveryClaimPlan.mockResolvedValue(undefined);
        mockedExecuteDiscoveryClaim.mockResolvedValue(undefined);
        mockedApproveDiscoveredDevice.mockResolvedValue(undefined);
        mockedApiFetch.mockResolvedValue({
            ok: true,
            json: async () => ({ running: false }),
        } as Response);

        vi.spyOn(globalThis, 'setInterval').mockImplementation(
            () => 0 as unknown as ReturnType<typeof setInterval>,
        );
        vi.spyOn(globalThis, 'clearInterval').mockImplementation(() => undefined);
    });

    it('loads discovered devices and filters them through the search box', async () => {
        const user = userEvent.setup();

        render(<DiscoveryPage />);

        expect(await screen.findByText('10.0.0.10')).toBeInTheDocument();
        expect(screen.getByText('10.0.0.11')).toBeInTheDocument();
        expect(screen.getByText('Scan Network')).toBeInTheDocument();

        await user.type(
            screen.getByPlaceholderText(/Search by IP, host, vendor, model, profile, template/i),
            'hpe',
        );

        expect(screen.queryByText('10.0.0.10')).not.toBeInTheDocument();
        expect(screen.getByText('10.0.0.11')).toBeInTheDocument();
    });

    it('runs claim-planning actions against the selected discovered device', async () => {
        const user = userEvent.setup();

        render(<DiscoveryPage />);

        const readyRow = (await screen.findByText('10.0.0.10')).closest('tr');
        expect(readyRow).not.toBeNull();

        await user.click(within(readyRow as HTMLElement).getByRole('button', { name: /replan/i }));
        await waitFor(() => expect(mockedRefreshDiscoveryClaimPlan).toHaveBeenCalledWith('device-1'));

        await user.click(within(readyRow as HTMLElement).getByRole('button', { name: /claim/i }));
        await waitFor(() => expect(mockedExecuteDiscoveryClaim).toHaveBeenCalledWith('device-1'));

        await user.click(within(readyRow as HTMLElement).getByTitle('Approve'));
        await waitFor(() => expect(mockedApproveDiscoveredDevice).toHaveBeenCalledWith('device-1'));
    });
});
