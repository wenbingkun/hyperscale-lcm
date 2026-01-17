import React, { useEffect, useState } from 'react';
import { GlassCard } from '../components/GlassCard';
import { Wifi, Check, X, Plus, RefreshCw, Monitor } from 'lucide-react';

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

const API_BASE = 'http://localhost:8080';

export const DiscoveryPage: React.FC = () => {
    const [devices, setDevices] = useState<DiscoveredDevice[]>([]);
    const [loading, setLoading] = useState(true);
    const [pendingCount, setPendingCount] = useState(0);

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

    useEffect(() => {
        loadDevices();
        loadPendingCount();
    }, []);

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
                    <p className="text-gray-400 mt-1">Manage discovered devices and approve for onboarding</p>
                </div>
                <div className="flex gap-3">
                    {pendingCount > 0 && (
                        <div className="px-4 py-2 rounded-lg bg-yellow-500/20 border border-yellow-500/30 text-yellow-400">
                            <span className="font-bold">{pendingCount}</span> pending approval
                        </div>
                    )}
                    <button
                        className="flex items-center gap-2 px-4 py-2 rounded-lg bg-cyan-500/20 border border-cyan-500/30 text-cyan-400 hover:bg-cyan-500/30 transition-colors opacity-50 cursor-not-allowed"
                        disabled
                        title="Coming soon"
                    >
                        <Plus size={18} />
                        Add Device
                    </button>
                    <button
                        onClick={loadDevices}
                        className="p-2 rounded-lg bg-white/5 border border-white/10 hover:bg-white/10 text-white transition-colors"
                    >
                        <RefreshCw size={18} className={loading ? 'animate-spin' : ''} />
                    </button>
                </div>
            </header>

            <GlassCard className="min-h-[500px]">
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
