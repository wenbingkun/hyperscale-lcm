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
        if (lastEvent?.type === 'SCHEDULE_EVENT' && lastEvent.jobId === id) {
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
                    <div className="bg-black/40 rounded-lg p-4 font-mono text-sm text-gray-300 h-64 overflow-auto">
                        <p className="text-gray-500"># Job execution log</p>
                        <p className="text-green-400">[{new Date().toISOString()}] Job created</p>
                        {job.scheduledAt && (
                            <p className="text-blue-400">[{job.scheduledAt}] Scheduled to node: {job.assignedNodeId}</p>
                        )}
                        {job.status === 'RUNNING' && (
                            <p className="text-cyan-400">[...] Executing...</p>
                        )}
                        {job.completedAt && (
                            <p className={job.status === 'COMPLETED' ? 'text-green-400' : 'text-red-400'}>
                                [{job.completedAt}] Job {job.status.toLowerCase()} with exit code: {job.exitCode}
                            </p>
                        )}
                    </div>
                </GlassCard>
            </div>
        </div>
    );
};
