import React, { useEffect, useState } from 'react';
import { useParams, Link } from 'react-router-dom';
import { GlassCard } from '../components/GlassCard';
import { ArrowLeft, Server, Cpu, HardDrive, Wifi, WifiOff, Container, Activity, Zap } from 'lucide-react';
import { type Satellite, fetchSatellites } from '../api/client';
import { useWebSocketContext } from '../contexts/WebSocketContext';

// GPU 拓扑类型定义
interface GpuInfo {
    id: number;
    model: string;
    memory: number;
    utilization: number;
    temperature: number;
}

// 模拟 GPU 拓扑数据
const mockGpuTopology = {
    gpus: [
        { id: 0, model: 'A100', memory: 80, utilization: 45, temperature: 62 },
        { id: 1, model: 'A100', memory: 80, utilization: 78, temperature: 68 },
        { id: 2, model: 'A100', memory: 80, utilization: 23, temperature: 55 },
        { id: 3, model: 'A100', memory: 80, utilization: 92, temperature: 72 },
        { id: 4, model: 'A100', memory: 80, utilization: 56, temperature: 64 },
        { id: 5, model: 'A100', memory: 80, utilization: 34, temperature: 58 },
        { id: 6, model: 'A100', memory: 80, utilization: 67, temperature: 66 },
        { id: 7, model: 'A100', memory: 80, utilization: 12, temperature: 52 },
    ],
    topology: 'NVSwitch',
    nvlinkBandwidth: 600,
    // NVLink connections (GPU pairs)
    nvlinks: [
        [0, 1], [0, 2], [0, 3],
        [1, 2], [1, 3],
        [2, 3],
        [4, 5], [4, 6], [4, 7],
        [5, 6], [5, 7],
        [6, 7],
        // Cross-group via NVSwitch
        [0, 4], [1, 5], [2, 6], [3, 7]
    ]
};

// GPU Card Component
const GpuCard: React.FC<{ gpu: GpuInfo; isSelected?: boolean }> = ({ gpu, isSelected }) => {
    const getUtilColor = (util: number) => {
        if (util >= 80) return 'text-red-400 bg-red-500';
        if (util >= 50) return 'text-yellow-400 bg-yellow-500';
        return 'text-green-400 bg-green-500';
    };

    const getTempColor = (temp: number) => {
        if (temp >= 70) return 'text-red-400';
        if (temp >= 60) return 'text-yellow-400';
        return 'text-green-400';
    };

    return (
        <div className={`p-3 rounded-lg bg-white/5 border transition-all ${isSelected ? 'border-cyan-500 shadow-[0_0_10px_rgba(0,255,255,0.3)]' : 'border-white/10'}`}>
            <div className="flex items-center justify-between mb-2">
                <div className="flex items-center gap-2">
                    <Zap size={14} className="text-purple-400" />
                    <span className="text-white font-medium">GPU {gpu.id}</span>
                </div>
                <span className="text-xs text-gray-400">{gpu.model}</span>
            </div>
            <div className="space-y-2">
                <div className="flex items-center justify-between text-xs">
                    <span className="text-gray-400">Utilization</span>
                    <span className={getUtilColor(gpu.utilization).split(' ')[0]}>{gpu.utilization}%</span>
                </div>
                <div className="h-1.5 bg-white/10 rounded-full overflow-hidden">
                    <div
                        className={`h-full ${getUtilColor(gpu.utilization).split(' ')[1]} transition-all`}
                        style={{ width: `${gpu.utilization}%` }}
                    />
                </div>
                <div className="flex justify-between text-xs text-gray-400">
                    <span>{gpu.memory} GB</span>
                    <span className={getTempColor(gpu.temperature)}>{gpu.temperature}°C</span>
                </div>
            </div>
        </div>
    );
};

// NVLink Topology Visualization
const GpuTopologyView: React.FC<{ isOnline: boolean }> = ({ isOnline }) => {
    if (!isOnline) {
        return (
            <div className="text-center py-8 text-gray-500">
                Node is offline. GPU topology unavailable.
            </div>
        );
    }

    const { gpus, topology, nvlinkBandwidth } = mockGpuTopology;

    return (
        <div className="space-y-4">
            {/* Topology Header */}
            <div className="flex items-center justify-between">
                <div className="flex items-center gap-2">
                    <div className="px-2 py-1 rounded bg-purple-500/20 text-purple-400 text-xs font-medium">
                        {topology}
                    </div>
                    <span className="text-sm text-gray-400">
                        {nvlinkBandwidth} GB/s per link
                    </span>
                </div>
                <div className="text-sm text-gray-400">
                    {gpus.length} GPUs
                </div>
            </div>

            {/* GPU Grid - 2x4 layout for 8 GPUs */}
            <div className="grid grid-cols-4 gap-3">
                {gpus.slice(0, 4).map((gpu) => (
                    <GpuCard key={gpu.id} gpu={gpu} />
                ))}
            </div>

            {/* NVSwitch indicator */}
            <div className="relative py-2">
                <div className="absolute inset-x-0 top-1/2 h-0.5 bg-gradient-to-r from-purple-500/50 via-cyan-500/50 to-purple-500/50" />
                <div className="relative flex justify-center">
                    <div className="px-3 py-1 rounded-full bg-gray-900 border border-purple-500/50 text-xs text-purple-400">
                        NVSwitch Fabric
                    </div>
                </div>
            </div>

            <div className="grid grid-cols-4 gap-3">
                {gpus.slice(4, 8).map((gpu) => (
                    <GpuCard key={gpu.id} gpu={gpu} />
                ))}
            </div>

            {/* Aggregate Stats */}
            <div className="grid grid-cols-3 gap-4 pt-4 border-t border-white/10">
                <div className="text-center">
                    <p className="text-2xl font-bold text-white">
                        {Math.round(gpus.reduce((sum, g) => sum + g.utilization, 0) / gpus.length)}%
                    </p>
                    <p className="text-xs text-gray-400">Avg Utilization</p>
                </div>
                <div className="text-center">
                    <p className="text-2xl font-bold text-white">
                        {gpus.reduce((sum, g) => sum + g.memory, 0)} GB
                    </p>
                    <p className="text-xs text-gray-400">Total VRAM</p>
                </div>
                <div className="text-center">
                    <p className="text-2xl font-bold text-white">
                        {Math.round(gpus.reduce((sum, g) => sum + g.temperature, 0) / gpus.length)}°C
                    </p>
                    <p className="text-xs text-gray-400">Avg Temperature</p>
                </div>
            </div>
        </div>
    );
};

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
        if (lastEvent?.type === 'NODE_STATUS' && lastEvent.payload?.nodeId === id) {
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

            <div className="grid grid-cols-1 lg:grid-cols-3 gap-6">
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
                            {satellite.model && (
                                <div className="col-span-2 pt-2 border-t border-white/5">
                                    <p className="text-gray-400 text-sm">Hardware Model</p>
                                    <p className="text-white font-mono text-sm">{satellite.model}</p>
                                </div>
                            )}
                            {satellite.bmcIp && (
                                <div>
                                    <p className="text-gray-400 text-sm">BMC IP</p>
                                    <p className="text-cyan-400 font-mono text-sm">{satellite.bmcIp}</p>
                                </div>
                            )}
                            {satellite.systemSerial && (
                                <div>
                                    <p className="text-gray-400 text-sm">Serial Number</p>
                                    <p className="text-white font-mono text-sm">{satellite.systemSerial}</p>
                                </div>
                            )}
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

                {/* Resources */}
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
                                <Zap className="text-green-400" size={16} />
                                <span className="text-gray-300">GPU</span>
                            </div>
                            <span className="text-white font-medium">8x A100</span>
                        </div>
                        {satellite.systemTemperatureCelsius !== undefined && (
                            <div className="flex items-center justify-between pt-2 border-t border-white/5">
                                <span className="text-gray-300 text-sm">System Temp</span>
                                <span className={`text-sm font-mono ${(satellite.systemTemperatureCelsius >= 75) ? 'text-red-400' : (satellite.systemTemperatureCelsius >= 60) ? 'text-yellow-400' : 'text-green-400'}`}>
                                    {satellite.systemTemperatureCelsius}°C
                                </span>
                            </div>
                        )}
                        {satellite.powerState && (
                            <div className="flex items-center justify-between">
                                <span className="text-gray-300 text-sm">Chassis Power</span>
                                <span className="text-gray-400 text-sm">{satellite.powerState}</span>
                            </div>
                        )}
                    </div>
                </GlassCard>
            </div>

            {/* GPU Topology - Full Width */}
            <GlassCard title="GPU Topology (NVLink/NVSwitch)">
                <GpuTopologyView isOnline={isOnline} />
            </GlassCard>

            {/* Running Containers */}
            <GlassCard title="Running Containers">
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
    );
};
