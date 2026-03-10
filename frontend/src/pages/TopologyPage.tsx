import React, { useEffect, useState, useMemo } from 'react';
import { fetchSatellites, fetchJobs, type Satellite, type Job } from '../api/client';
import { GlassCard } from '../components/GlassCard';
import { Server, Grid, Network, Cpu, Zap } from 'lucide-react';
import { motion } from 'framer-motion';

type StatBubbleProps = {
    label: string;
    value: string;
    icon: React.ReactNode;
    color: string;
};

const StatBubble: React.FC<StatBubbleProps> = ({ label, value, icon, color }) => (
    <div className="flex items-center gap-3 rounded-xl border border-white/10 bg-white/5 p-4">
        <div className={`rounded-lg p-3 ${color} bg-opacity-20 text-current`}>
            {icon}
        </div>
        <div>
            <p className="text-sm text-gray-400">{label}</p>
            <p className="text-xl font-bold text-white">{value}</p>
        </div>
    </div>
);

export const TopologyPage: React.FC = () => {
    const [satellites, setSatellites] = useState<Satellite[]>([]);
    const [jobs, setJobs] = useState<Job[]>([]);
    const [loading, setLoading] = useState(true);
    const [filter, setFilter] = useState<'ALL' | 'ACTIVE' | 'IDLE'>('ALL');

    useEffect(() => {
        const loadTopologyData = async () => {
            const [fetchedSatellites, fetchedJobs] = await Promise.all([
                fetchSatellites(),
                fetchJobs()
            ]);
            setSatellites(fetchedSatellites);
            setJobs(fetchedJobs);
            setLoading(false);
        };

        loadTopologyData();
        const interval = setInterval(loadTopologyData, 5000);
        return () => clearInterval(interval);
    }, []);

    // Create a color map for active jobs so we easily distinguish them.
    const jobColors = useMemo(() => {
        const colors = [
            'bg-purple-500', 'bg-cyan-500', 'bg-emerald-500',
            'bg-amber-500', 'bg-rose-500', 'bg-indigo-500', 'bg-sky-500'
        ];
        const activeJobs = jobs.filter(j => j.status === 'RUNNING' || j.status === 'SCHEDULED');
        const map = new Map<string, string>();
        activeJobs.forEach((job, idx) => {
            map.set(job.id, colors[idx % colors.length]);
        });
        return map;
    }, [jobs]);

    // Map jobs to nodes
    const getJobsForNode = (nodeId: string) => {
        return jobs.filter(
            j => j.assignedNodeId === nodeId &&
                (j.status === 'RUNNING' || j.status === 'SCHEDULED')
        );
    };

    const displayNodes = satellites.filter(s => {
        if (filter === 'ACTIVE') return getJobsForNode(s.id).length > 0;
        if (filter === 'IDLE') return getJobsForNode(s.id).length === 0;
        return true;
    }).sort((a, b) => a.id.localeCompare(b.id));

    // Fallback to 8 GPUs per node until the backend exposes exact inventory for every node.
    const totalGpus = satellites.reduce((sum, satellite) => sum + (satellite.gpuCount ?? 8), 0);
    const utilizedGpus = jobs
        .filter(j => j.status === 'RUNNING' || j.status === 'SCHEDULED')
        .reduce((sum, job) => sum + (job.requiredGpuCount ?? 8), 0);

    if (loading) {
        return (
            <div className="flex items-center justify-center p-12">
                <p className="text-cyan-400 animate-pulse text-lg">Loading Topology Map...</p>
            </div>
        );
    }

    return (
        <div className="space-y-6">
            <div className="flex flex-col md:flex-row md:items-center justify-between gap-4">
                <div>
                    <h1 className="text-2xl font-bold text-white flex items-center gap-2">
                        <Network className="text-cyan-400" />
                        Infrastructure Topology
                    </h1>
                    <p className="text-gray-400 text-sm mt-1">Real-time GPU and NVLink allocation mapped across physical computing racks.</p>
                </div>

                <div className="flex items-center gap-2 bg-black/40 p-1.5 rounded-lg border border-white/10">
                    {(['ALL', 'ACTIVE', 'IDLE'] as const).map(f => (
                        <button
                            key={f}
                            onClick={() => setFilter(f)}
                            className={`px-4 py-1.5 rounded-md text-sm font-medium transition-all ${filter === f
                                ? 'bg-white/10 text-white shadow-sm'
                                : 'text-gray-400 hover:text-white hover:bg-white/5'
                                }`}
                        >
                            {f.charAt(0) + f.slice(1).toLowerCase()}
                        </button>
                    ))}
                </div>
            </div>

            <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-4 gap-4">
                <StatBubble label="Total Nodes" value={satellites.length.toString()} icon={<Server size={24} className="text-cyan-400" />} color="bg-cyan-400" />
                <StatBubble label="Total GPUs" value={totalGpus.toString()} icon={<Grid size={24} className="text-purple-400" />} color="bg-purple-400" />
                <StatBubble label="Active Allocations" value={`${Math.round((utilizedGpus / Math.max(totalGpus, 1)) * 100)}%`} icon={<Zap size={24} className="text-yellow-400" />} color="bg-yellow-400" />
                <StatBubble label="Avg Bandwidth" value="600 GB/s" icon={<Network size={24} className="text-green-400" />} color="bg-green-400" />
            </div>

            <GlassCard title="Global Allocation Heatmap" className="min-h-[500px]">
                {displayNodes.length === 0 ? (
                    <div className="text-center py-24 text-gray-500">
                        No nodes matched the selected filter.
                    </div>
                ) : (
                    <div className="grid grid-cols-1 md:grid-cols-2 xl:grid-cols-3 gap-6">
                        {displayNodes.map(node => {
                            const isOnline = node.status === 'ONLINE';
                            const assignedJobs = getJobsForNode(node.id);
                            // Simple simulation: We have 8 GPUs per node. If a job is assigned, color all 8 for now.
                            // In a full implementation we'd read `job.requiredGpuCount` and pack them correctly.
                            const hasJob = assignedJobs.length > 0;
                            const jobColor = hasJob ? jobColors.get(assignedJobs[0].id) || 'bg-cyan-500' : 'bg-gray-800';

                            return (
                                <motion.div
                                    key={node.id}
                                    initial={{ opacity: 0, scale: 0.95 }}
                                    animate={{ opacity: 1, scale: 1 }}
                                    className="p-4 rounded-xl bg-black/40 border border-white/10 hover:border-cyan-500/50 transition-colors group"
                                >
                                    <div className="flex items-center justify-between mb-4 border-b border-white/5 pb-3">
                                        <div className="flex items-center gap-2">
                                            <div className={`w-2 h-2 rounded-full ${isOnline ? 'bg-green-400 shadow-[0_0_8px_rgba(74,222,128,0.8)]' : 'bg-gray-600'}`} />
                                            <span className="text-white font-medium font-mono text-sm">{node.hostname}</span>
                                        </div>
                                        <div className="flex items-center text-xs text-gray-500 font-mono">
                                            {node.ipAddress}
                                        </div>
                                    </div>

                                    {/* Simulated Chassis Grid (2x4) */}
                                    <div className="relative">
                                        {/* NVSwitch Backbone graphic */}
                                        <div className="absolute left-1/2 top-4 bottom-4 w-px bg-gradient-to-b from-transparent via-cyan-500/30 to-transparent -translate-x-1/2 z-0"></div>

                                        <div className="grid grid-cols-2 gap-x-8 gap-y-3 relative z-10">
                                            {[...Array(8)].map((_, i) => (
                                                <div
                                                    key={i}
                                                    className={`
                                                        h-5 rounded-md flex items-center px-2 text-[10px] font-mono text-white/70 
                                                        border border-white/10
                                                        ${hasJob ? jobColor : (isOnline ? 'bg-white/5' : 'bg-transparent border-dashed')}
                                                        ${hasJob ? 'opacity-90' : 'opacity-40'}
                                                    `}
                                                    title={hasJob ? `Job ID: ${assignedJobs[0].id}` : 'Idle Slot'}
                                                >
                                                    <Cpu size={10} className="mr-1 opacity-50" />
                                                    GPU-{i}
                                                </div>
                                            ))}
                                        </div>
                                    </div>

                                    <div className="mt-4 pt-3 border-t border-white/5 flex justify-between items-center text-xs">
                                        <span className="text-gray-400">
                                            {hasJob ? (
                                                <span className="text-cyan-400 shrink-0 inline-flex items-center gap-1.5 truncate max-w-[150px]">
                                                    <Zap size={12} fill="currentColor" />
                                                    {assignedJobs[0].name}
                                                </span>
                                            ) : (
                                                'Idle'
                                            )}
                                        </span>
                                        <span className="text-white/40 font-mono">{node.model || 'Unknown'}</span>
                                    </div>
                                </motion.div>
                            );
                        })}
                    </div>
                )}
            </GlassCard>
        </div>
    );
};
