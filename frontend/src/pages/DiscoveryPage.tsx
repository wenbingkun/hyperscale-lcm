import React, { useEffect, useState } from 'react';
import { GlassCard } from '../components/GlassCard';
import { Wifi, Check, X, RefreshCw, Monitor, Play, Square, Search } from 'lucide-react';

interface DiscoveredDevice {
    id: string;
    ipAddress: string;
    hostname: string;
    macAddress: string;
    discoveryMethod: string;
    status: string;
    inferredType: string;
    discoveredAt: string;
}

interface ScanJob {
    id: string;
    target: string;
    status: string;
    progressPercent: number;
    scannedCount: number;
    totalIps: number;
    discoveredCount: number;
}

const API_BASE = 'http://localhost:8080';

export const DiscoveryPage: React.FC = () => {
    const [devices, setDevices] = useState<DiscoveredDevice[]>([]);
    const [loading, setLoading] = useState(true);
    const [pendingCount, setPendingCount] = useState(0);
    const [showScanModal, setShowScanModal] = useState(false);
    const [scanTarget, setScanTarget] = useState('');
    const [scanPorts, setScanPorts] = useState('22,8080,9000,623');
    const [runningScan, setRunningScan] = useState<ScanJob | null>(null);

    const loadDevices = async () => {
        try {
            const token = localStorage.getItem('token');
            const response = await fetch(`${API_BASE}/api/discovery`, {
                headers: { 'Authorization': `Bearer ${token}` }
            });
            if (response.ok) {
                const data = await response.json();
                setDevices(data);
            }
        } catch (error) {
            console.error('Failed to load devices:', error);
        }
        setLoading(false);
    };

    const loadPendingCount = async () => {
        try {
            const token = localStorage.getItem('token');
            const response = await fetch(`${API_BASE}/api/discovery/pending/count`, {
                headers: { 'Authorization': `Bearer ${token}` }
            });
            if (response.ok) {
                const data = await response.json();
                setPendingCount(data.count);
            }
        } catch (error) {
            console.error('Failed to load pending count:', error);
        }
    };

    const checkRunningScan = async () => {
        try {
            const token = localStorage.getItem('token');
            const response = await fetch(`${API_BASE}/api/scan/running`, {
                headers: { 'Authorization': `Bearer ${token}` }
            });
            if (response.ok) {
                const data = await response.json();
                setRunningScan(data.running ? data.job : null);
            }
        } catch (error) {
            console.error('Failed to check running scan:', error);
        }
    };

    useEffect(() => {
        loadDevices();
        loadPendingCount();
        checkRunningScan();

        // Poll for running scan status
        const interval = setInterval(() => {
            checkRunningScan();
            if (runningScan) {
                loadDevices();
                loadPendingCount();
            }
        }, 2000);

        return () => clearInterval(interval);
    }, []);

    const startScan = async () => {
        if (!scanTarget) return;

        try {
            const token = localStorage.getItem('token');
            const response = await fetch(`${API_BASE}/api/scan`, {
                method: 'POST',
                headers: {
                    'Authorization': `Bearer ${token}`,
                    'Content-Type': 'application/json'
                },
                body: JSON.stringify({
                    target: scanTarget,
                    ports: scanPorts
                })
            });
            if (response.ok) {
                setShowScanModal(false);
                setScanTarget('');
                checkRunningScan();
            }
        } catch (error) {
            console.error('Failed to start scan:', error);
        }
    };

    const cancelScan = async () => {
        if (!runningScan) return;

        try {
            const token = localStorage.getItem('token');
            await fetch(`${API_BASE}/api/scan/${runningScan.id}/cancel`, {
                method: 'POST',
                headers: { 'Authorization': `Bearer ${token}` }
            });
            setRunningScan(null);
        } catch (error) {
            console.error('Failed to cancel scan:', error);
        }
    };

    const approveDevice = async (id: string) => {
        const token = localStorage.getItem('token');
        await fetch(`${API_BASE}/api/discovery/${id}/approve`, {
            method: 'POST',
            headers: { 'Authorization': `Bearer ${token}` }
        });
        loadDevices();
        loadPendingCount();
    };

    const rejectDevice = async (id: string) => {
        const token = localStorage.getItem('token');
        await fetch(`${API_BASE}/api/discovery/${id}/reject`, {
            method: 'POST',
            headers: { 'Authorization': `Bearer ${token}` }
        });
        loadDevices();
        loadPendingCount();
    };

    const getStatusColor = (status: string) => {
        switch (status) {
            case 'PENDING': return 'text-yellow-400 bg-yellow-400/10';
            case 'APPROVED': return 'text-green-400 bg-green-400/10';
            case 'REJECTED': return 'text-red-400 bg-red-400/10';
            case 'MANAGED': return 'text-cyan-400 bg-cyan-400/10';
            default: return 'text-gray-400 bg-gray-400/10';
        }
    };

    return (
        <div className="space-y-6">
            <header className="flex items-center justify-between">
                <div>
                    <h2 className="text-2xl font-bold text-white">Device Discovery</h2>
                    <p className="text-gray-400 mt-1">Scan networks and manage discovered devices</p>
                </div>
                <div className="flex gap-3">
                    {pendingCount > 0 && (
                        <div className="px-4 py-2 rounded-lg bg-yellow-500/20 border border-yellow-500/30 text-yellow-400">
                            <span className="font-bold">{pendingCount}</span> pending
                        </div>
                    )}
                    <button
                        onClick={() => setShowScanModal(true)}
                        disabled={!!runningScan}
                        className="flex items-center gap-2 px-4 py-2 rounded-lg bg-purple-500/20 border border-purple-500/30 text-purple-400 hover:bg-purple-500/30 transition-colors disabled:opacity-50"
                    >
                        <Search size={18} />
                        Scan Network
                    </button>
                    <button
                        onClick={loadDevices}
                        className="p-2 rounded-lg bg-white/5 border border-white/10 hover:bg-white/10 text-white transition-colors"
                    >
                        <RefreshCw size={18} className={loading ? 'animate-spin' : ''} />
                    </button>
                </div>
            </header>

            {/* Running Scan Progress */}
            {runningScan && (
                <GlassCard className="!p-4 border-l-4 border-l-purple-500">
                    <div className="flex items-center justify-between">
                        <div className="flex items-center gap-4">
                            <div className="p-2 rounded-lg bg-purple-500/20">
                                <Search size={20} className="text-purple-400 animate-pulse" />
                            </div>
                            <div>
                                <p className="text-white font-medium">Scanning: {runningScan.target}</p>
                                <p className="text-sm text-gray-400">
                                    {runningScan.scannedCount} / {runningScan.totalIps} IPs •
                                    {runningScan.discoveredCount} discovered
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
                            >
                                <Square size={16} />
                            </button>
                        </div>
                    </div>
                </GlassCard>
            )}

            {/* Scan Modal */}
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
                                    placeholder="22,8080,9000,623"
                                    className="w-full px-4 py-2 rounded-lg bg-white/5 border border-white/10 text-white placeholder-gray-500 focus:outline-none focus:border-purple-500"
                                />
                                <p className="text-xs text-gray-500 mt-1">SSH (22), HTTP (8080), gRPC (9000), IPMI (623)</p>
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

            {/* Devices Table */}
            <GlassCard className="min-h-[400px]">
                <div className="overflow-x-auto">
                    <table className="w-full text-left">
                        <thead>
                            <tr className="text-sm text-gray-500 border-b border-white/5">
                                <th className="py-4 px-4 font-medium">Status</th>
                                <th className="py-4 px-4 font-medium">IP Address</th>
                                <th className="py-4 px-4 font-medium">Hostname</th>
                                <th className="py-4 px-4 font-medium">Type</th>
                                <th className="py-4 px-4 font-medium">Method</th>
                                <th className="py-4 px-4 font-medium">Discovered</th>
                                <th className="py-4 px-4 font-medium">Actions</th>
                            </tr>
                        </thead>
                        <tbody>
                            {devices.length === 0 ? (
                                <tr>
                                    <td colSpan={7} className="py-12 text-center text-gray-500">
                                        <Wifi size={48} className="mx-auto mb-4 opacity-30" />
                                        No devices discovered yet
                                        <p className="text-sm mt-2">Start a network scan to discover devices</p>
                                    </td>
                                </tr>
                            ) : (
                                devices.map((device) => (
                                    <tr key={device.id} className="border-b border-white/5 hover:bg-white/5 transition-colors">
                                        <td className="py-4 px-4">
                                            <span className={`px-2.5 py-0.5 rounded-full text-xs font-medium ${getStatusColor(device.status)}`}>
                                                {device.status}
                                            </span>
                                        </td>
                                        <td className="py-4 px-4 font-mono text-cyan-300">{device.ipAddress}</td>
                                        <td className="py-4 px-4 text-white">{device.hostname || '-'}</td>
                                        <td className="py-4 px-4 text-gray-400">
                                            <div className="flex items-center gap-2">
                                                <Monitor size={14} />
                                                {device.inferredType || 'UNKNOWN'}
                                            </div>
                                        </td>
                                        <td className="py-4 px-4 text-gray-500 text-sm">{device.discoveryMethod}</td>
                                        <td className="py-4 px-4 text-gray-500 text-sm">
                                            {new Date(device.discoveredAt).toLocaleString()}
                                        </td>
                                        <td className="py-4 px-4">
                                            {device.status === 'PENDING' && (
                                                <div className="flex gap-2">
                                                    <button
                                                        onClick={() => approveDevice(device.id)}
                                                        className="p-1.5 rounded bg-green-500/20 text-green-400 hover:bg-green-500/30"
                                                        title="Approve"
                                                    >
                                                        <Check size={16} />
                                                    </button>
                                                    <button
                                                        onClick={() => rejectDevice(device.id)}
                                                        className="p-1.5 rounded bg-red-500/20 text-red-400 hover:bg-red-500/30"
                                                        title="Reject"
                                                    >
                                                        <X size={16} />
                                                    </button>
                                                </div>
                                            )}
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
