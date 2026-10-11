import React, { useEffect, useState } from 'react';
import { GlassCard } from './GlassCard';
import { GradientButton } from './GradientButton';
import { type DiscoveredDevice, fetchDiscoveredDevices, submitJob } from '../api/client';

const SSH_TASKS = [{ value: 'SYSTEM_INFO', label: 'System info (uname / uptime / df / free)' }];
const INPUT_CLASS = 'w-full bg-black/30 border border-glass-border rounded p-2 text-white focus:border-cyan-400 focus:outline-none transition-colors';

export const JobSubmissionForm: React.FC = () => {
    const [loading, setLoading] = useState(false);
    const [success, setSuccess] = useState(false);
    const [mode, setMode] = useState<'training' | 'ssh'>('training');
    const [devices, setDevices] = useState<DiscoveredDevice[]>([]);
    const [targetDeviceId, setTargetDeviceId] = useState('');
    const [task, setTask] = useState(SSH_TASKS[0].value);
    const [formData, setFormData] = useState({
        name: '',
        cpuCores: 4,
        memoryGb: 16,
        gpuCount: 1
    });

    useEffect(() => {
        if (mode !== 'ssh') return;
        fetchDiscoveredDevices()
            .then(all => setDevices(all.filter(d => d.status === 'APPROVED' || d.status === 'MANAGED')))
            .catch(err => console.error(err));
    }, [mode]);

    const handleSubmit = async (e: React.FormEvent) => {
        e.preventDefault();
        setLoading(true);
        setSuccess(false);

        try {
            const name = formData.name || `Job-${Date.now()}`; // Default name if empty
            await submitJob(mode === 'ssh'
                ? { name, cpuCores: 1, memoryGb: 1, gpuCount: 0, clusterId: 'default', executionType: 'SSH', targetDeviceId, task }
                : { ...formData, name });
            setSuccess(true);
            setTimeout(() => setSuccess(false), 3000);
            // Reset form but keep defaults
            setFormData(prev => ({ ...prev, name: '' }));
        } catch (err) {
            console.error(err);
            alert("Failed to submit job");
        } finally {
            setLoading(false);
        }
    };

    return (
        <GlassCard title={mode === 'ssh' ? 'Run SSH Maintenance Task' : 'Submit AI Training Job'}>
            <form onSubmit={handleSubmit} className="space-y-4">
                <div>
                    <label htmlFor="job-mode" className="block text-sm text-gray-400 mb-1">Job Type</label>
                    <select id="job-mode" value={mode} onChange={e => setMode(e.target.value as 'training' | 'ssh')} className={INPUT_CLASS}>
                        <option value="training">AI Training</option>
                        <option value="ssh">SSH Maintenance (read-only)</option>
                    </select>
                </div>
                <div>
                    <label className="block text-sm text-gray-400 mb-1">Job Name</label>
                    <input
                        type="text"
                        placeholder="e.g. LLM-Training-v1"
                        value={formData.name}
                        onChange={e => setFormData({ ...formData, name: e.target.value })}
                        className="w-full bg-black/30 border border-glass-border rounded p-2 text-white focus:border-cyan-400 focus:outline-none transition-colors placeholder:text-gray-600"
                    />
                </div>
                {mode === 'ssh' ? (
                    <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
                        <div>
                            <label htmlFor="ssh-target" className="block text-sm text-gray-400 mb-1">Target Device (approved)</label>
                            <select id="ssh-target" required value={targetDeviceId} onChange={e => setTargetDeviceId(e.target.value)} className={INPUT_CLASS}>
                                <option value="">Select a device…</option>
                                {devices.map(d => (
                                    <option key={d.id} value={d.id}>{d.hostname || d.ipAddress} ({d.ipAddress})</option>
                                ))}
                            </select>
                        </div>
                        <div>
                            <label htmlFor="ssh-task" className="block text-sm text-gray-400 mb-1">Task</label>
                            <select id="ssh-task" value={task} onChange={e => setTask(e.target.value)} className={INPUT_CLASS}>
                                {SSH_TASKS.map(t => <option key={t.value} value={t.value}>{t.label}</option>)}
                            </select>
                        </div>
                    </div>
                ) : (
                <div className="grid grid-cols-1 md:grid-cols-3 gap-4">
                    <div>
                        <label className="block text-sm text-gray-400 mb-1">CPU Cores</label>
                        <input
                            type="number"
                            min="1"
                            value={formData.cpuCores}
                            onChange={e => setFormData({ ...formData, cpuCores: parseInt(e.target.value) || 1 })}
                            className="w-full bg-black/30 border border-glass-border rounded p-2 text-white focus:border-cyan-400 focus:outline-none transition-colors"
                        />
                    </div>
                    <div>
                        <label className="block text-sm text-gray-400 mb-1">Memory (GB)</label>
                        <input
                            type="number"
                            min="1"
                            value={formData.memoryGb}
                            onChange={e => setFormData({ ...formData, memoryGb: parseInt(e.target.value) || 1 })}
                            className="w-full bg-black/30 border border-glass-border rounded p-2 text-white focus:border-cyan-400 focus:outline-none transition-colors"
                        />
                    </div>
                    <div>
                        <label className="block text-sm text-gray-400 mb-1">GPUs (A100)</label>
                        <input
                            type="number"
                            min="0"
                            value={formData.gpuCount}
                            onChange={e => setFormData({ ...formData, gpuCount: parseInt(e.target.value) || 0 })}
                            className="w-full bg-black/30 border border-glass-border rounded p-2 text-white focus:border-cyan-400 focus:outline-none transition-colors"
                        />
                    </div>
                </div>
                )}

                <div className="flex items-center justify-between mt-6">
                    <div className="text-sm text-gray-500">
                        {mode === 'ssh'
                            ? <>Command: <span className="text-cyan-400 font-mono">fixed, read-only</span></>
                            : <>Target Constraint: <span className="text-cyan-400 font-mono">NVLink-Enabled</span></>}
                    </div>
                    <GradientButton type="submit" loading={loading}>
                        {success ? '✅ Submitted!' : '🚀 Launch Job'}
                    </GradientButton>
                </div>
            </form>
        </GlassCard>
    );
};
