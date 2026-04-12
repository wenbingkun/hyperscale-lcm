import React, { useEffect, useEffectEvent, useMemo, useState } from 'react';
import { Activity, Cpu, Grid, Network, Server, Zap } from 'lucide-react';
import { motion } from 'framer-motion';
import { fetchJobs, fetchSatellites, type Job, type Satellite } from '../api/client';
import { GlassCard } from '../components/GlassCard';
import { useWebSocketContext } from '../contexts/WebSocketContext';

type FilterMode = 'ALL' | 'ACTIVE' | 'IDLE' | 'OFFLINE';

type JobPalette = {
    solid: string;
    surface: string;
    text: string;
};

type ActiveJob = Job & {
    gpuDemand: number;
};

type SlotAssignment = {
    index: number;
    job: ActiveJob | null;
    palette: JobPalette | null;
};

type NodeCard = {
    node: Satellite;
    assignedJobs: ActiveJob[];
    slots: SlotAssignment[];
    slotCount: number;
    reportedGpuCount: number;
    allocatedGpuCount: number;
    freeGpuCount: number;
    utilizationPercent: number;
    isOnline: boolean;
    zoneLabel: string;
    rackLabel: string;
    fabricLabel: string;
    modelLabel: string;
};

type RackGroup = {
    rackLabel: string;
    nodes: NodeCard[];
};

type ZoneGroup = {
    zoneLabel: string;
    racks: RackGroup[];
    nodeCount: number;
    onlineCount: number;
    totalGpuCount: number;
    allocatedGpuCount: number;
    fabrics: string[];
};

type FabricSummary = {
    fabricId: string;
    nodeCount: number;
    zoneCount: number;
    totalGpuCount: number;
    allocatedGpuCount: number;
};

type StatBubbleProps = {
    label: string;
    value: string;
    icon: React.ReactNode;
    color: string;
};

const ACTIVE_JOB_STATUSES = new Set(['RUNNING', 'SCHEDULED']);
const TOPOLOGY_REFRESH_EVENTS = new Set(['SCHEDULE_EVENT', 'JOB_STATUS', 'NODE_STATUS', 'HEARTBEAT_UPDATE']);
const JOB_PALETTES: JobPalette[] = [
    { solid: 'bg-cyan-500', surface: 'bg-cyan-500/15 border-cyan-400/30', text: 'text-cyan-200' },
    { solid: 'bg-emerald-500', surface: 'bg-emerald-500/15 border-emerald-400/30', text: 'text-emerald-200' },
    { solid: 'bg-amber-500', surface: 'bg-amber-500/15 border-amber-400/30', text: 'text-amber-200' },
    { solid: 'bg-fuchsia-500', surface: 'bg-fuchsia-500/15 border-fuchsia-400/30', text: 'text-fuchsia-200' },
    { solid: 'bg-sky-500', surface: 'bg-sky-500/15 border-sky-400/30', text: 'text-sky-200' },
    { solid: 'bg-rose-500', surface: 'bg-rose-500/15 border-rose-400/30', text: 'text-rose-200' },
];

const StatBubble: React.FC<StatBubbleProps> = ({ label, value, icon, color }) => (
    <div className="flex items-center gap-3 rounded-2xl border border-white/10 bg-white/[0.04] p-4">
        <div className={`rounded-xl p-3 ${color} bg-opacity-20 text-current`}>
            {icon}
        </div>
        <div>
            <p className="text-sm text-gray-400">{label}</p>
            <p className="text-xl font-bold text-white">{value}</p>
        </div>
    </div>
);

function getGpuDemand(job: Job): number {
    return Math.max(job.requiredGpuCount ?? 0, 1);
}

function isActiveJob(job: Job): boolean {
    return ACTIVE_JOB_STATUSES.has(job.status);
}

function formatZone(zoneId?: string): string {
    return zoneId && zoneId.trim() ? zoneId : 'unassigned';
}

function formatRack(rackId?: string): string {
    return rackId && rackId.trim() ? rackId : 'unassigned-rack';
}

function formatFabric(fabricId?: string): string {
    return fabricId && fabricId.trim() ? fabricId : 'unassigned-fabric';
}

function sortByLabel<T>(items: T[], select: (item: T) => string): T[] {
    return [...items].sort((left, right) => select(left).localeCompare(select(right)));
}

export const TopologyPage: React.FC = () => {
    const [satellites, setSatellites] = useState<Satellite[]>([]);
    const [jobs, setJobs] = useState<Job[]>([]);
    const [loading, setLoading] = useState(true);
    const [filter, setFilter] = useState<FilterMode>('ALL');
    const { lastEvent } = useWebSocketContext();

    const refreshTopology = useEffectEvent(async () => {
        setLoading(true);
        try {
            const [fetchedSatellites, fetchedJobs] = await Promise.all([
                fetchSatellites(),
                fetchJobs(),
            ]);
            setSatellites(fetchedSatellites);
            setJobs(fetchedJobs);
        } catch (error) {
            console.error('Failed to refresh topology', error);
        } finally {
            setLoading(false);
        }
    });

    useEffect(() => {
        void refreshTopology();
        const interval = setInterval(() => {
            void refreshTopology();
        }, 5000);
        return () => {
            clearInterval(interval);
        };
    }, []);

    useEffect(() => {
        if (lastEvent && TOPOLOGY_REFRESH_EVENTS.has(lastEvent.type)) {
            void refreshTopology();
        }
    }, [lastEvent]);

    const activeJobs = useMemo<ActiveJob[]>(
        () => jobs.filter(isActiveJob).map((job) => ({ ...job, gpuDemand: getGpuDemand(job) })),
        [jobs],
    );

    const jobPaletteById = useMemo(() => {
        const paletteById = new Map<string, JobPalette>();
        activeJobs.forEach((job, index) => {
            paletteById.set(job.id, JOB_PALETTES[index % JOB_PALETTES.length]);
        });
        return paletteById;
    }, [activeJobs]);

    const jobsByNodeId = useMemo(() => {
        const grouped = new Map<string, ActiveJob[]>();

        activeJobs.forEach((job) => {
            if (!job.assignedNodeId) {
                return;
            }
            const current = grouped.get(job.assignedNodeId) ?? [];
            current.push(job);
            grouped.set(job.assignedNodeId, current);
        });

        grouped.forEach((assignedJobs, nodeId) => {
            grouped.set(
                nodeId,
                sortByLabel(assignedJobs, (job) => `${job.status}-${job.name ?? job.id}`),
            );
        });

        return grouped;
    }, [activeJobs]);

    const nodeCards = useMemo<NodeCard[]>(() => {
        return sortByLabel(
            satellites.map((node) => {
                const assignedJobs = jobsByNodeId.get(node.id) ?? [];
                const reportedGpuCount = Math.max(node.gpuCount ?? 0, 0);
                const allocatedGpuCount = assignedJobs.reduce((sum, job) => sum + job.gpuDemand, 0);
                const slotCount = Math.max(reportedGpuCount, allocatedGpuCount);
                const expandedAssignments = assignedJobs.flatMap((job) =>
                    Array.from({ length: job.gpuDemand }, () => job),
                );
                const slots = Array.from({ length: slotCount }, (_, index) => {
                    const job = expandedAssignments[index] ?? null;
                    return {
                        index,
                        job,
                        palette: job ? jobPaletteById.get(job.id) ?? JOB_PALETTES[0] : null,
                    };
                });

                return {
                    node,
                    assignedJobs,
                    slots,
                    slotCount,
                    reportedGpuCount,
                    allocatedGpuCount,
                    freeGpuCount: Math.max(slotCount - allocatedGpuCount, 0),
                    utilizationPercent: slotCount === 0 ? 0 : Math.round((allocatedGpuCount / slotCount) * 100),
                    isOnline: node.status === 'ONLINE',
                    zoneLabel: formatZone(node.zoneId),
                    rackLabel: formatRack(node.rackId),
                    fabricLabel: formatFabric(node.ibFabricId),
                    modelLabel: node.systemModel || node.model || 'Unknown model',
                };
            }),
            (card) => `${card.zoneLabel}-${card.rackLabel}-${card.node.hostname || card.node.id}`,
        );
    }, [jobPaletteById, jobsByNodeId, satellites]);

    const filterCounts = useMemo<Record<FilterMode, number>>(() => {
        return nodeCards.reduce(
            (counts, card) => {
                counts.ALL += 1;
                if (!card.isOnline) {
                    counts.OFFLINE += 1;
                }
                if (card.assignedJobs.length > 0) {
                    counts.ACTIVE += 1;
                } else if (card.isOnline) {
                    counts.IDLE += 1;
                }
                return counts;
            },
            { ALL: 0, ACTIVE: 0, IDLE: 0, OFFLINE: 0 },
        );
    }, [nodeCards]);

    const visibleNodeCards = useMemo(() => {
        return nodeCards.filter((card) => {
            if (filter === 'ACTIVE') {
                return card.assignedJobs.length > 0;
            }
            if (filter === 'IDLE') {
                return card.isOnline && card.assignedJobs.length === 0;
            }
            if (filter === 'OFFLINE') {
                return !card.isOnline;
            }
            return true;
        });
    }, [filter, nodeCards]);

    const zoneGroups = useMemo<ZoneGroup[]>(() => {
        const grouped = new Map<
            string,
            {
                zoneLabel: string;
                nodes: NodeCard[];
                fabrics: Set<string>;
                totalGpuCount: number;
                allocatedGpuCount: number;
                onlineCount: number;
            }
        >();

        visibleNodeCards.forEach((card) => {
            const current = grouped.get(card.zoneLabel) ?? {
                zoneLabel: card.zoneLabel,
                nodes: [],
                fabrics: new Set<string>(),
                totalGpuCount: 0,
                allocatedGpuCount: 0,
                onlineCount: 0,
            };
            current.nodes.push(card);
            current.totalGpuCount += card.reportedGpuCount;
            current.allocatedGpuCount += card.allocatedGpuCount;
            current.onlineCount += card.isOnline ? 1 : 0;
            if (card.node.ibFabricId) {
                current.fabrics.add(card.node.ibFabricId);
            }
            grouped.set(card.zoneLabel, current);
        });

        return sortByLabel(
            Array.from(grouped.values()).map((zone) => {
                const rackMap = new Map<string, NodeCard[]>();
                zone.nodes.forEach((card) => {
                    const rackNodes = rackMap.get(card.rackLabel) ?? [];
                    rackNodes.push(card);
                    rackMap.set(card.rackLabel, rackNodes);
                });

                const racks = sortByLabel(
                    Array.from(rackMap.entries()).map(([rackLabel, cards]) => ({
                        rackLabel,
                        nodes: sortByLabel(cards, (card) => card.node.hostname || card.node.id),
                    })),
                    (rack) => rack.rackLabel,
                );

                return {
                    zoneLabel: zone.zoneLabel,
                    racks,
                    nodeCount: zone.nodes.length,
                    onlineCount: zone.onlineCount,
                    totalGpuCount: zone.totalGpuCount,
                    allocatedGpuCount: zone.allocatedGpuCount,
                    fabrics: [...zone.fabrics].sort(),
                };
            }),
            (zone) => zone.zoneLabel,
        );
    }, [visibleNodeCards]);

    const fabricSummaries = useMemo<FabricSummary[]>(() => {
        const grouped = new Map<
            string,
            {
                nodeIds: Set<string>;
                zones: Set<string>;
                totalGpuCount: number;
                allocatedGpuCount: number;
            }
        >();

        nodeCards.forEach((card) => {
            if (!card.node.ibFabricId) {
                return;
            }
            const current = grouped.get(card.node.ibFabricId) ?? {
                nodeIds: new Set<string>(),
                zones: new Set<string>(),
                totalGpuCount: 0,
                allocatedGpuCount: 0,
            };
            current.nodeIds.add(card.node.id);
            current.zones.add(card.zoneLabel);
            current.totalGpuCount += card.reportedGpuCount;
            current.allocatedGpuCount += card.allocatedGpuCount;
            grouped.set(card.node.ibFabricId, current);
        });

        return sortByLabel(
            Array.from(grouped.entries()).map(([fabricId, summary]) => ({
                fabricId,
                nodeCount: summary.nodeIds.size,
                zoneCount: summary.zones.size,
                totalGpuCount: summary.totalGpuCount,
                allocatedGpuCount: summary.allocatedGpuCount,
            })),
            (fabric) => fabric.fabricId,
        );
    }, [nodeCards]);

    const totalGpuCount = useMemo(
        () => nodeCards.reduce((sum, card) => sum + card.reportedGpuCount, 0),
        [nodeCards],
    );
    const allocatedGpuCount = useMemo(
        () => nodeCards.reduce((sum, card) => sum + card.allocatedGpuCount, 0),
        [nodeCards],
    );
    const averageNvlinkBandwidth = useMemo(() => {
        const nodesWithNvlink = satellites.filter((node) => (node.nvlinkBandwidthGbps ?? 0) > 0);
        if (nodesWithNvlink.length === 0) {
            return 0;
        }
        const totalBandwidth = nodesWithNvlink.reduce(
            (sum, node) => sum + (node.nvlinkBandwidthGbps ?? 0),
            0,
        );
        return Math.round(totalBandwidth / nodesWithNvlink.length);
    }, [satellites]);

    if (loading) {
        return (
            <div className="flex items-center justify-center p-12">
                <p className="animate-pulse text-lg text-cyan-400">Loading topology fabric map...</p>
            </div>
        );
    }

    return (
        <div className="space-y-6">
            <div className="flex flex-col gap-4 md:flex-row md:items-center md:justify-between">
                <div>
                    <h1 className="flex items-center gap-2 text-2xl font-bold text-white">
                        <Network className="text-cyan-400" />
                        Allocation Topology
                    </h1>
                    <p className="mt-1 max-w-3xl text-sm text-gray-400">
                        Group scheduled workloads by zone, rack, GPU topology, and IB fabric so the
                        dispatch result can be reviewed against the physical layout instead of a flat
                        job list.
                    </p>
                </div>

                <div className="flex items-center gap-2 rounded-xl border border-white/10 bg-black/30 p-1.5">
                    {([
                        { key: 'ALL', label: 'All' },
                        { key: 'ACTIVE', label: 'Active' },
                        { key: 'IDLE', label: 'Idle' },
                        { key: 'OFFLINE', label: 'Offline' },
                    ] as const).map((option) => (
                        <button
                            key={option.key}
                            onClick={() => setFilter(option.key)}
                            className={`rounded-lg px-4 py-2 text-sm font-medium transition-all ${
                                filter === option.key
                                    ? 'bg-white/10 text-white shadow-sm'
                                    : 'text-gray-400 hover:bg-white/5 hover:text-white'
                            }`}
                        >
                            {option.label}
                            <span className="ml-2 text-xs text-gray-500">{filterCounts[option.key]}</span>
                        </button>
                    ))}
                </div>
            </div>

            <div className="grid grid-cols-1 gap-4 md:grid-cols-2 xl:grid-cols-4">
                <StatBubble
                    label="Total Nodes"
                    value={nodeCards.length.toString()}
                    icon={<Server size={24} className="text-cyan-400" />}
                    color="bg-cyan-400"
                />
                <StatBubble
                    label="Reported GPUs"
                    value={totalGpuCount.toString()}
                    icon={<Grid size={24} className="text-violet-400" />}
                    color="bg-violet-400"
                />
                <StatBubble
                    label="Allocated GPUs"
                    value={`${allocatedGpuCount}/${totalGpuCount || 0}`}
                    icon={<Zap size={24} className="text-amber-400" />}
                    color="bg-amber-400"
                />
                <StatBubble
                    label="Avg NVLink"
                    value={averageNvlinkBandwidth > 0 ? `${averageNvlinkBandwidth} GB/s` : 'N/A'}
                    icon={<Activity size={24} className="text-emerald-400" />}
                    color="bg-emerald-400"
                />
            </div>

            <GlassCard title="Active Allocation Legend">
                {activeJobs.length === 0 ? (
                    <div className="rounded-2xl border border-dashed border-white/10 bg-black/20 px-4 py-8 text-center text-sm text-gray-500">
                        No active GPU allocations are being tracked right now.
                    </div>
                ) : (
                    <div className="grid grid-cols-1 gap-3 lg:grid-cols-2">
                        {activeJobs.map((job) => {
                            const palette = jobPaletteById.get(job.id) ?? JOB_PALETTES[0];
                            return (
                                <div
                                    key={job.id}
                                    className={`rounded-2xl border px-4 py-3 ${palette.surface}`}
                                >
                                    <div className="flex items-center justify-between gap-3">
                                        <div className="min-w-0">
                                            <div className="flex items-center gap-2">
                                                <span className={`h-2.5 w-2.5 rounded-full ${palette.solid}`} />
                                                <p className={`truncate font-medium ${palette.text}`}>
                                                    {job.name || job.id}
                                                </p>
                                            </div>
                                            <p className="mt-1 text-xs text-gray-400">
                                                Node {job.assignedNodeId || 'pending'} · {job.gpuDemand} GPU
                                                {job.gpuDemand > 1 ? 's' : ''}
                                            </p>
                                        </div>
                                        <span className="rounded-full border border-white/10 bg-white/5 px-2.5 py-1 text-xs text-gray-200">
                                            {job.status}
                                        </span>
                                    </div>
                                </div>
                            );
                        })}
                    </div>
                )}
            </GlassCard>

            <GlassCard title="IB Fabric Overview">
                {fabricSummaries.length === 0 ? (
                    <div className="rounded-2xl border border-dashed border-white/10 bg-black/20 px-4 py-8 text-center text-sm text-gray-500">
                        No IB fabric metadata has been reported by managed nodes yet.
                    </div>
                ) : (
                    <div className="grid grid-cols-1 gap-4 md:grid-cols-2 xl:grid-cols-3">
                        {fabricSummaries.map((fabric) => (
                            <div
                                key={fabric.fabricId}
                                className="rounded-2xl border border-white/10 bg-white/[0.03] p-4"
                            >
                                <div className="flex items-start justify-between gap-3">
                                    <div>
                                        <p className="text-xs uppercase tracking-[0.22em] text-gray-500">
                                            IB Fabric
                                        </p>
                                        <p className="mt-1 font-mono text-sm text-cyan-300">
                                            {fabric.fabricId}
                                        </p>
                                    </div>
                                    <Network className="text-cyan-400" size={18} />
                                </div>
                                <div className="mt-4 grid grid-cols-2 gap-3 text-sm">
                                    <div>
                                        <p className="text-gray-500">Nodes</p>
                                        <p className="text-white">{fabric.nodeCount}</p>
                                    </div>
                                    <div>
                                        <p className="text-gray-500">Zones</p>
                                        <p className="text-white">{fabric.zoneCount}</p>
                                    </div>
                                    <div>
                                        <p className="text-gray-500">Allocated</p>
                                        <p className="text-white">
                                            {fabric.allocatedGpuCount}/{fabric.totalGpuCount} GPU
                                        </p>
                                    </div>
                                    <div>
                                        <p className="text-gray-500">Utilization</p>
                                        <p className="text-white">
                                            {fabric.totalGpuCount === 0
                                                ? '0%'
                                                : `${Math.round((fabric.allocatedGpuCount / fabric.totalGpuCount) * 100)}%`}
                                        </p>
                                    </div>
                                </div>
                            </div>
                        ))}
                    </div>
                )}
            </GlassCard>

            {zoneGroups.length === 0 ? (
                <GlassCard title="Zone Layout">
                    <div className="rounded-2xl border border-dashed border-white/10 bg-black/20 px-4 py-10 text-center text-sm text-gray-500">
                        No nodes matched the current topology filter.
                    </div>
                </GlassCard>
            ) : (
                zoneGroups.map((zone) => (
                    <GlassCard
                        key={zone.zoneLabel}
                        title={`Zone ${zone.zoneLabel}`}
                        className="overflow-visible"
                    >
                        <div className="mb-5 flex flex-wrap gap-2 text-xs text-gray-400">
                            <span className="rounded-full border border-white/10 bg-white/5 px-3 py-1.5">
                                {zone.onlineCount}/{zone.nodeCount} nodes online
                            </span>
                            <span className="rounded-full border border-white/10 bg-white/5 px-3 py-1.5">
                                {zone.allocatedGpuCount}/{zone.totalGpuCount} GPU allocated
                            </span>
                            <span className="rounded-full border border-white/10 bg-white/5 px-3 py-1.5">
                                {zone.racks.length} rack{zone.racks.length > 1 ? 's' : ''}
                            </span>
                            <span className="rounded-full border border-white/10 bg-white/5 px-3 py-1.5">
                                {zone.fabrics.length > 0
                                    ? `${zone.fabrics.length} IB fabric${zone.fabrics.length > 1 ? 's' : ''}`
                                    : 'No IB fabric metadata'}
                            </span>
                        </div>

                        <div className="space-y-5">
                            {zone.racks.map((rack) => (
                                <div key={rack.rackLabel} className="space-y-3">
                                    <div className="flex flex-wrap items-center justify-between gap-3">
                                        <div>
                                            <p className="text-xs uppercase tracking-[0.22em] text-gray-500">
                                                Rack
                                            </p>
                                            <h3 className="mt-1 font-mono text-sm text-white">
                                                {rack.rackLabel}
                                            </h3>
                                        </div>
                                        <p className="text-xs text-gray-500">
                                            {rack.nodes.length} node{rack.nodes.length > 1 ? 's' : ''}
                                        </p>
                                    </div>

                                    <div className="grid grid-cols-1 gap-4 xl:grid-cols-2">
                                        {rack.nodes.map((card) => (
                                            <motion.div
                                                key={card.node.id}
                                                initial={{ opacity: 0, y: 8 }}
                                                animate={{ opacity: 1, y: 0 }}
                                                className="rounded-3xl border border-white/10 bg-[radial-gradient(circle_at_top_left,rgba(34,211,238,0.12),transparent_32%),linear-gradient(180deg,rgba(255,255,255,0.04),rgba(255,255,255,0.02))] p-5"
                                            >
                                                <div className="flex items-start justify-between gap-4">
                                                    <div>
                                                        <div className="flex items-center gap-2">
                                                            <span
                                                                className={`h-2.5 w-2.5 rounded-full ${
                                                                    card.isOnline
                                                                        ? 'bg-emerald-400 shadow-[0_0_12px_rgba(74,222,128,0.9)]'
                                                                        : 'bg-gray-600'
                                                                }`}
                                                            />
                                                            <h4 className="font-mono text-sm text-white">
                                                                {card.node.hostname || card.node.id}
                                                            </h4>
                                                        </div>
                                                        <p className="mt-1 text-xs text-gray-500">
                                                            {card.node.ipAddress} · {card.modelLabel}
                                                        </p>
                                                    </div>
                                                    <span
                                                        className={`rounded-full border px-2.5 py-1 text-xs ${
                                                            card.isOnline
                                                                ? 'border-emerald-400/30 bg-emerald-500/10 text-emerald-300'
                                                                : 'border-gray-500/20 bg-gray-500/10 text-gray-300'
                                                        }`}
                                                    >
                                                        {card.isOnline ? 'ONLINE' : 'OFFLINE'}
                                                    </span>
                                                </div>

                                                <div className="mt-4 flex flex-wrap gap-2 text-xs">
                                                    <span className="rounded-full border border-white/10 bg-white/5 px-3 py-1.5 text-gray-200">
                                                        {card.reportedGpuCount}x {card.node.gpuModel || 'GPU'}
                                                    </span>
                                                    <span className="rounded-full border border-white/10 bg-white/5 px-3 py-1.5 text-gray-200">
                                                        {card.node.gpuTopology || 'Topology unknown'}
                                                    </span>
                                                    <span className="rounded-full border border-white/10 bg-white/5 px-3 py-1.5 text-gray-200">
                                                        {card.node.nvlinkBandwidthGbps
                                                            ? `${card.node.nvlinkBandwidthGbps} GB/s NVLink`
                                                            : 'NVLink n/a'}
                                                    </span>
                                                    <span className="rounded-full border border-white/10 bg-white/5 px-3 py-1.5 text-gray-200">
                                                        IB Fabric {card.fabricLabel}
                                                    </span>
                                                </div>

                                                <div className="mt-5 rounded-2xl border border-white/10 bg-black/25 p-4">
                                                    <div className="mb-3 flex items-center justify-between">
                                                        <p className="text-sm text-gray-300">
                                                            Allocated {card.allocatedGpuCount} / {card.slotCount} GPUs
                                                        </p>
                                                        <p className="text-xs text-cyan-300">
                                                            {card.utilizationPercent}% utilized
                                                        </p>
                                                    </div>

                                                    {card.slotCount === 0 ? (
                                                        <div className="rounded-2xl border border-dashed border-white/10 bg-black/20 px-4 py-6 text-center text-sm text-gray-500">
                                                            No GPU inventory reported for this node yet.
                                                        </div>
                                                    ) : (
                                                        <div className="grid grid-cols-2 gap-2 sm:grid-cols-4">
                                                            {card.slots.map((slot) => (
                                                                <div
                                                                    key={`${card.node.id}-${slot.index}`}
                                                                    data-testid={`gpu-slot-${card.node.id}`}
                                                                    className={`rounded-xl border px-3 py-2 ${
                                                                        slot.palette
                                                                            ? slot.palette.surface
                                                                            : card.isOnline
                                                                              ? 'border-white/10 bg-white/5'
                                                                              : 'border-dashed border-white/10 bg-transparent'
                                                                    }`}
                                                                    title={slot.job ? `${slot.job.name} (${slot.job.id})` : 'Idle GPU slot'}
                                                                >
                                                                    <div className="flex items-center gap-2 text-[11px] font-mono text-white">
                                                                        <Cpu size={12} className="opacity-60" />
                                                                        GPU-{slot.index}
                                                                    </div>
                                                                    <p className="mt-1 truncate text-[10px] text-gray-400">
                                                                        {slot.job ? slot.job.name || slot.job.id : 'Idle'}
                                                                    </p>
                                                                </div>
                                                            ))}
                                                        </div>
                                                    )}

                                                    <div className="mt-4 flex flex-wrap gap-2 text-xs">
                                                        {card.assignedJobs.length === 0 ? (
                                                            <span className="rounded-full border border-white/10 bg-white/5 px-3 py-1.5 text-gray-400">
                                                                {card.freeGpuCount > 0
                                                                    ? `${card.freeGpuCount} GPU slots ready`
                                                                    : 'No active allocations'}
                                                            </span>
                                                        ) : (
                                                            card.assignedJobs.map((job) => {
                                                                const palette = jobPaletteById.get(job.id) ?? JOB_PALETTES[0];
                                                                return (
                                                                    <span
                                                                        key={job.id}
                                                                        className={`rounded-full border px-3 py-1.5 ${palette.surface} ${palette.text}`}
                                                                    >
                                                                        {job.name || job.id} · {job.gpuDemand} GPU
                                                                    </span>
                                                                );
                                                            })
                                                        )}
                                                    </div>
                                                </div>
                                            </motion.div>
                                        ))}
                                    </div>
                                </div>
                            ))}
                        </div>
                    </GlassCard>
                ))
            )}
        </div>
    );
};
