import React, { useEffect, useState } from 'react';
import { GlassCard } from '../components/GlassCard';
import { Building2, Cpu, HardDrive, Zap, Users, Plus, Settings } from 'lucide-react';

interface Tenant {
    id: string;
    name: string;
    description: string;
    status: string;
    cpuQuota: number;
    memoryQuotaGb: number;
    gpuQuota: number;
    maxConcurrentJobs: number;
    cpuUsed: number;
    memoryUsedGb: number;
    gpuUsed: number;
    runningJobs: number;
}

const API_BASE = import.meta.env.VITE_API_BASE || 'http://localhost:8080';
const TOKEN_KEY = 'lcm_auth_token';

export const TenantsPage: React.FC = () => {
    const [tenants, setTenants] = useState<Tenant[]>([]);

    useEffect(() => {
        const doLoadTenants = async () => {
            try {
                const token = localStorage.getItem(TOKEN_KEY);
                const response = await fetch(`${API_BASE}/api/tenants`, {
                    headers: { 'Authorization': `Bearer ${token}` }
                });
                if (response.ok) {
                    const data = await response.json();
                    setTenants(data);
                }
            } catch (error) {
                console.error('Failed to load tenants:', error);
            }
        };
        doLoadTenants();
    }, []);

    const getUtilizationColor = (used: number, quota: number) => {
        const pct = quota > 0 ? (used / quota) * 100 : 0;
        if (pct >= 90) return 'bg-red-500';
        if (pct >= 70) return 'bg-yellow-500';
        return 'bg-cyan-500';
    };

    const ProgressBar: React.FC<{ used: number; quota: number; label: string }> = ({ used, quota, label }) => {
        const pct = quota > 0 ? Math.min((used / quota) * 100, 100) : 0;
        return (
            <div className="space-y-1">
                <div className="flex justify-between text-xs">
                    <span className="text-gray-400">{label}</span>
                    <span className="text-gray-300">{used.toLocaleString()} / {quota.toLocaleString()}</span>
                </div>
                <div className="h-2 bg-white/10 rounded-full overflow-hidden">
                    <div
                        className={`h-full ${getUtilizationColor(used, quota)} transition-all`}
                        style={{ width: `${pct}%` }}
                    />
                </div>
            </div>
        );
    };

    return (
        <div className="space-y-6">
            <header className="flex items-center justify-between">
                <div>
                    <h2 className="text-2xl font-bold text-white">Tenant Management</h2>
                    <p className="text-gray-400 mt-1">Manage resource quotas and monitor usage</p>
                </div>
                <button className="flex items-center gap-2 px-4 py-2 rounded-lg bg-cyan-500/20 border border-cyan-500/30 text-cyan-400 hover:bg-cyan-500/30 transition-colors">
                    <Plus size={18} />
                    New Tenant
                </button>
            </header>

            <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-6">
                {tenants.length === 0 ? (
                    <GlassCard className="col-span-full py-12 text-center">
                        <Building2 size={48} className="mx-auto mb-4 text-gray-500 opacity-30" />
                        <p className="text-gray-500">No tenants configured</p>
                    </GlassCard>
                ) : (
                    tenants.map((tenant) => (
                        <GlassCard key={tenant.id} className="relative group">
                            <button
                                className="absolute top-4 right-4 p-1.5 rounded bg-white/5 opacity-0 group-hover:opacity-100 transition-opacity"
                                title="Settings (coming soon)"
                            >
                                <Settings size={14} className="text-gray-400" />
                            </button>

                            <div className="flex items-center gap-3 mb-4">
                                <div className={`p-2 rounded-lg ${tenant.status === 'ACTIVE' ? 'bg-green-500/20' : 'bg-red-500/20'}`}>
                                    <Building2
                                        size={20}
                                        className={tenant.status === 'ACTIVE' ? 'text-green-400' : 'text-red-400'}
                                    />
                                </div>
                                <div>
                                    <h3 className="font-semibold text-white">{tenant.name}</h3>
                                    <p className="text-xs text-gray-500">{tenant.id}</p>
                                </div>
                            </div>

                            <div className="space-y-3">
                                <ProgressBar
                                    used={tenant.cpuUsed}
                                    quota={tenant.cpuQuota}
                                    label="CPU Cores"
                                />
                                <ProgressBar
                                    used={tenant.gpuUsed}
                                    quota={tenant.gpuQuota}
                                    label="GPUs"
                                />
                                <ProgressBar
                                    used={tenant.memoryUsedGb}
                                    quota={tenant.memoryQuotaGb}
                                    label="Memory (GB)"
                                />
                            </div>

                            <div className="mt-4 pt-4 border-t border-white/10 flex justify-between text-sm">
                                <div className="flex items-center gap-1 text-gray-400">
                                    <Users size={14} />
                                    {tenant.runningJobs} / {tenant.maxConcurrentJobs} jobs
                                </div>
                                <span className={`px-2 py-0.5 rounded text-xs ${tenant.status === 'ACTIVE'
                                    ? 'bg-green-500/20 text-green-400'
                                    : 'bg-red-500/20 text-red-400'
                                    }`}>
                                    {tenant.status}
                                </span>
                            </div>
                        </GlassCard>
                    ))
                )}
            </div>

            {/* Summary Stats */}
            <div className="grid grid-cols-1 md:grid-cols-4 gap-4">
                <GlassCard className="!p-4">
                    <div className="flex items-center gap-3">
                        <div className="p-2 rounded-lg bg-cyan-500/20">
                            <Building2 size={20} className="text-cyan-400" />
                        </div>
                        <div>
                            <p className="text-2xl font-bold text-white">{tenants.length}</p>
                            <p className="text-xs text-gray-400">Total Tenants</p>
                        </div>
                    </div>
                </GlassCard>
                <GlassCard className="!p-4">
                    <div className="flex items-center gap-3">
                        <div className="p-2 rounded-lg bg-green-500/20">
                            <Cpu size={20} className="text-green-400" />
                        </div>
                        <div>
                            <p className="text-2xl font-bold text-white">
                                {tenants.reduce((sum, t) => sum + t.cpuUsed, 0).toLocaleString()}
                            </p>
                            <p className="text-xs text-gray-400">CPU Cores Used</p>
                        </div>
                    </div>
                </GlassCard>
                <GlassCard className="!p-4">
                    <div className="flex items-center gap-3">
                        <div className="p-2 rounded-lg bg-purple-500/20">
                            <Zap size={20} className="text-purple-400" />
                        </div>
                        <div>
                            <p className="text-2xl font-bold text-white">
                                {tenants.reduce((sum, t) => sum + t.gpuUsed, 0).toLocaleString()}
                            </p>
                            <p className="text-xs text-gray-400">GPUs Allocated</p>
                        </div>
                    </div>
                </GlassCard>
                <GlassCard className="!p-4">
                    <div className="flex items-center gap-3">
                        <div className="p-2 rounded-lg bg-blue-500/20">
                            <HardDrive size={20} className="text-blue-400" />
                        </div>
                        <div>
                            <p className="text-2xl font-bold text-white">
                                {(tenants.reduce((sum, t) => sum + t.memoryUsedGb, 0) / 1024).toFixed(1)} TB
                            </p>
                            <p className="text-xs text-gray-400">Memory Used</p>
                        </div>
                    </div>
                </GlassCard>
            </div>
        </div>
    );
};
