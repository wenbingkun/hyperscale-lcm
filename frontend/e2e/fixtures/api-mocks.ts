import type { Page } from '@playwright/test';
import type {
    BmcCapabilitySnapshot,
    ClusterStats,
    CredentialProfile,
    CredentialProfileRequest,
    CredentialProfileValidation,
    DiscoveredDevice,
    Job,
    JobRequest,
    JobStats,
    RedfishTemplateSummary,
    Satellite,
} from '../../src/api/client';

type TenantSummary = {
    id: string;
    name: string;
    description: string;
    status: string;
    cpuQuota: number;
    memoryQuotaGb: number;
    gpuQuota: number;
    maxConcurrentJobs: number;
    cpuUsed: number;
    memoryUsedGb: number;
    gpuUsed: number;
    runningJobs: number;
};

export type MockApiOverrides = Partial<{
    clusterStats: ClusterStats;
    jobStats: JobStats;
    satellites: Satellite[];
    jobs: Job[];
    discoveredDevices: DiscoveredDevice[];
    credentialProfiles: CredentialProfile[];
    redfishTemplates: RedfishTemplateSummary[];
    tenants: TenantSummary[];
    loginStatus: number;
    loginError: string;
    loginToken: string;
}>;

function base64UrlEncode(value: string): string {
    return Buffer.from(value)
        .toString('base64')
        .replace(/\+/g, '-')
        .replace(/\//g, '_')
        .replace(/=+$/g, '');
}

function createJwt(overrides: Record<string, unknown> = {}): string {
    const header = base64UrlEncode(JSON.stringify({ alg: 'HS256', typ: 'JWT' }));
    const payload = base64UrlEncode(
        JSON.stringify({
            upn: 'admin',
            groups: ['ADMIN'],
            exp: Math.floor(Date.now() / 1000) + 7_200,
            ...overrides,
        }),
    );

    return `${header}.${payload}.mock-signature`;
}

export const MOCK_JWT = createJwt();

export const MOCK_USER = {
    username: 'admin',
    roles: ['ADMIN'],
    tenantId: 'default',
};

export const MOCK_CLUSTER_STATS: ClusterStats = {
    onlineNodes: 42,
    totalNodes: 50,
    totalCpuCores: 1_600,
    totalGpus: 200,
    totalMemoryGb: 12_800,
};

export const MOCK_JOB_STATS: JobStats = {
    pending: 3,
    scheduled: 2,
    running: 5,
    completed: 120,
    failed: 2,
};

export const MOCK_SATELLITES: Satellite[] = [
    {
        id: 'sat-001',
        hostname: 'gpu-node-alpha',
        ipAddress: '10.0.1.10',
        osVersion: 'Ubuntu 22.04',
        agentVersion: '1.2.0',
        status: 'ONLINE',
        online: true,
        cpuCores: 64,
        gpuCount: 8,
        gpuModel: 'A100-SXM4-80GB',
        memoryGb: 512,
        rackId: 'rack-01',
        zoneId: 'zone-a',
        gpuTopology: 'NVSwitch',
        nvlinkBandwidthGbps: 600,
        ibFabricId: 'ib-fabric-1',
        model: 'Dell PowerEdge XE9680',
        lastHeartbeat: new Date().toISOString(),
        powerState: 'On',
        systemTemperatureCelsius: 54,
    },
    {
        id: 'sat-002',
        hostname: 'gpu-node-beta',
        ipAddress: '10.0.1.11',
        osVersion: 'Ubuntu 22.04',
        agentVersion: '1.2.0',
        status: 'ONLINE',
        online: true,
        cpuCores: 128,
        gpuCount: 8,
        gpuModel: 'H100-SXM5',
        memoryGb: 1_024,
        rackId: 'rack-02',
        zoneId: 'zone-a',
        gpuTopology: 'NVLink',
        nvlinkBandwidthGbps: 900,
        ibFabricId: 'ib-fabric-1',
        model: 'HPE Cray XD670',
        lastHeartbeat: new Date().toISOString(),
        powerState: 'On',
        systemTemperatureCelsius: 59,
    },
    {
        id: 'sat-003',
        hostname: 'cpu-node-gamma',
        ipAddress: '10.0.2.20',
        osVersion: 'Rocky Linux 9',
        agentVersion: '1.1.9',
        status: 'OFFLINE',
        online: false,
        cpuCores: 32,
        gpuCount: 0,
        memoryGb: 256,
        rackId: 'rack-03',
        zoneId: 'zone-b',
        ibFabricId: 'ib-fabric-2',
        model: 'Lenovo ThinkSystem SR650',
        powerState: 'Off',
    },
];

export const MOCK_JOBS: Job[] = [
    {
        id: 'job-001',
        name: 'llm-training-gpt4',
        description: 'GPT-4 fine-tuning run',
        status: 'RUNNING',
        assignedNodeId: 'sat-001',
        requiredGpuCount: 8,
        scheduledAt: new Date().toISOString(),
    },
    {
        id: 'job-002',
        name: 'data-preprocessing',
        description: 'Clean and tokenize dataset',
        status: 'COMPLETED',
        assignedNodeId: 'sat-002',
        requiredGpuCount: 2,
        completedAt: new Date().toISOString(),
        exitCode: 0,
    },
    {
        id: 'job-003',
        name: 'inference-benchmark',
        description: 'Latency and throughput benchmark',
        status: 'PENDING',
        requiredGpuCount: 4,
    },
    {
        id: 'job-004',
        name: 'failed-experiment',
        description: 'Validation run with a synthetic failure',
        status: 'FAILED',
        assignedNodeId: 'sat-003',
        requiredGpuCount: 1,
        exitCode: 1,
    },
];

export const MOCK_DISCOVERED_DEVICES: DiscoveredDevice[] = [
    {
        id: 'dev-001',
        ipAddress: '192.168.1.100',
        macAddress: 'AA:BB:CC:DD:EE:01',
        hostname: 'unknown-server-1',
        discoveryMethod: 'ARP_SCAN',
        status: 'PENDING',
        inferredType: 'SERVER',
        discoveredAt: new Date().toISOString(),
        claimStatus: 'DISCOVERED',
        authStatus: 'PENDING',
    },
    {
        id: 'dev-002',
        ipAddress: '192.168.1.101',
        macAddress: 'AA:BB:CC:DD:EE:02',
        hostname: 'dell-r750-bmc',
        discoveryMethod: 'DHCP_LISTENER',
        status: 'APPROVED',
        inferredType: 'BMC',
        discoveredAt: new Date().toISOString(),
        bmcAddress: '192.168.1.201',
        manufacturerHint: 'Dell Inc.',
        modelHint: 'PowerEdge R750',
        credentialProfileName: 'Dell iDRAC Default',
        recommendedRedfishTemplate: 'dell-idrac',
        claimStatus: 'READY_TO_CLAIM',
        authStatus: 'PROFILE_MATCHED',
        claimMessage: 'Ready to claim using Dell iDRAC Default.',
    },
];

export const MOCK_CREDENTIAL_PROFILES: CredentialProfile[] = [
    {
        id: 'cp-001',
        name: 'Dell iDRAC Default',
        protocol: 'REDFISH',
        enabled: true,
        autoClaim: false,
        priority: 10,
        vendorPattern: 'Dell.*',
        redfishTemplate: 'dell-idrac',
        redfishAuthMode: 'BASIC_ONLY',
        managedAccountEnabled: false,
        createdAt: new Date().toISOString(),
    },
    {
        id: 'cp-002',
        name: 'HPE iLO Session',
        protocol: 'REDFISH',
        enabled: true,
        autoClaim: true,
        priority: 20,
        vendorPattern: 'HPE.*',
        redfishTemplate: 'hpe-ilo',
        redfishAuthMode: 'SESSION_ONLY',
        managedAccountEnabled: true,
        managedAccountRoleId: 'Operator',
        createdAt: new Date().toISOString(),
    },
];

export const MOCK_REDFISH_TEMPLATES: RedfishTemplateSummary[] = [
    {
        name: 'openbmc-baseline',
        description: 'OpenBMC baseline profile',
        priority: 5,
        manufacturerPatterns: ['OpenBMC'],
        modelPatterns: ['.*'],
        source: 'BUILTIN',
    },
    {
        name: 'dell-idrac',
        description: 'Dell iDRAC 9/10',
        priority: 10,
        manufacturerPatterns: ['Dell.*'],
        modelPatterns: ['PowerEdge.*'],
        source: 'BUILTIN',
    },
    {
        name: 'hpe-ilo',
        description: 'HPE iLO 5/6',
        priority: 20,
        manufacturerPatterns: ['HPE.*'],
        modelPatterns: ['ProLiant.*'],
        source: 'BUILTIN',
    },
];

export const MOCK_TENANTS: TenantSummary[] = [
    {
        id: 'default',
        name: 'Default Tenant',
        description: 'Default shared infrastructure tenant',
        status: 'ACTIVE',
        cpuQuota: 2_000,
        memoryQuotaGb: 16_384,
        gpuQuota: 240,
        maxConcurrentJobs: 50,
        cpuUsed: 640,
        memoryUsedGb: 4_096,
        gpuUsed: 72,
        runningJobs: 12,
    },
    {
        id: 'team-ml',
        name: 'ML Research Team',
        description: 'Tenant for model experimentation',
        status: 'ACTIVE',
        cpuQuota: 1_024,
        memoryQuotaGb: 8_192,
        gpuQuota: 120,
        maxConcurrentJobs: 20,
        cpuUsed: 384,
        memoryUsedGb: 2_048,
        gpuUsed: 40,
        runningJobs: 5,
    },
];

const DEFAULT_BMC_CAPABILITIES: Record<string, BmcCapabilitySnapshot> = {
    'dev-001': {
        deviceId: 'dev-001',
        ipAddress: '192.168.1.100',
        capabilities: {
            sessionAuth: false,
            powerControl: false,
            systemCount: 0,
            resetActions: [],
        },
    },
    'dev-002': {
        deviceId: 'dev-002',
        ipAddress: '192.168.1.101',
        bmcAddress: '192.168.1.201',
        manufacturer: 'Dell Inc.',
        model: 'PowerEdge R750',
        recommendedRedfishTemplate: 'dell-idrac',
        lastSuccessfulAuthMode: 'BASIC',
        lastCapabilityProbeAt: new Date().toISOString(),
        capabilities: {
            sessionAuth: true,
            powerControl: true,
            systemCount: 1,
            resetActions: ['GracefulRestart', 'ForceRestart', 'On', 'ForceOff'],
        },
    },
};

type MockState = {
    clusterStats: ClusterStats;
    jobStats: JobStats;
    satellites: Satellite[];
    jobs: Job[];
    discoveredDevices: DiscoveredDevice[];
    credentialProfiles: CredentialProfile[];
    redfishTemplates: RedfishTemplateSummary[];
    tenants: TenantSummary[];
};

function cloneState(overrides: MockApiOverrides = {}): MockState {
    return {
        clusterStats: structuredClone(overrides.clusterStats ?? MOCK_CLUSTER_STATS),
        jobStats: structuredClone(overrides.jobStats ?? MOCK_JOB_STATS),
        satellites: structuredClone(overrides.satellites ?? MOCK_SATELLITES),
        jobs: structuredClone(overrides.jobs ?? MOCK_JOBS),
        discoveredDevices: structuredClone(overrides.discoveredDevices ?? MOCK_DISCOVERED_DEVICES),
        credentialProfiles: structuredClone(overrides.credentialProfiles ?? MOCK_CREDENTIAL_PROFILES),
        redfishTemplates: structuredClone(overrides.redfishTemplates ?? MOCK_REDFISH_TEMPLATES),
        tenants: structuredClone(overrides.tenants ?? MOCK_TENANTS),
    };
}

function json(body: unknown, status = 200): { status: number; contentType: string; body: string } {
    return {
        status,
        contentType: 'application/json',
        body: JSON.stringify(body),
    };
}

function buildValidation(profile: CredentialProfile): CredentialProfileValidation {
    return {
        id: profile.id,
        name: profile.name,
        ready: true,
        credentialSource: profile.sourceType,
        message: 'All credentials are valid',
        usernameReady: true,
        usernameMessage: 'OK',
        passwordReady: true,
        passwordMessage: 'OK',
        managedAccountEnabled: profile.managedAccountEnabled,
        managedAccountReady: profile.managedAccountEnabled ? true : false,
        managedAccountMessage: profile.managedAccountEnabled ? 'OK' : 'N/A',
        managedUsernameReady: profile.managedAccountEnabled ? true : false,
        managedUsernameMessage: profile.managedAccountEnabled ? 'OK' : 'N/A',
        managedPasswordReady: profile.managedAccountEnabled ? true : false,
        managedPasswordMessage: profile.managedAccountEnabled ? 'OK' : 'N/A',
    };
}

function toCredentialProfile(id: string, request: CredentialProfileRequest): CredentialProfile {
    return {
        id,
        name: request.name,
        protocol: request.protocol ?? 'REDFISH',
        enabled: request.enabled ?? true,
        autoClaim: request.autoClaim ?? false,
        priority: request.priority ?? 100,
        sourceType: request.sourceType,
        externalRef: request.externalRef,
        vendorPattern: request.vendorPattern,
        modelPattern: request.modelPattern,
        subnetCidr: request.subnetCidr,
        deviceType: request.deviceType,
        hostnamePattern: request.hostnamePattern,
        ipAddressPattern: request.ipAddressPattern,
        macAddressPattern: request.macAddressPattern,
        redfishTemplate: request.redfishTemplate,
        redfishAuthMode: request.redfishAuthMode,
        usernameSecretRef: request.usernameSecretRef,
        passwordSecretRef: request.passwordSecretRef,
        managedAccountEnabled: request.managedAccountEnabled ?? false,
        managedUsernameSecretRef: request.managedUsernameSecretRef,
        managedPasswordSecretRef: request.managedPasswordSecretRef,
        managedAccountRoleId: request.managedAccountRoleId,
        description: request.description,
        createdAt: new Date().toISOString(),
        updatedAt: new Date().toISOString(),
    };
}

function installMockWebSocket(page: Page): Promise<void> {
    return page.addInitScript(() => {
        const NativeWebSocket = window.WebSocket;

        class DashboardWebSocketMock {
            static CONNECTING = NativeWebSocket.CONNECTING;
            static OPEN = NativeWebSocket.OPEN;
            static CLOSING = NativeWebSocket.CLOSING;
            static CLOSED = NativeWebSocket.CLOSED;

            readyState = DashboardWebSocketMock.OPEN;
            onopen: ((event: Event) => void) | null = null;
            onclose: ((event: CloseEvent) => void) | null = null;
            onerror: ((event: Event) => void) | null = null;
            onmessage: ((event: MessageEvent) => void) | null = null;

            constructor() {
                setTimeout(() => {
                    this.onopen?.(new Event('open'));
                }, 0);
            }

            send(): void {}

            close(): void {
                this.readyState = DashboardWebSocketMock.CLOSED;
                this.onclose?.(new CloseEvent('close'));
            }
        }

        function PatchedWebSocket(url: string | URL, protocols?: string | string[]): WebSocket {
            const urlString = typeof url === 'string' ? url : url.toString();
            if (urlString.includes('/ws/dashboard')) {
                return new DashboardWebSocketMock() as unknown as WebSocket;
            }

            return protocols === undefined
                ? new NativeWebSocket(url)
                : new NativeWebSocket(url, protocols);
        }

        Object.assign(PatchedWebSocket, {
            CONNECTING: NativeWebSocket.CONNECTING,
            OPEN: NativeWebSocket.OPEN,
            CLOSING: NativeWebSocket.CLOSING,
            CLOSED: NativeWebSocket.CLOSED,
            prototype: NativeWebSocket.prototype,
        });

        Object.defineProperty(window, 'WebSocket', {
            configurable: true,
            writable: true,
            value: PatchedWebSocket,
        });
    });
}

export async function mockAllApiRoutes(page: Page, overrides: MockApiOverrides = {}): Promise<void> {
    const state = cloneState(overrides);
    const loginStatus = overrides.loginStatus ?? 200;
    const loginError = overrides.loginError ?? 'Invalid credentials';
    const loginToken = overrides.loginToken ?? MOCK_JWT;

    await installMockWebSocket(page);

    await page.route('**/*', async (route) => {
        const request = route.request();
        const url = new URL(request.url());
        const method = request.method();
        const pathname = url.pathname;

        if (!pathname.startsWith('/api/')) {
            return route.continue();
        }

        if (pathname === '/api/auth/login' && method === 'POST') {
            if (loginStatus >= 400) {
                return route.fulfill({
                    status: loginStatus,
                    contentType: 'text/plain',
                    body: loginError,
                });
            }

            return route.fulfill(json({ token: loginToken }));
        }

        if (pathname === '/api/nodes/stats' && method === 'GET') {
            return route.fulfill(json(state.clusterStats));
        }

        if (pathname === '/api/nodes/online/count' && method === 'GET') {
            const count = state.satellites.filter((satellite) => satellite.online).length;
            return route.fulfill(json({ count }));
        }

        if (pathname === '/api/nodes' && method === 'GET') {
            return route.fulfill(json(state.satellites));
        }

        if (pathname === '/api/jobs/stats' && method === 'GET') {
            return route.fulfill(json(state.jobStats));
        }

        if (pathname === '/api/jobs' && method === 'GET') {
            return route.fulfill(json(state.jobs));
        }

        if (pathname === '/api/jobs' && method === 'POST') {
            const payload = request.postDataJSON() as JobRequest;
            const newJob: Job = {
                id: `job-${String(state.jobs.length + 1).padStart(3, '0')}`,
                name: payload.name,
                description: payload.description,
                status: 'PENDING',
                requiredGpuCount: payload.gpuCount,
            };
            state.jobs = [newJob, ...state.jobs];
            return route.fulfill(json({}, 201));
        }

        if (pathname.startsWith('/api/jobs/') && method === 'GET') {
            const jobId = pathname.split('/').at(-1);
            const job = state.jobs.find((item) => item.id === jobId) ?? state.jobs[0];
            return route.fulfill(json(job));
        }

        if (pathname === '/api/discovery' && method === 'GET') {
            return route.fulfill(json(state.discoveredDevices));
        }

        if (pathname === '/api/discovery/pending/count' && method === 'GET') {
            const count = state.discoveredDevices.filter((device) => device.status === 'PENDING').length;
            return route.fulfill(json({ count }));
        }

        if (pathname.match(/^\/api\/discovery\/[^/]+\/approve$/) && method === 'POST') {
            const deviceId = pathname.split('/')[3];
            state.discoveredDevices = state.discoveredDevices.map((device) =>
                device.id === deviceId
                    ? {
                        ...device,
                        status: 'APPROVED',
                        authStatus: 'PROFILE_MATCHED',
                        claimStatus: 'READY_TO_CLAIM',
                        claimMessage: 'Device approved and claim planning is ready.',
                    }
                    : device,
            );
            return route.fulfill({ status: 200 });
        }

        if (pathname.match(/^\/api\/discovery\/[^/]+\/reject$/) && method === 'POST') {
            const deviceId = pathname.split('/')[3];
            state.discoveredDevices = state.discoveredDevices.map((device) =>
                device.id === deviceId ? { ...device, status: 'REJECTED' } : device,
            );
            return route.fulfill({ status: 200 });
        }

        if (pathname.match(/^\/api\/discovery\/[^/]+\/claim-plan$/) && method === 'POST') {
            const deviceId = pathname.split('/')[3];
            state.discoveredDevices = state.discoveredDevices.map((device) =>
                device.id === deviceId
                    ? {
                        ...device,
                        credentialProfileName: 'Dell iDRAC Default',
                        recommendedRedfishTemplate: 'dell-idrac',
                        authStatus: 'PROFILE_MATCHED',
                        claimStatus: 'READY_TO_CLAIM',
                        claimMessage: 'Claim plan refreshed using Dell iDRAC Default.',
                    }
                    : device,
            );
            return route.fulfill({ status: 200 });
        }

        if (pathname === '/api/scan/running' && method === 'GET') {
            return route.fulfill(json({ running: false }));
        }

        if (pathname === '/api/scan' && method === 'POST') {
            return route.fulfill(
                json({
                    id: 'scan-001',
                    target: '192.168.1.0/24',
                    status: 'RUNNING',
                    progressPercent: 10,
                    scannedCount: 10,
                    totalIps: 254,
                    discoveredCount: 2,
                }, 202),
            );
        }

        if (pathname.match(/^\/api\/scan\/[^/]+\/cancel$/) && method === 'POST') {
            return route.fulfill({ status: 202 });
        }

        if (pathname.match(/^\/api\/bmc\/devices\/[^/]+\/capabilities$/) && method === 'GET') {
            const deviceId = pathname.split('/')[4];
            return route.fulfill(json(DEFAULT_BMC_CAPABILITIES[deviceId] ?? DEFAULT_BMC_CAPABILITIES['dev-001']));
        }

        if (pathname.match(/^\/api\/bmc\/devices\/[^/]+\/claim$/) && method === 'POST') {
            const deviceId = pathname.split('/')[4];
            state.discoveredDevices = state.discoveredDevices.map((device) =>
                device.id === deviceId
                    ? {
                        ...device,
                        status: 'MANAGED',
                        claimStatus: 'CLAIMED',
                        authStatus: 'AUTHENTICATED',
                        claimMessage: 'Claim completed successfully.',
                    }
                    : device,
            );
            const claimedDevice = state.discoveredDevices.find((device) => device.id === deviceId) ?? state.discoveredDevices[0];
            return route.fulfill(json(claimedDevice));
        }

        if (pathname.match(/^\/api\/bmc\/devices\/[^/]+\/rotate-credentials$/) && method === 'POST') {
            const deviceId = pathname.split('/')[4];
            return route.fulfill(
                json({
                    deviceId,
                    status: 'SUCCESS',
                    message: 'Managed credentials rotated successfully.',
                }),
            );
        }

        if (pathname.match(/^\/api\/bmc\/devices\/[^/]+\/power-actions$/) && method === 'POST') {
            const payload = request.postDataJSON() as { action: string; systemId?: string };
            const dryRun = url.searchParams.get('dryRun') === 'true';
            return route.fulfill(
                json({
                    status: dryRun ? 'DRY_RUN' : 'COMPLETED',
                    action: payload.action,
                    systemId: payload.systemId,
                    authMode: 'SESSION',
                    targetUri: `/redfish/v1/Systems/${payload.systemId ?? 'System.Embedded.1'}/Actions/ComputerSystem.Reset`,
                    message: dryRun
                        ? `Dry run validated action ${payload.action}.`
                        : `Power action ${payload.action} completed.`,
                }),
            );
        }

        if (pathname === '/api/credential-profiles/templates' && method === 'GET') {
            return route.fulfill(json(state.redfishTemplates));
        }

        if (pathname === '/api/credential-profiles' && method === 'GET') {
            return route.fulfill(json(state.credentialProfiles));
        }

        if (pathname === '/api/credential-profiles' && method === 'POST') {
            const payload = request.postDataJSON() as CredentialProfileRequest;
            const id = `cp-${String(state.credentialProfiles.length + 1).padStart(3, '0')}`;
            const profile = toCredentialProfile(id, payload);
            state.credentialProfiles = [profile, ...state.credentialProfiles];
            return route.fulfill(json({}, 201));
        }

        if (pathname.match(/^\/api\/credential-profiles\/[^/]+$/) && method === 'PUT') {
            const profileId = pathname.split('/')[3];
            const payload = request.postDataJSON() as CredentialProfileRequest;
            state.credentialProfiles = state.credentialProfiles.map((profile) =>
                profile.id === profileId ? { ...toCredentialProfile(profileId, payload), createdAt: profile.createdAt } : profile,
            );
            return route.fulfill({ status: 204 });
        }

        if (pathname.match(/^\/api\/credential-profiles\/[^/]+$/) && method === 'DELETE') {
            const profileId = pathname.split('/')[3];
            state.credentialProfiles = state.credentialProfiles.filter((profile) => profile.id !== profileId);
            return route.fulfill({ status: 204 });
        }

        if (pathname.match(/^\/api\/credential-profiles\/[^/]+\/validate$/) && method === 'POST') {
            const profileId = pathname.split('/')[3];
            const profile = state.credentialProfiles.find((item) => item.id === profileId) ?? state.credentialProfiles[0];
            return route.fulfill(json(buildValidation(profile)));
        }

        if (pathname === '/api/tenants' && method === 'GET') {
            return route.fulfill(json(state.tenants));
        }

        return route.fulfill(
            json(
                {
                    message: `No Playwright mock is defined for ${method} ${pathname}`,
                },
                404,
            ),
        );
    });
}

export async function loginAsAdmin(page: Page): Promise<void> {
    await page.goto('/login');
    await page.evaluate(
        ({ token, user }) => {
            localStorage.setItem('lcm_auth_token', token);
            localStorage.setItem('lcm_auth_user', JSON.stringify(user));
        },
        { token: MOCK_JWT, user: MOCK_USER },
    );
}
