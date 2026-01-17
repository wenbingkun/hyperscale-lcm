import React, { useEffect, useState } from 'react';
import { useParams, Link } from 'react-router-dom';
import { GlassCard } from '../components/GlassCard';
import { ArrowLeft, Server, Cpu, HardDrive, Wifi, WifiOff, Container, Activity } from 'lucide-react';
import { type Satellite, fetchSatellites } from '../api/client';
import { useWebSocketContext } from '../contexts/WebSocketContext';

export const SatelliteDetailPage: React.FC = () => {
    const { id } = useParams<{ id: string }>();
    const [satellite, setSatellite] = useState<Satellite | null>(null);
    const [loading, setLoading] = useState(true);
    const { lastEvent } = useWebSocketContext();

    useEffect(() => {
        const loadSatellite = async () => {
            const satellites = await fetchSatellites();
            const found = satellites.find((s) => s.id === id);
            setSatellite(found || null);
            setLoading(false);
        };
        loadSatellite();
    }, [id]);

    // Real-time updates
    useEffect(() => {
        if (lastEvent?.type === 'NODE_STATUS' && lastEvent.nodeId === id) {
            fetchSatellites().then((satellites) => {
                const found = satellites.find((s) => s.id === id);
                if (found) setSatellite(found);
            });
        }
    }, [lastEvent, id]);

    if (loading) {
        return (
            <div className="flex items-center justify-center h-64">
                <Activity className="animate-pulse text-cyan-400" size={48} />
            </div>
        );
    }

    if (!satellite) {
        return (
            <div className="text-center py-12">
                <h2 className="text-2xl font-bold text-white mb-4">Satellite Not Found</h2>
                <Link to="/satellites" className="text-cyan-400 hover:text-cyan-300">
                    ← Back to Satellites
                </Link>
            </div>
        );
    }

    const isOnline = satellite.status === 'ONLINE';

    return (
        <div className="space-y-6">
            {/* Header */}
            <div className="flex items-center gap-4">
                <Link
                    to="/satellites"
                    className="p-2 rounded-lg bg-white/5 hover:bg-white/10 transition-colors"
                >
                    <ArrowLeft size={20} />
                </Link>
                <div className="flex items-center gap-3">
                    <div className={`p-2 rounded-lg ${isOnline ? 'bg-green-500/20' : 'bg-red-500/20'}`}>
                        {isOnline ? <Wifi className="text-green-400" size={24} /> : <WifiOff className="text-red-400" size={24} />}
                    </div>
                    <div>
                        <h1 className="text-2xl font-bold text-white">{satellite.hostname}</h1>
                        <p className="text-gray-400 text-sm">{satellite.ipAddress}</p>
                    </div>
                </div>
            </div>

            <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-6">
                {/* Node Info */}
                <GlassCard title="Node Information">
                    <div className="space-y-4">
                        <div className="flex items-center gap-3">
                            <Server className="text-cyan-400" size={20} />
                            <div>
                                <p className="text-gray-400 text-sm">Node ID</p>
                                <p className="text-white font-mono text-sm">{satellite.id}</p>
                            </div>
                        </div>
                        <div className="grid grid-cols-2 gap-4 pt-4 border-t border-white/10">
                            <div>
                                <p className="text-gray-400 text-sm">OS Version</p>
                                <p className="text-white">{satellite.osVersion || 'Unknown'}</p>
                            </div>
                            <div>
                                <p className="text-gray-400 text-sm">Agent Version</p>
                                <p className="text-white">{satellite.agentVersion || 'Unknown'}</p>
                            </div>
                        </div>
                    </div>
                </GlassCard>

                {/* Status */}
                <GlassCard title="Connection Status">
                    <div className="space-y-4">
                        <div className={`inline-flex items-center gap-2 px-3 py-1.5 rounded-full ${isOnline ? 'bg-green-500/20 text-green-400' : 'bg-red-500/20 text-red-400'
                            }`}>
                            <span className={`w-2 h-2 rounded-full ${isOnline ? 'bg-green-400 animate-pulse' : 'bg-red-400'}`} />
                            {satellite.status}
                        </div>
                        <div className="pt-4 border-t border-white/10">
                            <p className="text-gray-400 text-sm">Last Heartbeat</p>
                            <p className="text-white">
                                {satellite.lastHeartbeat
                                    ? new Date(satellite.lastHeartbeat).toLocaleString()
                                    : 'Never'}
                            </p>
                        </div>
                    </div>
                </GlassCard>

                {/* Resources - Placeholder */}
                <GlassCard title="Resources">
                    <div className="space-y-4">
                        <div className="flex items-center justify-between">
                            <div className="flex items-center gap-2">
                                <Cpu className="text-purple-400" size={16} />
                                <span className="text-gray-300">CPU</span>
                            </div>
                            <span className="text-white font-medium">32 Cores</span>
                        </div>
                        <div className="flex items-center justify-between">
                            <div className="flex items-center gap-2">
                                <HardDrive className="text-blue-400" size={16} />
                                <span className="text-gray-300">Memory</span>
                            </div>
                            <span className="text-white font-medium">256 GB</span>
                        </div>
                        <div className="flex items-center justify-between">
                            <div className="flex items-center gap-2">
                                <Activity className="text-green-400" size={16} />
                                <span className="text-gray-300">GPU</span>
                            </div>
                            <span className="text-white font-medium">8x A100</span>
                        </div>
                    </div>
                </GlassCard>

                {/* Running Containers */}
                <GlassCard title="Running Containers" className="md:col-span-2 lg:col-span-3">
                    <div className="space-y-3">
                        {isOnline ? (
                            <>
                                <div className="flex items-center justify-between p-3 rounded-lg bg-white/5">
                                    <div className="flex items-center gap-3">
                                        <Container className="text-cyan-400" size={18} />
                                        <div>
                                            <p className="text-white font-medium">training-model-v2</p>
                                            <p className="text-gray-400 text-sm">pytorch:2.1-cuda12</p>
                                        </div>
                                    </div>
                                    <span className="px-2 py-1 rounded text-xs bg-green-500/20 text-green-400">Running</span>
                                </div>
                                <div className="flex items-center justify-between p-3 rounded-lg bg-white/5">
                                    <div className="flex items-center gap-3">
                                        <Container className="text-purple-400" size={18} />
                                        <div>
                                            <p className="text-white font-medium">inference-service</p>
                                            <p className="text-gray-400 text-sm">nvidia/triton:23.10</p>
                                        </div>
                                    </div>
                                    <span className="px-2 py-1 rounded text-xs bg-green-500/20 text-green-400">Running</span>
                                </div>
                            </>
                        ) : (
                            <p className="text-gray-500 text-center py-8">Node is offline. No container information available.</p>
                        )}
                    </div>
                </GlassCard>
            </div>
        </div>
    );
};
