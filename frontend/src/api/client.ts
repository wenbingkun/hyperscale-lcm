export interface Satellite {
    id: string;
    hostname: string;
    ipAddress: string;
    osVersion: string;
    agentVersion: string;
    status: string; // 'ONLINE' | 'OFFLINE'
    lastHeartbeat: string;
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

const API_BASE = 'http://localhost:8080';

export async function fetchSatellites(): Promise<Satellite[]> {
    try {
        const response = await fetch(`${API_BASE}/api/nodes`); // Updated endpoint to match NodeResource
        if (!response.ok) throw new Error(`Failed: ${response.statusText}`);
        return await response.json();
    } catch (error) {
        console.error("API Error (Satellites):", error);
        return [];
    }
}

export async function fetchJobs(): Promise<Job[]> {
    try {
        const response = await fetch(`${API_BASE}/api/jobs`);
        if (!response.ok) throw new Error(`Failed: ${response.statusText}`);
        return await response.json();
    } catch (error) {
        console.error("API Error (Jobs):", error);
        return [];
    }
}

export async function fetchJobStats(): Promise<JobStats> {
    try {
        const response = await fetch(`${API_BASE}/api/jobs/stats`);
        if (!response.ok) throw new Error(`Failed: ${response.statusText}`);
        return await response.json();
    } catch (error) {
        console.error("API Error (JobStats):", error);
        return { pending: 0, scheduled: 0, running: 0, completed: 0, failed: 0 };
    }
}

export async function fetchClusterStats(): Promise<ClusterStats> {
    try {
        const response = await fetch(`${API_BASE}/api/nodes/stats`);
        if (!response.ok) throw new Error(`Failed: ${response.statusText}`);
        return await response.json();
    } catch (error) {
        console.error("API Error (ClusterStats):", error);
        return { onlineNodes: 0, totalNodes: 0, totalCpuCores: 0, totalGpus: 0, totalMemoryGb: 0 };
    }
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

export async function submitJob(job: JobRequest): Promise<void> {
    const response = await fetch(`${API_BASE}/api/jobs`, {
        method: 'POST',
        headers: {
            'Content-Type': 'application/json',
        },
        body: JSON.stringify(job),
    });

    if (!response.ok) {
        throw new Error(`Failed to submit job: ${response.statusText}`);
    }
}
