import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { DiscoveryPage } from './DiscoveryPage';
import {
    apiFetch,
    executeBmcClaim,
    executeBmcPowerAction,
    fetchBmcCapabilities,
    approveDiscoveredDevice,
    fetchDiscoveredDevices,
    fetchPendingDiscoveryCount,
    refreshDiscoveryClaimPlan,
    rotateBmcCredentials,
} from '../api/client';

const mockUseAuth = vi.fn();

vi.mock('../api/client', async () => {
    const actual = await vi.importActual<typeof import('../api/client')>('../api/client');
    return {
        ...actual,
        apiFetch: vi.fn(),
        executeBmcClaim: vi.fn(),
        executeBmcPowerAction: vi.fn(),
        fetchBmcCapabilities: vi.fn(),
        approveDiscoveredDevice: vi.fn(),
        fetchDiscoveredDevices: vi.fn(),
        fetchPendingDiscoveryCount: vi.fn(),
        refreshDiscoveryClaimPlan: vi.fn(),
        rejectDiscoveredDevice: vi.fn(),
        rotateBmcCredentials: vi.fn(),
    };
});

vi.mock('../contexts/AuthContext', () => ({
    useAuth: () => mockUseAuth(),
}));

const mockedApiFetch = vi.mocked(apiFetch);
const mockedExecuteBmcClaim = vi.mocked(executeBmcClaim);
const mockedExecuteBmcPowerAction = vi.mocked(executeBmcPowerAction);
const mockedFetchBmcCapabilities = vi.mocked(fetchBmcCapabilities);
const mockedApproveDiscoveredDevice = vi.mocked(approveDiscoveredDevice);
const mockedFetchDiscoveredDevices = vi.mocked(fetchDiscoveredDevices);
const mockedFetchPendingDiscoveryCount = vi.mocked(fetchPendingDiscoveryCount);
const mockedRefreshDiscoveryClaimPlan = vi.mocked(refreshDiscoveryClaimPlan);
const mockedRotateBmcCredentials = vi.mocked(rotateBmcCredentials);

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
        bmcAddress: '10.0.0.210',
        credentialProfileName: 'idrac-default',
        recommendedRedfishTemplate: 'dell-idrac',
        claimMessage: 'Ready for automated first claim.',
        credentialSource: 'CMDB',
        lastSuccessfulAuthMode: 'SESSION_PREFERRED',
        lastCapabilityProbeAt: '2026-04-10T02:00:00Z',
        bmcCapabilities: {
            sessionAuth: true,
            powerControl: true,
            systemCount: 1,
            resetActions: ['GracefulRestart', 'ForceRestart'],
        },
    },
    {
        id: 'device-2',
        ipAddress: '10.0.0.11',
        hostname: 'node-11',
        discoveryMethod: 'PING',
        status: 'APPROVED',
        authStatus: 'PROFILE_MATCHED',
        claimStatus: 'MANAGED',
        inferredType: 'BMC',
        discoveredAt: '2026-04-08T01:30:00Z',
        bmcAddress: '10.0.0.211',
        manufacturerHint: 'HPE',
        modelHint: 'DL380',
        claimMessage: 'Managed account already owned by the platform.',
        lastSuccessfulAuthMode: 'BASIC_ONLY',
        bmcCapabilities: {
            sessionAuth: false,
            powerControl: true,
            systemCount: 2,
            resetActions: ['GracefulShutdown', 'GracefulRestart'],
        },
    },
];

describe('DiscoveryPage', () => {
    beforeEach(() => {
        mockUseAuth.mockReturnValue({
            user: {
                username: 'admin',
                roles: ['ADMIN'],
                tenantId: 'default',
            },
        });
        mockedFetchDiscoveredDevices.mockResolvedValue(discoveredDevices);
        mockedFetchPendingDiscoveryCount.mockResolvedValue({ count: 1 });
        mockedRefreshDiscoveryClaimPlan.mockResolvedValue(undefined);
        mockedApproveDiscoveredDevice.mockResolvedValue(undefined);
        mockedExecuteBmcClaim.mockResolvedValue({
            ...discoveredDevices[0],
            claimStatus: 'CLAIMED',
            claimMessage: 'Redfish claim completed successfully.',
        });
        mockedFetchBmcCapabilities.mockResolvedValue({
            deviceId: 'device-1',
            ipAddress: '10.0.0.10',
            bmcAddress: '10.0.0.210',
            manufacturer: 'Dell',
            model: 'R760',
            lastSuccessfulAuthMode: 'SESSION_PREFERRED',
            lastCapabilityProbeAt: '2026-04-12T03:00:00Z',
            capabilities: {
                sessionAuth: true,
                powerControl: true,
                systemCount: 2,
                resetActions: ['GracefulRestart', 'ForceRestart'],
            },
        });
        mockedExecuteBmcPowerAction.mockResolvedValue({
            status: 'DRY_RUN',
            action: 'ForceRestart',
            systemId: 'System-1',
            targetUri: '/redfish/v1/Systems/System-1/Actions/ComputerSystem.Reset',
            authMode: 'SESSION_PREFERRED',
            allowedValues: ['GracefulRestart', 'ForceRestart'],
            message: 'Dry run; no BMC mutation performed.',
            replayed: false,
        });
        mockedExecuteBmcPowerAction.mockImplementation(async (_id, request, options) => {
            if (options?.dryRun) {
                return {
                    status: 'DRY_RUN',
                    action: request.action,
                    systemId: request.systemId ?? 'System-1',
                    targetUri: '/redfish/v1/Systems/System-1/Actions/ComputerSystem.Reset',
                    authMode: 'SESSION_PREFERRED',
                    allowedValues: ['GracefulRestart', 'ForceRestart'],
                    message: 'Dry run; no BMC mutation performed.',
                    replayed: false,
                };
            }
            return {
                status: 'ACCEPTED',
                action: request.action,
                systemId: request.systemId ?? 'System-1',
                targetUri: '/redfish/v1/Systems/System-1/Actions/ComputerSystem.Reset',
                authMode: 'SESSION_PREFERRED',
                taskLocation: '/redfish/v1/TaskService/Tasks/42',
                message: 'Power action accepted.',
                replayed: false,
            };
        });
        mockedRotateBmcCredentials.mockResolvedValue({
            deviceId: 'device-2',
            status: 'SUCCESS',
            message: 'Managed BMC credentials rotated.',
        });
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

        await user.click(within(readyRow as HTMLElement).getByRole('button', { name: /inspect/i }));
        await waitFor(() => expect(mockedFetchBmcCapabilities).toHaveBeenCalledWith('device-1'));

        await user.selectOptions(
            within(readyRow as HTMLElement).getByLabelText('Power action for 10.0.0.10'),
            'ForceRestart',
        );
        await user.type(within(readyRow as HTMLElement).getByLabelText('System ID for 10.0.0.10'), 'System-1');
        await user.click(within(readyRow as HTMLElement).getByRole('button', { name: /dry run/i }));
        await waitFor(() => expect(mockedExecuteBmcPowerAction).toHaveBeenNthCalledWith(1,
            'device-1',
            { action: 'ForceRestart', systemId: 'System-1' },
            { dryRun: true },
        ));
        await user.type(
            within(readyRow as HTMLElement).getByLabelText('Confirm power action for 10.0.0.10'),
            'ForceRestart',
        );
        await user.click(within(readyRow as HTMLElement).getByRole('button', { name: /^execute$/i }));
        await waitFor(() => expect(mockedExecuteBmcPowerAction).toHaveBeenNthCalledWith(2,
            'device-1',
            { action: 'ForceRestart', systemId: 'System-1' },
        ));

        await user.click(within(readyRow as HTMLElement).getByRole('button', { name: /claim/i }));
        await waitFor(() => expect(mockedExecuteBmcClaim).toHaveBeenCalledWith('device-1'));

        await user.click(within(readyRow as HTMLElement).getByTitle('Approve'));
        await waitFor(() => expect(mockedApproveDiscoveredDevice).toHaveBeenCalledWith('device-1'));

        const managedRow = (await screen.findByText('10.0.0.11')).closest('tr');
        expect(managedRow).not.toBeNull();

        await user.click(within(managedRow as HTMLElement).getByRole('button', { name: /rotate/i }));
        await waitFor(() => expect(mockedRotateBmcCredentials).toHaveBeenCalledWith('device-2'));

        expect(await screen.findByText('ACCEPTED')).toBeInTheDocument();
        expect(screen.getByText('Task: /redfish/v1/TaskService/Tasks/42')).toBeInTheDocument();
    });

    it('keeps BMC power execution locked behind the ADMIN role', async () => {
        mockUseAuth.mockReturnValue({
            user: {
                username: 'ops',
                roles: ['OPERATOR'],
                tenantId: 'default',
            },
        });

        render(<DiscoveryPage />);

        const row = (await screen.findByText('10.0.0.10')).closest('tr');
        expect(row).not.toBeNull();
        expect(within(row as HTMLElement).queryByRole('button', { name: /claim/i })).not.toBeInTheDocument();
        expect(within(row as HTMLElement).queryByRole('button', { name: /dry run/i })).not.toBeInTheDocument();
        expect(within(row as HTMLElement).getByText(/require the `ADMIN` role/i)).toBeInTheDocument();
    });
});
