import React, { useCallback, useEffect, useRef, useState } from 'react';
import { Link } from 'react-router-dom';
import { GlassCard } from '../components/GlassCard';
import { fetchJobs, type Job } from '../api/client';
import { RefreshCcw, Search, ExternalLink } from 'lucide-react';
import { useWebSocketContext } from '../contexts/WebSocketContext';

export const JobsPage: React.FC = () => {
    const [jobs, setJobs] = useState<Job[]>([]);
    const [loading, setLoading] = useState(true);
    const { lastEvent } = useWebSocketContext();

    const refreshJobsRef = useRef<() => Promise<void>>(undefined);
    refreshJobsRef.current = async () => {
        setLoading(true);
        const data = await fetchJobs();
        setJobs(data);
        setLoading(false);
    };

    const loadJobs = useCallback(() => { void refreshJobsRef.current?.(); }, []);

    useEffect(() => {
        void loadJobs();
        const interval = setInterval(() => {
            void loadJobs();
        }, 5000);
        return () => {
            clearInterval(interval);
        };
    }, [loadJobs]);

    useEffect(() => {
        if (lastEvent?.type === 'SCHEDULE_EVENT' || lastEvent?.type === 'JOB_STATUS') {
            void loadJobs();
        }
    }, [lastEvent, loadJobs]);

    const getStatusColor = (status: string) => {
        switch (status) {
            case 'RUNNING': return 'text-cyan-400 bg-cyan-400/10 border-cyan-400/20';
            case 'COMPLETED': return 'text-green-400 bg-green-400/10 border-green-400/20';
            case 'FAILED': return 'text-red-400 bg-red-400/10 border-red-400/20';
            case 'SCHEDULED': return 'text-purple-400 bg-purple-400/10 border-purple-400/20';
            default: return 'text-gray-400 bg-gray-400/10 border-gray-400/20';
        }
    };

    return (
        <div className="space-y-6">
            <header className="flex flex-col md:flex-row md:items-center justify-between gap-4">
                <div>
                    <h2 className="text-2xl font-bold text-white">Job Queues</h2>
                    <p className="text-gray-400 mt-1">Monitor and manage AI training workloads</p>
                </div>
                <div className="flex gap-2">
                    <button
                        onClick={() => void loadJobs()}
                        className="p-2 rounded-lg bg-white/5 border border-white/10 hover:bg-white/10 text-white transition-colors"
                    >
                        <RefreshCcw size={18} className={loading ? 'animate-spin' : ''} />
                    </button>
                    <div className="relative">
                        <Search size={16} className="absolute left-3 top-1/2 -translate-y-1/2 text-gray-500" />
                        <input
                            type="text"
                            placeholder="Search jobs..."
                            className="bg-black/20 border border-white/10 rounded-lg pl-9 pr-4 py-2 text-sm text-white focus:outline-none focus:border-cyan-400/50 w-64"
                        />
                    </div>
                </div>
            </header>

            <GlassCard className="min-h-[500px]">
                <div className="overflow-x-auto">
                    <table className="w-full text-left border-collapse">
                        <thead>
                            <tr className="text-sm text-gray-500 border-b border-white/5">
                                <th className="py-4 px-4 font-medium pl-6">Job ID / Name</th>
                                <th className="py-4 px-4 font-medium">Status</th>
                                <th className="py-4 px-4 font-medium">Assigned Node</th>
                                <th className="py-4 px-4 font-medium">Schedule Time</th>
                                <th className="py-4 px-4 font-medium">Duration</th>
                            </tr>
                        </thead>
                        <tbody>
                            {jobs.length === 0 ? (
                                <tr>
                                    <td colSpan={5} className="py-12 text-center text-gray-500">
                                        No active jobs found in queue.
                                    </td>
                                </tr>
                            ) : (
                                jobs.map((job) => (
                                    <tr key={job.id} className="border-b border-white/5 hover:bg-white/5 transition-colors group">
                                        <td className="py-4 px-4 pl-6">
                                            <Link to={`/jobs/${job.id}`} className="block">
                                                <div className="font-medium text-white group-hover:text-cyan-400 transition-colors flex items-center gap-2">
                                                    {job.name || 'Untitled Job'}
                                                    <ExternalLink size={12} className="opacity-0 group-hover:opacity-100 transition-opacity" />
                                                </div>
                                                <div className="text-xs text-gray-500 font-mono mt-1">{job.id.substring(0, 8)}...</div>
                                            </Link>
                                        </td>
                                        <td className="py-4 px-4">
                                            <span className={`px-2.5 py-0.5 rounded-full text-xs font-medium border ${getStatusColor(job.status)}`}>
                                                {job.status}
                                            </span>
                                        </td>
                                        <td className="py-4 px-4 text-sm text-gray-300 font-mono">
                                            {job.assignedNodeId ? (
                                                <Link to={`/satellites/${job.assignedNodeId}`} className="hover:text-cyan-400">
                                                    {job.assignedNodeId.substring(0, 12)}
                                                </Link>
                                            ) : '-'}
                                        </td>
                                        <td className="py-4 px-4 text-sm text-gray-400">
                                            {job.scheduledAt ? new Date(job.scheduledAt).toLocaleString() : '-'}
                                        </td>
                                        <td className="py-4 px-4 text-sm text-gray-500">
                                            -
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
