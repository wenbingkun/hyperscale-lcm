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
    lastHeartbeat: string;
    bmcIp?: string;
    systemSerial?: string;
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
}

const API_BASE = import.meta.env.VITE_API_BASE || 'http://localhost:8080';
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
async function apiFetch(url: string, options?: RequestInit): Promise<Response> {
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
