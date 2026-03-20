import React, { useEffect, useRef, useState } from 'react';
import { GlassCard } from '../components/GlassCard';
import { Wifi, Check, X, RefreshCw, Monitor, Play, Square, Search, Shield, KeyRound } from 'lucide-react';
import {
    apiFetch,
    API_BASE,
    type DiscoveredDevice,
    approveDiscoveredDevice,
    executeDiscoveryClaim,
    fetchDiscoveredDevices,
    fetchPendingDiscoveryCount,
    refreshDiscoveryClaimPlan,
    rejectDiscoveredDevice,
} from '../api/client';

interface ScanJob {
    id: string;
    target: string;
    status: string;
    progressPercent: number;
    scannedCount: number;
    totalIps: number;
    discoveredCount: number;
}

export const DiscoveryPage: React.FC = () => {
    const [devices, setDevices] = useState<DiscoveredDevice[]>([]);
    const [loading, setLoading] = useState(true);
    const [pendingCount, setPendingCount] = useState(0);
    const [showScanModal, setShowScanModal] = useState(false);
    const [scanTarget, setScanTarget] = useState('');
    const [scanPorts, setScanPorts] = useState('22,443,8080,9000,623');
    const [runningScan, setRunningScan] = useState<ScanJob | null>(null);
    const [busyAction, setBusyAction] = useState<string | null>(null);
    const [loadError, setLoadError] = useState<string | null>(null);
    const [searchQuery, setSearchQuery] = useState('');
    const [statusFilter, setStatusFilter] = useState('ALL');
    const [authFilter, setAuthFilter] = useState('ALL');
    const [claimFilter, setClaimFilter] = useState('ALL');

    const readyToClaimCount = devices.filter((device) => device.claimStatus === 'READY_TO_CLAIM').length;
    const authPendingCount = devices.filter((device) => device.authStatus === 'AUTH_PENDING').length;
    const filteredDevices = devices.filter((device) => {
        const query = searchQuery.trim().toLowerCase();
        const matchesQuery = query === ''
            || [
                device.ipAddress,
                device.hostname,
                device.bmcAddress,
                device.macAddress,
                device.inferredType,
                device.manufacturerHint,
                device.modelHint,
                device.credentialProfileName,
                device.recommendedRedfishTemplate,
            ].some((value) => value?.toLowerCase().includes(query));
        const matchesStatus = statusFilter === 'ALL' || device.status === statusFilter;
        const matchesAuth = authFilter === 'ALL' || device.authStatus === authFilter;
        const matchesClaim = claimFilter === 'ALL' || device.claimStatus === claimFilter;
        return matchesQuery && matchesStatus && matchesAuth && matchesClaim;
    });

    const loadDevices = async () => {
        try {
            const data = await fetchDiscoveredDevices();
            setDevices(data);
            setLoadError(null);
        } catch (error) {
            console.error('Failed to load devices:', error);
            setLoadError(error instanceof Error ? error.message : 'Failed to load devices');
        }
    };

    const loadPendingCount = async () => {
        const data = await fetchPendingDiscoveryCount();
        setPendingCount(data.count);
    };

    const loadOverview = async () => {
        setLoading(true);
        try {
            await Promise.all([loadDevices(), loadPendingCount(), checkRunningScan()]);
        } finally {
            setLoading(false);
        }
    };

    const checkRunningScan = async () => {
        try {
            const response = await apiFetch(`${API_BASE}/api/scan/running`);
            if (response.ok) {
                const data = await response.json();
                setRunningScan(data.running ? data.job : null);
            }
        } catch (error) {
            console.error('Failed to check running scan:', error);
        }
    };

    const runningScanRef = useRef(runningScan);
    useEffect(() => {
        runningScanRef.current = runningScan;
    }, [runningScan]);

    useEffect(() => {
        loadOverview();

        const interval = setInterval(() => {
            checkRunningScan();
            if (runningScanRef.current) {
                void Promise.all([loadDevices(), loadPendingCount()]);
            }
        }, 2000);

        return () => clearInterval(interval);
    }, []);

    const startScan = async () => {
        if (!scanTarget) return;

        try {
            const response = await apiFetch(`${API_BASE}/api/scan`, {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({
                    target: scanTarget,
                    ports: scanPorts,
                }),
            });
            if (response.ok) {
                setShowScanModal(false);
                setScanTarget('');
                await checkRunningScan();
            }
        } catch (error) {
            console.error('Failed to start scan:', error);
        }
    };

    const cancelScan = async () => {
        if (!runningScan) return;

        try {
            await apiFetch(`${API_BASE}/api/scan/${runningScan.id}/cancel`, {
                method: 'POST',
            });
            setRunningScan(null);
        } catch (error) {
            console.error('Failed to cancel scan:', error);
        }
    };

    const runDeviceAction = async (actionKey: string, operation: () => Promise<void>) => {
        setBusyAction(actionKey);
        try {
            await operation();
            await Promise.all([loadDevices(), loadPendingCount()]);
        } catch (error) {
            console.error(`Failed to run device action ${actionKey}:`, error);
        } finally {
            setBusyAction(null);
        }
    };

    const approveDevice = async (id: string) => {
        await runDeviceAction(`approve:${id}`, () => approveDiscoveredDevice(id));
    };

    const rejectDevice = async (id: string) => {
        await runDeviceAction(`reject:${id}`, () => rejectDiscoveredDevice(id));
    };

    const refreshClaimPlan = async (id: string) => {
        await runDeviceAction(`replan:${id}`, () => refreshDiscoveryClaimPlan(id));
    };

    const executeClaim = async (id: string) => {
        await runDeviceAction(`claim:${id}`, () => executeDiscoveryClaim(id));
    };

    const getStatusColor = (status?: string) => {
        switch (status) {
            case 'PENDING': return 'text-yellow-400 bg-yellow-400/10';
            case 'APPROVED': return 'text-green-400 bg-green-400/10';
            case 'REJECTED': return 'text-red-400 bg-red-400/10';
            case 'MANAGED': return 'text-cyan-400 bg-cyan-400/10';
            default: return 'text-gray-400 bg-gray-400/10';
        }
    };

    const getAuthColor = (status?: string) => {
        switch (status) {
            case 'PROFILE_MATCHED': return 'text-emerald-400 bg-emerald-400/10';
            case 'AUTH_PENDING': return 'text-amber-400 bg-amber-400/10';
            case 'AUTH_FAILED': return 'text-red-400 bg-red-400/10';
            case 'AUTHENTICATED': return 'text-cyan-400 bg-cyan-400/10';
            default: return 'text-gray-400 bg-gray-400/10';
        }
    };

    const getClaimColor = (status?: string) => {
        switch (status) {
            case 'READY_TO_CLAIM': return 'text-blue-400 bg-blue-400/10';
            case 'CLAIMING': return 'text-violet-400 bg-violet-400/10';
            case 'CLAIMED': return 'text-emerald-400 bg-emerald-400/10';
            case 'MANAGED': return 'text-cyan-400 bg-cyan-400/10';
            default: return 'text-gray-400 bg-gray-400/10';
        }
    };

    const formatDate = (value?: string) => {
        if (!value) return '—';
        const date = new Date(value);
        if (Number.isNaN(date.getTime())) return value;
        return date.toLocaleString();
    };

    return (
        <div className="space-y-6">
            <header className="flex flex-col gap-4 lg:flex-row lg:items-center lg:justify-between">
                <div>
                    <h2 className="text-2xl font-bold text-white">Device Discovery</h2>
                    <p className="text-gray-400 mt-1">Scan networks, classify BMCs, and plan first-claim onboarding</p>
                </div>
                <div className="flex flex-wrap gap-3">
                    <div className="px-4 py-2 rounded-lg bg-yellow-500/20 border border-yellow-500/30 text-yellow-400">
                        <span className="font-bold">{pendingCount}</span> pending
                    </div>
                    <div className="px-4 py-2 rounded-lg bg-blue-500/15 border border-blue-500/30 text-blue-300">
                        <span className="font-bold">{readyToClaimCount}</span> ready to claim
                    </div>
                    <div className="px-4 py-2 rounded-lg bg-amber-500/15 border border-amber-500/30 text-amber-300">
                        <span className="font-bold">{authPendingCount}</span> auth pending
                    </div>
                    <button
                        onClick={() => setShowScanModal(true)}
                        disabled={!!runningScan}
                        className="flex items-center gap-2 px-4 py-2 rounded-lg bg-purple-500/20 border border-purple-500/30 text-purple-400 hover:bg-purple-500/30 transition-colors disabled:opacity-50"
                    >
                        <Search size={18} />
                        Scan Network
                    </button>
                    <button
                        onClick={loadOverview}
                        className="p-2 rounded-lg bg-white/5 border border-white/10 hover:bg-white/10 text-white transition-colors"
                        title="Refresh"
                    >
                        <RefreshCw size={18} className={loading ? 'animate-spin' : ''} />
                    </button>
                </div>
            </header>

            {loadError && (
                <div className="px-4 py-3 rounded-lg bg-red-500/15 border border-red-500/30 text-red-400 text-sm">
                    {loadError}
                    <button onClick={loadOverview} className="ml-3 underline hover:no-underline">Retry</button>
                </div>
            )}

            {runningScan && (
                <GlassCard className="!p-4 border-l-4 border-l-purple-500">
                    <div className="flex items-center justify-between gap-4">
                        <div className="flex items-center gap-4">
                            <div className="p-2 rounded-lg bg-purple-500/20">
                                <Search size={20} className="text-purple-400 animate-pulse" />
                            </div>
                            <div>
                                <p className="text-white font-medium">Scanning: {runningScan.target}</p>
                                <p className="text-sm text-gray-400">
                                    {runningScan.scannedCount} / {runningScan.totalIps} IPs • {runningScan.discoveredCount} discovered
                                </p>
                            </div>
                        </div>
                        <div className="flex items-center gap-4">
                            <div className="w-48">
                                <div className="h-2 bg-white/10 rounded-full overflow-hidden">
                                    <div
                                        className="h-full bg-purple-500 transition-all"
                                        style={{ width: `${runningScan.progressPercent}%` }}
                                    />
                                </div>
                                <p className="text-xs text-center text-gray-400 mt-1">{runningScan.progressPercent}%</p>
                            </div>
                            <button
                                onClick={cancelScan}
                                className="p-2 rounded bg-red-500/20 text-red-400 hover:bg-red-500/30"
                                title="Cancel scan"
                            >
                                <Square size={16} />
                            </button>
                        </div>
                    </div>
                </GlassCard>
            )}

            {showScanModal && (
                <div className="fixed inset-0 bg-black/60 flex items-center justify-center z-50">
                    <GlassCard className="w-full max-w-lg">
                        <h3 className="text-lg font-semibold text-white mb-4">Start Network Scan</h3>

                        <div className="space-y-4">
                            <div>
                                <label className="block text-sm text-gray-400 mb-2">Target (CIDR or IP Range)</label>
                                <input
                                    type="text"
                                    value={scanTarget}
                                    onChange={(e) => setScanTarget(e.target.value)}
                                    placeholder="e.g., 192.168.1.0/24 or 10.0.0.1-10.0.0.100"
                                    className="w-full px-4 py-2 rounded-lg bg-white/5 border border-white/10 text-white placeholder-gray-500 focus:outline-none focus:border-purple-500"
                                />
                            </div>

                            <div>
                                <label className="block text-sm text-gray-400 mb-2">Ports to Scan</label>
                                <input
                                    type="text"
                                    value={scanPorts}
                                    onChange={(e) => setScanPorts(e.target.value)}
                                    placeholder="22,443,8080,9000,623"
                                    className="w-full px-4 py-2 rounded-lg bg-white/5 border border-white/10 text-white placeholder-gray-500 focus:outline-none focus:border-purple-500"
                                />
                                <p className="text-xs text-gray-500 mt-1">SSH (22), Redfish HTTPS (443), HTTP (8080), gRPC (9000), IPMI (623)</p>
                            </div>
                        </div>

                        <div className="flex gap-3 mt-6">
                            <button
                                onClick={() => setShowScanModal(false)}
                                className="flex-1 px-4 py-2 rounded-lg bg-white/5 text-gray-400 hover:bg-white/10"
                            >
                                Cancel
                            </button>
                            <button
                                onClick={startScan}
                                disabled={!scanTarget}
                                className="flex-1 flex items-center justify-center gap-2 px-4 py-2 rounded-lg bg-purple-500 text-white hover:bg-purple-600 disabled:opacity-50"
                            >
                                <Play size={18} />
                                Start Scan
                            </button>
                        </div>
                    </GlassCard>
                </div>
            )}

            <GlassCard className="min-h-[400px]">
                <div className="flex flex-col gap-3 border-b border-white/5 pb-4 mb-4 lg:flex-row lg:items-center">
                    <div className="relative flex-1">
                        <Search size={16} className="absolute left-3 top-1/2 -translate-y-1/2 text-gray-500" />
                        <input
                            type="text"
                            value={searchQuery}
                            onChange={(e) => setSearchQuery(e.target.value)}
                            placeholder="Search by IP, host, vendor, model, profile, template"
                            className="w-full rounded-lg border border-white/10 bg-white/5 py-2 pl-10 pr-4 text-sm text-white placeholder-gray-500 focus:outline-none focus:border-cyan-500"
                        />
                    </div>
                    <select
                        value={statusFilter}
                        onChange={(e) => setStatusFilter(e.target.value)}
                        className="rounded-lg border border-white/10 bg-slate-950 px-3 py-2 text-sm text-white focus:outline-none focus:border-cyan-500"
                    >
                        <option value="ALL">All status</option>
                        <option value="PENDING">Pending</option>
                        <option value="APPROVED">Approved</option>
                        <option value="REJECTED">Rejected</option>
                        <option value="MANAGED">Managed</option>
                    </select>
                    <select
                        value={authFilter}
                        onChange={(e) => setAuthFilter(e.target.value)}
                        className="rounded-lg border border-white/10 bg-slate-950 px-3 py-2 text-sm text-white focus:outline-none focus:border-cyan-500"
                    >
                        <option value="ALL">All auth</option>
                        <option value="PENDING">Pending</option>
                        <option value="PROFILE_MATCHED">Profile matched</option>
                        <option value="AUTH_PENDING">Auth pending</option>
                        <option value="AUTH_FAILED">Auth failed</option>
                        <option value="AUTHENTICATED">Authenticated</option>
                    </select>
                    <select
                        value={claimFilter}
                        onChange={(e) => setClaimFilter(e.target.value)}
                        className="rounded-lg border border-white/10 bg-slate-950 px-3 py-2 text-sm text-white focus:outline-none focus:border-cyan-500"
                    >
                        <option value="ALL">All claim</option>
                        <option value="DISCOVERED">Discovered</option>
                        <option value="READY_TO_CLAIM">Ready to claim</option>
                        <option value="CLAIMING">Claiming</option>
                        <option value="CLAIMED">Claimed</option>
                        <option value="MANAGED">Managed</option>
                    </select>
                </div>
                <div className="overflow-x-auto">
                    <table className="w-full text-left">
                        <thead>
                            <tr className="text-sm text-gray-500 border-b border-white/5">
                                <th className="py-4 px-4 font-medium">Discovery</th>
                                <th className="py-4 px-4 font-medium">Endpoint</th>
                                <th className="py-4 px-4 font-medium">Classification</th>
                                <th className="py-4 px-4 font-medium">Claim Plan</th>
                                <th className="py-4 px-4 font-medium">Actions</th>
                            </tr>
                        </thead>
                        <tbody>
                            {filteredDevices.length === 0 ? (
                                <tr>
                                    <td colSpan={5} className="py-12 text-center text-gray-500">
                                        <Wifi size={48} className="mx-auto mb-4 opacity-30" />
                                        {devices.length === 0 ? 'No devices discovered yet' : 'No devices match the current filters'}
                                        <p className="text-sm mt-2">
                                            {devices.length === 0 ? 'Start a network scan to discover devices' : 'Adjust the filters or search query'}
                                        </p>
                                    </td>
                                </tr>
                            ) : (
                                filteredDevices.map((device) => (
                                    <tr key={device.id} className="border-b border-white/5 hover:bg-white/5 transition-colors align-top">
                                        <td className="py-4 px-4 min-w-[220px]">
                                            <div className="space-y-2">
                                                <div className="flex flex-wrap gap-2">
                                                    <span className={`px-2.5 py-0.5 rounded-full text-xs font-medium ${getStatusColor(device.status)}`}>
                                                        {device.status}
                                                    </span>
                                                    <span className={`px-2.5 py-0.5 rounded-full text-xs font-medium ${getAuthColor(device.authStatus)}`}>
                                                        {device.authStatus || 'PENDING'}
                                                    </span>
                                                    <span className={`px-2.5 py-0.5 rounded-full text-xs font-medium ${getClaimColor(device.claimStatus)}`}>
                                                        {device.claimStatus || 'DISCOVERED'}
                                                    </span>
                                                </div>
                                                <div className="text-xs text-gray-500">
                                                    <div>{device.discoveryMethod}</div>
                                                    <div>{formatDate(device.discoveredAt)}</div>
                                                    {device.lastProbedAt && <div>Last probe: {formatDate(device.lastProbedAt)}</div>}
                                                </div>
                                            </div>
                                        </td>
                                        <td className="py-4 px-4 min-w-[240px]">
                                            <div className="space-y-1">
                                                <div className="font-mono text-cyan-300">{device.ipAddress}</div>
                                                <div className="text-white">{device.hostname || '—'}</div>
                                                {device.bmcAddress && (
                                                    <div className="text-xs text-cyan-500 font-mono">BMC: {device.bmcAddress}</div>
                                                )}
                                                {device.macAddress && (
                                                    <div className="text-xs text-gray-500 font-mono">{device.macAddress}</div>
                                                )}
                                            </div>
                                        </td>
                                        <td className="py-4 px-4 min-w-[260px]">
                                            <div className="space-y-2">
                                                <div className="flex items-center gap-2 text-gray-300">
                                                    <Monitor size={14} />
                                                    <span>{device.inferredType || 'UNKNOWN'}</span>
                                                </div>
                                                <div className="text-sm text-gray-400">
                                                    {device.manufacturerHint && <div>Vendor: {device.manufacturerHint}</div>}
                                                    {device.modelHint && <div>Model: {device.modelHint}</div>}
                                                    {device.openPorts && <div className="text-xs text-gray-500">Ports: {device.openPorts}</div>}
                                                </div>
                                            </div>
                                        </td>
                                        <td className="py-4 px-4 min-w-[320px]">
                                            <div className="space-y-2">
                                                <div className="flex flex-wrap items-center gap-2">
                                                    {device.credentialProfileName ? (
                                                        <span className="inline-flex items-center gap-1 px-2.5 py-0.5 rounded-full text-xs bg-emerald-500/10 text-emerald-300">
                                                            <KeyRound size={12} />
                                                            {device.credentialProfileName}
                                                        </span>
                                                    ) : (
                                                        <span className="inline-flex items-center gap-1 px-2.5 py-0.5 rounded-full text-xs bg-white/5 text-gray-400">
                                                            <KeyRound size={12} />
                                                            No profile matched
                                                        </span>
                                                    )}
                                                    {device.recommendedRedfishTemplate && (
                                                        <span className="inline-flex items-center gap-1 px-2.5 py-0.5 rounded-full text-xs bg-blue-500/10 text-blue-300">
                                                            <Shield size={12} />
                                                            {device.recommendedRedfishTemplate}
                                                        </span>
                                                    )}
                                                </div>
                                                <p className="text-sm text-gray-300">{device.claimMessage || 'No claim plan generated yet.'}</p>
                                                <div className="text-xs text-gray-500">
                                                    {device.credentialSource && <div>Source: {device.credentialSource}</div>}
                                                    {device.lastAuthAttemptAt && <div>Last auth attempt: {formatDate(device.lastAuthAttemptAt)}</div>}
                                                </div>
                                            </div>
                                        </td>
                                        <td className="py-4 px-4 min-w-[180px]">
                                            <div className="flex flex-wrap gap-2">
                                                <button
                                                    onClick={() => void refreshClaimPlan(device.id)}
                                                    disabled={busyAction === `replan:${device.id}`}
                                                    className="inline-flex items-center gap-2 px-3 py-2 rounded-lg bg-white/5 text-gray-300 hover:bg-white/10 disabled:opacity-50"
                                                    title="Recalculate claim plan"
                                                >
                                                    <RefreshCw size={14} className={busyAction === `replan:${device.id}` ? 'animate-spin' : ''} />
                                                    Replan
                                                </button>
                                                {device.claimStatus === 'READY_TO_CLAIM' && (
                                                    <button
                                                        onClick={() => void executeClaim(device.id)}
                                                        disabled={busyAction === `claim:${device.id}`}
                                                        className="inline-flex items-center gap-2 px-3 py-2 rounded-lg bg-cyan-500/15 text-cyan-200 hover:bg-cyan-500/25 disabled:opacity-50"
                                                        title="Execute first Redfish claim"
                                                    >
                                                        <Shield size={14} />
                                                        {busyAction === `claim:${device.id}` ? 'Claiming...' : 'Claim'}
                                                    </button>
                                                )}
                                                {device.status === 'PENDING' && (
                                                    <>
                                                        <button
                                                            onClick={() => void approveDevice(device.id)}
                                                            disabled={busyAction === `approve:${device.id}`}
                                                            className="p-2 rounded bg-green-500/20 text-green-400 hover:bg-green-500/30 disabled:opacity-50"
                                                            title="Approve"
                                                        >
                                                            <Check size={16} />
                                                        </button>
                                                        <button
                                                            onClick={() => void rejectDevice(device.id)}
                                                            disabled={busyAction === `reject:${device.id}`}
                                                            className="p-2 rounded bg-red-500/20 text-red-400 hover:bg-red-500/30 disabled:opacity-50"
                                                            title="Reject"
                                                        >
                                                            <X size={16} />
                                                        </button>
                                                    </>
                                                )}
                                            </div>
                                        </td>
                                    </tr>
                                ))
                            )}
                        </tbody>
                    </table>
                </div>
            </GlassCard>
        </div>
    );
};
