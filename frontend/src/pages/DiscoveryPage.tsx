import React, { useCallback, useEffect, useRef, useState } from 'react';
import { Play, RefreshCw, Search, Square } from 'lucide-react';
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
import { GlassCard } from '../components/GlassCard';
import { DiscoveryList } from '../components/discovery/DiscoveryList';

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

    const loadDevices = useCallback(async () => {
        try {
            const data = await fetchDiscoveredDevices();
            setDevices(data);
            setLoadError(null);
        } catch (error) {
            console.error('Failed to load devices:', error);
            setLoadError(error instanceof Error ? error.message : 'Failed to load devices');
        }
    }, []);

    const loadPendingCount = useCallback(async () => {
        const data = await fetchPendingDiscoveryCount();
        setPendingCount(data.count);
    }, []);

    const checkRunningScan = useCallback(async () => {
        try {
            const response = await apiFetch(`${API_BASE}/api/scan/running`);
            if (response.ok) {
                const data = await response.json();
                setRunningScan(data.running ? data.job : null);
            }
        } catch (error) {
            console.error('Failed to check running scan:', error);
        }
    }, []);

    const loadOverview = useCallback(async () => {
        setLoading(true);
        try {
            await Promise.all([loadDevices(), loadPendingCount(), checkRunningScan()]);
        } finally {
            setLoading(false);
        }
    }, [checkRunningScan, loadDevices, loadPendingCount]);

    const runningScanRef = useRef(runningScan);
    useEffect(() => {
        runningScanRef.current = runningScan;
    }, [runningScan]);

    useEffect(() => {
        void loadOverview();

        const interval = setInterval(() => {
            void checkRunningScan();
            if (runningScanRef.current) {
                void Promise.all([loadDevices(), loadPendingCount()]);
            }
        }, 2000);

        return () => clearInterval(interval);
    }, [checkRunningScan, loadDevices, loadOverview, loadPendingCount]);

    const startScan = async () => {
        if (!scanTarget) {
            return;
        }

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
        if (!runningScan) {
            return;
        }

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
        if (!value) {
            return '—';
        }
        const date = new Date(value);
        if (Number.isNaN(date.getTime())) {
            return value;
        }
        return date.toLocaleString();
    };

    return (
        <div className="space-y-6">
            <header className="flex flex-col gap-4 lg:flex-row lg:items-center lg:justify-between">
                <div>
                    <h2 className="text-2xl font-bold text-white">Device Discovery</h2>
                    <p className="mt-1 text-gray-400">Scan networks, classify BMCs, and plan first-claim onboarding</p>
                </div>
                <div className="flex flex-wrap gap-3">
                    <div className="rounded-lg border border-yellow-500/30 bg-yellow-500/20 px-4 py-2 text-yellow-400">
                        <span className="font-bold">{pendingCount}</span> pending
                    </div>
                    <div className="rounded-lg border border-blue-500/30 bg-blue-500/15 px-4 py-2 text-blue-300">
                        <span className="font-bold">{readyToClaimCount}</span> ready to claim
                    </div>
                    <div className="rounded-lg border border-amber-500/30 bg-amber-500/15 px-4 py-2 text-amber-300">
                        <span className="font-bold">{authPendingCount}</span> auth pending
                    </div>
                    <button
                        onClick={() => setShowScanModal(true)}
                        disabled={Boolean(runningScan)}
                        className="flex items-center gap-2 rounded-lg border border-purple-500/30 bg-purple-500/20 px-4 py-2 text-purple-400 transition-colors hover:bg-purple-500/30 disabled:opacity-50"
                    >
                        <Search size={18} />
                        Scan Network
                    </button>
                    <button
                        onClick={() => void loadOverview()}
                        className="rounded-lg border border-white/10 bg-white/5 p-2 text-white transition-colors hover:bg-white/10"
                        title="Refresh"
                    >
                        <RefreshCw size={18} className={loading ? 'animate-spin' : ''} />
                    </button>
                </div>
            </header>

            {loadError && (
                <div className="rounded-lg border border-red-500/30 bg-red-500/15 px-4 py-3 text-sm text-red-400">
                    {loadError}
                    <button onClick={() => void loadOverview()} className="ml-3 underline hover:no-underline">Retry</button>
                </div>
            )}

            {runningScan && (
                <GlassCard className="!p-4 border-l-4 border-l-purple-500">
                    <div className="flex items-center justify-between gap-4">
                        <div className="flex items-center gap-4">
                            <div className="rounded-lg bg-purple-500/20 p-2">
                                <Search size={20} className="animate-pulse text-purple-400" />
                            </div>
                            <div>
                                <p className="font-medium text-white">Scanning: {runningScan.target}</p>
                                <p className="text-sm text-gray-400">
                                    {runningScan.scannedCount} / {runningScan.totalIps} IPs • {runningScan.discoveredCount} discovered
                                </p>
                            </div>
                        </div>
                        <div className="flex items-center gap-4">
                            <div className="w-48">
                                <div className="h-2 overflow-hidden rounded-full bg-white/10">
                                    <div
                                        className="h-full bg-purple-500 transition-all"
                                        style={{ width: `${runningScan.progressPercent}%` }}
                                    />
                                </div>
                                <p className="mt-1 text-center text-xs text-gray-400">{runningScan.progressPercent}%</p>
                            </div>
                            <button
                                onClick={() => void cancelScan()}
                                className="rounded bg-red-500/20 p-2 text-red-400 hover:bg-red-500/30"
                                title="Cancel scan"
                            >
                                <Square size={16} />
                            </button>
                        </div>
                    </div>
                </GlassCard>
            )}

            {showScanModal && (
                <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/60">
                    <GlassCard className="w-full max-w-lg">
                        <h3 className="mb-4 text-lg font-semibold text-white">Start Network Scan</h3>

                        <div className="space-y-4">
                            <div>
                                <label className="mb-2 block text-sm text-gray-400">Target (CIDR or IP Range)</label>
                                <input
                                    type="text"
                                    value={scanTarget}
                                    onChange={(event) => setScanTarget(event.target.value)}
                                    placeholder="e.g., 192.168.1.0/24 or 10.0.0.1-10.0.0.100"
                                    className="w-full rounded-lg border border-white/10 bg-white/5 px-4 py-2 text-white placeholder-gray-500 focus:border-purple-500 focus:outline-none"
                                />
                            </div>

                            <div>
                                <label className="mb-2 block text-sm text-gray-400">Ports to Scan</label>
                                <input
                                    type="text"
                                    value={scanPorts}
                                    onChange={(event) => setScanPorts(event.target.value)}
                                    placeholder="22,443,8080,9000,623"
                                    className="w-full rounded-lg border border-white/10 bg-white/5 px-4 py-2 text-white placeholder-gray-500 focus:border-purple-500 focus:outline-none"
                                />
                                <p className="mt-1 text-xs text-gray-500">SSH (22), Redfish HTTPS (443), HTTP (8080), gRPC (9000), IPMI (623)</p>
                            </div>
                        </div>

                        <div className="mt-6 flex gap-3">
                            <button
                                onClick={() => setShowScanModal(false)}
                                className="flex-1 rounded-lg bg-white/5 px-4 py-2 text-gray-400 hover:bg-white/10"
                            >
                                Cancel
                            </button>
                            <button
                                onClick={() => void startScan()}
                                disabled={!scanTarget}
                                className="flex flex-1 items-center justify-center gap-2 rounded-lg bg-purple-500 px-4 py-2 text-white hover:bg-purple-600 disabled:opacity-50"
                            >
                                <Play size={18} />
                                Start Scan
                            </button>
                        </div>
                    </GlassCard>
                </div>
            )}

            <DiscoveryList
                devices={devices}
                filteredDevices={filteredDevices}
                searchQuery={searchQuery}
                statusFilter={statusFilter}
                authFilter={authFilter}
                claimFilter={claimFilter}
                busyAction={busyAction}
                formatDate={formatDate}
                getStatusColor={getStatusColor}
                getAuthColor={getAuthColor}
                getClaimColor={getClaimColor}
                onSearchQueryChange={setSearchQuery}
                onStatusFilterChange={setStatusFilter}
                onAuthFilterChange={setAuthFilter}
                onClaimFilterChange={setClaimFilter}
                onRefreshClaimPlan={(id) => void refreshClaimPlan(id)}
                onExecuteClaim={(id) => void executeClaim(id)}
                onApproveDevice={(id) => void approveDevice(id)}
                onRejectDevice={(id) => void rejectDevice(id)}
            />
        </div>
    );
};
