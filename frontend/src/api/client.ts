/**
 * API Client - 统一的后端请求封装
 * 自动附加 JWT Authorization 头部
 */

export interface Satellite {
    id: string;
    hostname: string;
    ipAddress: string;
    osVersion: string;
    agentVersion: string;
    status: string; // 'ONLINE' | 'OFFLINE'
    online: boolean;
    lastHeartbeat?: string;
    lastHeartbeatMs?: number;
    createdAt?: string;
    updatedAt?: string;
    // Hardware specs (from Node entity)
    cpuCores?: number;
    gpuCount?: number;
    gpuModel?: string;
    memoryGb?: number;
    rackId?: string;
    zoneId?: string;
    gpuTopology?: string;
    nvlinkBandwidthGbps?: number;
    ibFabricId?: string;
    // Redfish / BMC
    bmcIp?: string;
    bmcMac?: string;
    systemSerial?: string;
    systemModel?: string;
    model?: string;
    powerState?: string;
    systemTemperatureCelsius?: number;
}

export interface Job {
    id: string;
    name: string;
    description?: string;
    status: string; // 'PENDING' | 'SCHEDULED' | 'RUNNING' | 'COMPLETED' | 'FAILED' | 'CANCELLED'
    assignedNodeId?: string;
    clusterId?: string;
    requiredGpuCount?: number;
    scheduledAt?: string;
    completedAt?: string;
    exitCode?: number;
}

export interface JobStats {
    pending: number;
    scheduled: number;
    running: number;
    completed: number;
    failed: number;
}

export interface ClusterStats {
    onlineNodes: number;
    totalNodes: number;
    totalCpuCores: number;
    totalGpus: number;
    totalMemoryGb: number;
}

export interface OnlineCountResponse {
    count: number;
}

export interface DiscoveredDevice {
    id: string;
    ipAddress: string;
    macAddress?: string;
    hostname?: string;
    discoveryMethod: string;
    status: string;
    inferredType?: string;
    discoveredAt: string;
    lastProbedAt?: string;
    openPorts?: string;
    bmcAddress?: string;
    manufacturerHint?: string;
    modelHint?: string;
    recommendedRedfishTemplate?: string;
    authStatus?: string;
    claimStatus?: string;
    credentialProfileId?: string;
    credentialProfileName?: string;
    credentialSource?: string;
    claimMessage?: string;
    lastAuthAttemptAt?: string;
    notes?: string;
    tenantId?: string;
}

export interface DiscoveryCountResponse {
    count: number;
}

export interface CredentialProfile {
    id: string;
    name: string;
    protocol: string;
    enabled: boolean;
    autoClaim: boolean;
    priority: number;
    sourceType?: string;
    externalRef?: string;
    vendorPattern?: string;
    modelPattern?: string;
    subnetCidr?: string;
    deviceType?: string;
    hostnamePattern?: string;
    ipAddressPattern?: string;
    macAddressPattern?: string;
    redfishTemplate?: string;
    usernameSecretRef?: string;
    passwordSecretRef?: string;
    managedAccountEnabled: boolean;
    managedUsernameSecretRef?: string;
    managedPasswordSecretRef?: string;
    managedAccountRoleId?: string;
    description?: string;
    createdAt?: string;
    updatedAt?: string;
}

export interface CredentialProfileRequest {
    name: string;
    protocol?: string;
    enabled?: boolean;
    autoClaim?: boolean;
    priority?: number;
    sourceType?: string;
    externalRef?: string;
    vendorPattern?: string;
    modelPattern?: string;
    subnetCidr?: string;
    deviceType?: string;
    hostnamePattern?: string;
    ipAddressPattern?: string;
    macAddressPattern?: string;
    redfishTemplate?: string;
    usernameSecretRef?: string;
    passwordSecretRef?: string;
    managedAccountEnabled?: boolean;
    managedUsernameSecretRef?: string;
    managedPasswordSecretRef?: string;
    managedAccountRoleId?: string;
    description?: string;
}

export interface CredentialProfileValidation {
    id: string;
    name: string;
    ready: boolean;
    credentialSource?: string;
    message: string;
    usernameReady: boolean;
    usernameMessage: string;
    passwordReady: boolean;
    passwordMessage: string;
    managedAccountEnabled: boolean;
    managedAccountReady: boolean;
    managedAccountMessage: string;
    managedUsernameReady: boolean;
    managedUsernameMessage: string;
    managedPasswordReady: boolean;
    managedPasswordMessage: string;
}

export interface CredentialProfileImportResult {
    created: number;
    updated: number;
    skipped: number;
    results: Array<{
        name: string;
        status: 'CREATED' | 'UPDATED' | 'SKIPPED';
        message: string;
    }>;
}

export interface CmdbSyncResult {
    status: 'SUCCESS' | 'SKIPPED' | 'FAILURE';
    endpoint?: string;
    sourceType?: string;
    fetched: number;
    created: number;
    updated: number;
    skipped: number;
    message: string;
}

export interface RedfishTemplateSummary {
    name: string;
    description: string;
    priority: number;
    manufacturerPatterns: string[];
    modelPatterns: string[];
    source: string;
}

export interface JobRequest {
    name: string;
    description?: string;
    cpuCores: number;
    memoryGb: number;
    gpuCount: number;
    gpuModel?: string;
    requiresNvlink?: boolean;
    minNvlinkBandwidthGbps?: number;
    tenantId?: string;
    clusterId?: string;
    executionType?: string;
    executionPayload?: string;
}

export const API_BASE = import.meta.env.VITE_API_BASE || 'http://localhost:8080';
const TOKEN_KEY = 'lcm_auth_token';

/**
 * 获取带 Authorization 头的通用 headers
 */
function authHeaders(extra?: Record<string, string>): HeadersInit {
    const headers: Record<string, string> = { ...extra };
    const token = localStorage.getItem(TOKEN_KEY);
    if (token) {
        headers['Authorization'] = `Bearer ${token}`;
    }
    return headers;
}

/**
 * 统一的 fetch 封装 - 自动附加 Auth 头 + 401 处理
 */
export async function apiFetch(url: string, options?: RequestInit): Promise<Response> {
    const response = await fetch(url, {
        ...options,
        headers: authHeaders(options?.headers as Record<string, string>),
    });

    // 401 → Token 过期，清除并重定向到登录
    if (response.status === 401) {
        localStorage.removeItem(TOKEN_KEY);
        // 不在登录页时才跳转
        if (!window.location.pathname.includes('/login')) {
            window.location.href = '/login';
        }
    }

    return response;
}

export async function fetchSatellites(): Promise<Satellite[]> {
    try {
        const response = await apiFetch(`${API_BASE}/api/nodes`);
        if (!response.ok) throw new Error(`Failed: ${response.statusText}`);
        return await response.json();
    } catch (error) {
        console.error("API Error (Satellites):", error);
        return [];
    }
}

export async function fetchDiscoveredDevices(): Promise<DiscoveredDevice[]> {
    const response = await apiFetch(`${API_BASE}/api/discovery`);
    if (!response.ok) throw new Error(`Failed to load devices: ${response.statusText}`);
    return await response.json();
}

export async function fetchPendingDiscoveryCount(): Promise<DiscoveryCountResponse> {
    try {
        const response = await apiFetch(`${API_BASE}/api/discovery/pending/count`);
        if (!response.ok) throw new Error(`Failed: ${response.statusText}`);
        return await response.json();
    } catch (error) {
        console.error("API Error (DiscoveryCount):", error);
        return { count: 0 };
    }
}

export async function approveDiscoveredDevice(id: string): Promise<void> {
    const response = await apiFetch(`${API_BASE}/api/discovery/${id}/approve`, { method: 'POST' });
    if (!response.ok) {
        throw new Error(`Failed to approve device: ${response.statusText}`);
    }
}

export async function rejectDiscoveredDevice(id: string): Promise<void> {
    const response = await apiFetch(`${API_BASE}/api/discovery/${id}/reject`, { method: 'POST' });
    if (!response.ok) {
        throw new Error(`Failed to reject device: ${response.statusText}`);
    }
}

export async function refreshDiscoveryClaimPlan(id: string): Promise<void> {
    const response = await apiFetch(`${API_BASE}/api/discovery/${id}/claim-plan`, { method: 'POST' });
    if (!response.ok) {
        throw new Error(`Failed to refresh claim plan: ${response.statusText}`);
    }
}

export async function executeDiscoveryClaim(id: string): Promise<void> {
    const response = await apiFetch(`${API_BASE}/api/discovery/${id}/claim`, { method: 'POST' });
    if (!response.ok) {
        throw new Error(`Failed to execute claim: ${response.statusText}`);
    }
}

export async function fetchCredentialProfiles(): Promise<CredentialProfile[]> {
    try {
        const response = await apiFetch(`${API_BASE}/api/credential-profiles`);
        if (!response.ok) throw new Error(`Failed: ${response.statusText}`);
        return await response.json();
    } catch (error) {
        console.error("API Error (CredentialProfiles):", error);
        return [];
    }
}

export async function fetchRedfishTemplates(): Promise<RedfishTemplateSummary[]> {
    try {
        const response = await apiFetch(`${API_BASE}/api/credential-profiles/templates`);
        if (!response.ok) throw new Error(`Failed: ${response.statusText}`);
        return await response.json();
    } catch (error) {
        console.error("API Error (RedfishTemplates):", error);
        return [];
    }
}

export async function createCredentialProfile(profile: CredentialProfileRequest): Promise<void> {
    const response = await apiFetch(`${API_BASE}/api/credential-profiles`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(profile),
    });

    if (!response.ok) {
        throw new Error(`Failed to create credential profile: ${response.statusText}`);
    }
}

export async function updateCredentialProfile(id: string, profile: CredentialProfileRequest): Promise<void> {
    const response = await apiFetch(`${API_BASE}/api/credential-profiles/${id}`, {
        method: 'PUT',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(profile),
    });

    if (!response.ok) {
        throw new Error(`Failed to update credential profile: ${response.statusText}`);
    }
}

export async function deleteCredentialProfile(id: string): Promise<void> {
    const response = await apiFetch(`${API_BASE}/api/credential-profiles/${id}`, {
        method: 'DELETE',
    });

    if (!response.ok) {
        throw new Error(`Failed to delete credential profile: ${response.statusText}`);
    }
}

export async function validateCredentialProfile(id: string): Promise<CredentialProfileValidation> {
    const response = await apiFetch(`${API_BASE}/api/credential-profiles/${id}/validate`, {
        method: 'POST',
    });

    if (!response.ok) {
        throw new Error(`Failed to validate credential profile: ${response.statusText}`);
    }

    return await response.json();
}

export async function importBootstrapCredentialProfiles(
    entries: CredentialProfileRequest[],
): Promise<CredentialProfileImportResult> {
    const response = await apiFetch(`${API_BASE}/api/credential-profiles/import/bootstrap`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ entries }),
    });

    if (!response.ok) {
        throw new Error(`Failed to import bootstrap credential profiles: ${response.statusText}`);
    }

    return await response.json();
}

export async function syncCmdbCredentialProfiles(): Promise<CmdbSyncResult> {
    const response = await apiFetch(`${API_BASE}/api/credential-profiles/sync/cmdb`, {
        method: 'POST',
    });

    if (!response.ok) {
        throw new Error(`Failed to sync CMDB bootstrap credential profiles: ${response.statusText}`);
    }

    return await response.json();
}

export async function fetchJobs(): Promise<Job[]> {
    try {
        const response = await apiFetch(`${API_BASE}/api/jobs`);
        if (!response.ok) throw new Error(`Failed: ${response.statusText}`);
        return await response.json();
    } catch (error) {
        console.error("API Error (Jobs):", error);
        return [];
    }
}

export async function fetchJobStats(): Promise<JobStats> {
    try {
        const response = await apiFetch(`${API_BASE}/api/jobs/stats`);
        if (!response.ok) throw new Error(`Failed: ${response.statusText}`);
        return await response.json();
    } catch (error) {
        console.error("API Error (JobStats):", error);
        return { pending: 0, scheduled: 0, running: 0, completed: 0, failed: 0 };
    }
}

export async function fetchClusterStats(): Promise<ClusterStats> {
    try {
        const response = await apiFetch(`${API_BASE}/api/nodes/stats`);
        if (!response.ok) throw new Error(`Failed: ${response.statusText}`);
        return await response.json();
    } catch (error) {
        console.error("API Error (ClusterStats):", error);
        return { onlineNodes: 0, totalNodes: 0, totalCpuCores: 0, totalGpus: 0, totalMemoryGb: 0 };
    }
}

export async function submitJob(job: JobRequest): Promise<void> {
    const response = await apiFetch(`${API_BASE}/api/jobs`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(job),
    });

    if (!response.ok) {
        throw new Error(`Failed to submit job: ${response.statusText}`);
    }
}
