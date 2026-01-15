export interface Satellite {
    id: string;
    hostname: string;
    ipAddress: string;
    osVersion: string;
    agentVersion: string;
    status: string; // 'ONLINE' | 'OFFLINE'
    lastHeartbeat: string;
}

const API_BASE = 'http://localhost:8080';

export async function fetchSatellites(): Promise<Satellite[]> {
    try {
        const response = await fetch(`${API_BASE}/satellites`);
        if (!response.ok) {
            throw new Error(`Failed to fetch satellites: ${response.statusText}`);
        }
        return await response.json();
    } catch (error) {
        console.error("API Error:", error);
        // Return empty array or rethrow depending on needs. For now, empty to prevent UI crash.
        return [];
    }
}

export interface JobRequest {
    cpuCores: number;
    memoryGb: number;
    gpuCount: number;
}

export async function submitJob(job: JobRequest): Promise<void> {
    const response = await fetch(`${API_BASE}/jobs`, {
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
