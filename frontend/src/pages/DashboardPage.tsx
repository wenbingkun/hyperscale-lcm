import React, { useEffect, useState } from 'react';
import { GlassCard } from '../components/GlassCard';
import { SatelliteTable } from '../components/SatelliteTable';
import { JobSubmissionForm } from '../components/JobSubmissionForm';
import { StatCard } from '../components/StatCard';
import { Server, Zap, Network, Activity } from 'lucide-react';
import { AreaChart, Area, XAxis, YAxis, CartesianGrid, Tooltip, ResponsiveContainer } from 'recharts';
import { motion, type Variants } from 'framer-motion';
import { fetchClusterStats, fetchJobStats, type ClusterStats, type JobStats } from '../api/client';

const data = [
    { name: '00:00', nodes: 4000, jobs: 240 },
    { name: '04:00', nodes: 3000, jobs: 139 },
    { name: '08:00', nodes: 9000, jobs: 980 },
    { name: '12:00', nodes: 11000, jobs: 390 },
    { name: '16:00', nodes: 12400, jobs: 480 },
    { name: '20:00', nodes: 10000, jobs: 380 },
    { name: '23:59', nodes: 8500, jobs: 430 },
];

export const DashboardPage: React.FC = () => {
    const [clusterStats, setClusterStats] = useState<ClusterStats | null>(null);
    const [jobStats, setJobStats] = useState<JobStats | null>(null);

    useEffect(() => {
        const loadStats = async () => {
            const [cStats, jStats] = await Promise.all([
                fetchClusterStats(),
                fetchJobStats()
            ]);
            setClusterStats(cStats);
            setJobStats(jStats);
        };

        loadStats();
        const interval = setInterval(loadStats, 5000);
        return () => clearInterval(interval);
    }, []);

    const onlineNodes = clusterStats?.onlineNodes || 0;
    // const totalNodes = clusterStats?.totalNodes || 0; 
    const activeJobs = jobStats?.running || 0;

    const containerVariants: Variants = {
        hidden: { opacity: 0 },
        show: {
            opacity: 1,
            transition: { staggerChildren: 0.1 }
        }
    };

    const itemVariants: Variants = {
        hidden: { opacity: 0, y: 20 },
        show: { opacity: 1, y: 0, transition: { type: "spring", stiffness: 300, damping: 24 } }
    };

    return (
        <motion.div
            className="space-y-8"
            variants={containerVariants}
            initial="hidden"
            animate="show"
        >
            {/* Stats Grid */}
            <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-4 gap-6">
                <StatCard
                    title="Total Nodes"
                    value={onlineNodes.toLocaleString()}
                    trend="up"
                    subtext={`${onlineNodes} Online`}
                    icon={<Server size={20} />}
                    accentColor="cyan"
                    variants={itemVariants}
                />
                <StatCard
                    title="GPU Capacity"
                    value="85"
                    unit="PF"
                    trend="up"
                    subtext="98,000 A100s"
                    icon={<Zap size={20} />}
                    accentColor="purple"
                    variants={itemVariants}
                />
                <StatCard
                    title="Active Jobs"
                    value={activeJobs.toLocaleString()}
                    trend="neutral"
                    subtext="85% Utilization"
                    icon={<Activity size={20} />}
                    accentColor="yellow"
                    variants={itemVariants}
                />
                <StatCard
                    title="Network"
                    value="4.2"
                    unit="Tbps"
                    trend="up"
                    subtext="Infiniband Fabric"
                    icon={<Network size={20} />}
                    accentColor="green"
                    variants={itemVariants}
                />
            </div>

            {/* Main Content Area */}
            <div className="grid grid-cols-1 lg:grid-cols-3 gap-8">
                <div className="lg:col-span-2 space-y-8">
                    <GlassCard title="System Load" className="min-h-[400px]" variants={itemVariants}>
                        <div className="h-[300px] w-full">
                            <ResponsiveContainer width="100%" height="100%">
                                <AreaChart data={data}>
                                    <defs>
                                        <linearGradient id="colorNodes" x1="0" y1="0" x2="0" y2="1">
                                            <stop offset="5%" stopColor="#00f2ff" stopOpacity={0.3} />
                                            <stop offset="95%" stopColor="#00f2ff" stopOpacity={0} />
                                        </linearGradient>
                                        <linearGradient id="colorJobs" x1="0" y1="0" x2="0" y2="1">
                                            <stop offset="5%" stopColor="#7000ff" stopOpacity={0.3} />
                                            <stop offset="95%" stopColor="#7000ff" stopOpacity={0} />
                                        </linearGradient>
                                    </defs>
                                    <CartesianGrid strokeDasharray="3 3" stroke="rgba(255,255,255,0.05)" vertical={false} />
                                    <XAxis dataKey="name" stroke="#64748b" tick={{ fill: '#64748b' }} axisLine={false} tickLine={false} />
                                    <YAxis stroke="#64748b" tick={{ fill: '#64748b' }} axisLine={false} tickLine={false} />
                                    <Tooltip
                                        contentStyle={{ backgroundColor: '#0f1014', borderColor: 'rgba(255,255,255,0.1)', color: '#fff' }}
                                        itemStyle={{ color: '#fff' }}
                                    />
                                    <Area type="monotone" dataKey="nodes" stroke="#00f2ff" strokeWidth={2} fillOpacity={1} fill="url(#colorNodes)" />
                                    <Area type="monotone" dataKey="jobs" stroke="#7000ff" strokeWidth={2} fillOpacity={1} fill="url(#colorJobs)" />
                                </AreaChart>
                            </ResponsiveContainer>
                        </div>
                    </GlassCard>
                    <motion.div variants={itemVariants}>
                        <SatelliteTable />
                    </motion.div>
                </div>
                <motion.div variants={itemVariants}>
                    <JobSubmissionForm />
                </motion.div>
            </div>
        </motion.div>
    );
};
