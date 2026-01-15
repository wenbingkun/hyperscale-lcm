import React, { useState } from 'react';
import { GlassCard } from './GlassCard';
import { GradientButton } from './GradientButton';
import { submitJob } from '../api/client';

export const JobSubmissionForm: React.FC = () => {
    const [loading, setLoading] = useState(false);
    const [success, setSuccess] = useState(false);
    const [formData, setFormData] = useState({
        cpuCores: 4,
        memoryGb: 16,
        gpuCount: 1
    });

    const handleSubmit = async (e: React.FormEvent) => {
        e.preventDefault();
        setLoading(true);
        setSuccess(false);

        try {
            await submitJob(formData);
            setSuccess(true);
            setTimeout(() => setSuccess(false), 3000);
        } catch (err) {
            console.error(err);
            alert("Failed to submit job");
        } finally {
            setLoading(false);
        }
    };

    return (
        <GlassCard title="Submit AI Training Job">
            <form onSubmit={handleSubmit} className="space-y-4">
                <div className="grid grid-cols-1 md:grid-cols-3 gap-4">
                    <div>
                        <label className="block text-sm text-gray-400 mb-1">CPU Cores</label>
                        <input
                            type="number"
                            min="1"
                            value={formData.cpuCores}
                            onChange={e => setFormData({ ...formData, cpuCores: parseInt(e.target.value) })}
                            className="w-full bg-black/30 border border-gray-700 rounded p-2 text-white focus:border-cyan-400 focus:outline-none transition-colors"
                        />
                    </div>
                    <div>
                        <label className="block text-sm text-gray-400 mb-1">Memory (GB)</label>
                        <input
                            type="number"
                            min="1"
                            value={formData.memoryGb}
                            onChange={e => setFormData({ ...formData, memoryGb: parseInt(e.target.value) })}
                            className="w-full bg-black/30 border border-gray-700 rounded p-2 text-white focus:border-cyan-400 focus:outline-none transition-colors"
                        />
                    </div>
                    <div>
                        <label className="block text-sm text-gray-400 mb-1">GPUs (A100)</label>
                        <input
                            type="number"
                            min="0"
                            value={formData.gpuCount}
                            onChange={e => setFormData({ ...formData, gpuCount: parseInt(e.target.value) })}
                            className="w-full bg-black/30 border border-gray-700 rounded p-2 text-white focus:border-cyan-400 focus:outline-none transition-colors"
                        />
                    </div>
                </div>

                <div className="flex items-center justify-between mt-6">
                    <div className="text-sm text-gray-500">
                        Target Constraint: <span className="text-cyan-400 font-mono">NVLink-Enabled</span>
                    </div>
                    <GradientButton type="submit" loading={loading}>
                        {success ? '✅ Submitted!' : '🚀 Launch Job'}
                    </GradientButton>
                </div>
            </form>
        </GlassCard>
    );
};
