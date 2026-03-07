import React, { useEffect, useState } from 'react';
import { useParams, Link } from 'react-router-dom';
import { GlassCard } from '../components/GlassCard';
import { ArrowLeft, Clock, Server, CheckCircle, XCircle, Loader2, AlertCircle } from 'lucide-react';
import { type Job, fetchJobs } from '../api/client';
import { useWebSocketContext } from '../contexts/WebSocketContext';

const statusConfig: Record<string, { color: string; icon: React.ReactNode; label: string }> = {
    PENDING: { color: 'text-yellow-400', icon: <Clock size={20} />, label: 'Pending' },
    SCHEDULED: { color: 'text-blue-400', icon: <Server size={20} />, label: 'Scheduled' },
    RUNNING: { color: 'text-cyan-400', icon: <Loader2 size={20} className="animate-spin" />, label: 'Running' },
    COMPLETED: { color: 'text-green-400', icon: <CheckCircle size={20} />, label: 'Completed' },
    FAILED: { color: 'text-red-400', icon: <XCircle size={20} />, label: 'Failed' },
    CANCELLED: { color: 'text-gray-400', icon: <AlertCircle size={20} />, label: 'Cancelled' },
};

export const JobDetailPage: React.FC = () => {
    const { id } = useParams<{ id: string }>();
    const [job, setJob] = useState<Job | null>(null);
    const [loading, setLoading] = useState(true);
    const { lastEvent } = useWebSocketContext();
    const [initTimestamp] = useState(() => new Date(Date.now() - 3600000).toISOString());

    useEffect(() => {
        const loadJob = async () => {
            const jobs = await fetchJobs();
            const found = jobs.find((j) => j.id === id);
            setJob(found || null);
            setLoading(false);
        };
        loadJob();
    }, [id]);

    // Real-time updates
    useEffect(() => {
        if (lastEvent?.type === 'SCHEDULE_EVENT' && lastEvent.payload?.jobId === id) {
            // Refresh job data when we get an update for this job
            fetchJobs().then((jobs) => {
                const found = jobs.find((j) => j.id === id);
                if (found) setJob(found);
            });
        }
    }, [lastEvent, id]);

    if (loading) {
        return (
            <div className="flex items-center justify-center h-64">
                <Loader2 className="animate-spin text-cyan-400" size={48} />
            </div>
        );
    }

    if (!job) {
        return (
            <div className="text-center py-12">
                <h2 className="text-2xl font-bold text-white mb-4">Job Not Found</h2>
                <Link to="/jobs" className="text-cyan-400 hover:text-cyan-300">
                    ← Back to Jobs
                </Link>
            </div>
        );
    }

    const status = statusConfig[job.status] || statusConfig.PENDING;

    return (
        <div className="space-y-6">
            {/* Header */}
            <div className="flex items-center gap-4">
                <Link
                    to="/jobs"
                    className="p-2 rounded-lg bg-white/5 hover:bg-white/10 transition-colors"
                >
                    <ArrowLeft size={20} />
                </Link>
                <div>
                    <h1 className="text-2xl font-bold text-white">{job.name}</h1>
                    <p className="text-gray-400 text-sm">ID: {job.id}</p>
                </div>
            </div>

            <div className="grid grid-cols-1 lg:grid-cols-2 gap-6">
                {/* Status Card */}
                <GlassCard title="Job Status">
                    <div className="space-y-4">
                        <div className={`flex items-center gap-3 ${status.color}`}>
                            {status.icon}
                            <span className="text-xl font-semibold">{status.label}</span>
                        </div>

                        <div className="grid grid-cols-2 gap-4 pt-4 border-t border-white/10">
                            <div>
                                <p className="text-gray-400 text-sm">Assigned Node</p>
                                <p className="text-white font-medium">{job.assignedNodeId || '—'}</p>
                            </div>
                            <div>
                                <p className="text-gray-400 text-sm">Exit Code</p>
                                <p className="text-white font-medium">{job.exitCode ?? '—'}</p>
                            </div>
                            <div>
                                <p className="text-gray-400 text-sm">Scheduled At</p>
                                <p className="text-white font-medium">
                                    {job.scheduledAt ? new Date(job.scheduledAt).toLocaleString() : '—'}
                                </p>
                            </div>
                            <div>
                                <p className="text-gray-400 text-sm">Completed At</p>
                                <p className="text-white font-medium">
                                    {job.completedAt ? new Date(job.completedAt).toLocaleString() : '—'}
                                </p>
                            </div>
                        </div>
                    </div>
                </GlassCard>

                {/* Description Card */}
                <GlassCard title="Description">
                    <p className="text-gray-300">
                        {job.description || 'No description provided.'}
                    </p>
                </GlassCard>

                {/* Execution Log */}
                <GlassCard title="Execution Log" className="lg:col-span-2">
                    <div className="bg-[#050507] rounded-lg p-4 font-mono text-xs sm:text-sm text-gray-300 h-72 overflow-auto border border-white/10 shadow-[inset_0_2px_15px_rgba(0,0,0,0.5)]">
                        {/* Fake Mac Buttons */}
                        <div className="flex gap-2 mb-4 items-center">
                            <div className="w-3 h-3 rounded-full bg-red-500/80 border border-red-500/50"></div>
                            <div className="w-3 h-3 rounded-full bg-yellow-500/80 border border-yellow-500/50"></div>
                            <div className="w-3 h-3 rounded-full bg-green-500/80 border border-green-500/50"></div>
                            <span className="ml-2 text-gray-600 select-none text-xs flex-1 text-center pr-8">bash - hyperscale-job-{job.id.substring(0, 4)}</span>
                        </div>
                        <div className="space-y-1 relative">
                            <p className="text-gray-500 mb-2"># Live execution stream</p>
                            <p className="text-green-400">[{initTimestamp}] [SYSTEM] Job initialized.</p>
                            {job.scheduledAt && (
                                <p className="text-blue-400">[{job.scheduledAt}] [SCHEDULER] Assigned to compute node: <span className="text-cyan-300">{job.assignedNodeId}</span></p>
                            )}
                            {job.status === 'RUNNING' && (
                                <p className="text-cyan-400">[{new Date().toISOString()}] [CONTAINER] Main process executing... <span className="inline-block w-2 h-4 bg-cyan-400 animate-pulse ml-1 align-middle"></span></p>
                            )}
                            {job.completedAt && (
                                <p className={job.status === 'COMPLETED' ? 'text-green-400' : 'text-red-400'}>
                                    [{job.completedAt}] [SYSTEM] Process exited with code: {job.exitCode}
                                </p>
                            )}
                            {(job.status === 'COMPLETED' || job.status === 'FAILED') && (
                                <p className="text-gray-500 mt-2">End of log.</p>
                            )}
                        </div>
                    </div>
                </GlassCard>
            </div>
        </div>
    );
};
