import React, { useState } from 'react';
import { useAuth } from '../contexts/AuthContext';
import { Activity, Lock, User, Building2, AlertCircle, ArrowRight } from 'lucide-react';

export const LoginPage: React.FC = () => {
    const { login, isLoading, error } = useAuth();
    const [username, setUsername] = useState('');
    const [password, setPassword] = useState('');
    const [tenantId, setTenantId] = useState('default');
    const [localError, setLocalError] = useState<string | null>(null);

    const handleSubmit = async (e: React.FormEvent) => {
        e.preventDefault();
        setLocalError(null);
        if (!username || !password) {
            setLocalError('Please enter username and password');
            return;
        }
        try {
            await login(username, password, tenantId);
        } catch {
            // error is set in AuthContext
        }
    };

    const displayError = localError || error;

    return (
        <div className="min-h-screen flex items-center justify-center bg-[#050507] relative overflow-hidden">
            {/* Background effects */}
            <div className="absolute inset-0">
                <div className="absolute top-1/4 left-1/4 w-96 h-96 bg-cyan-500/5 rounded-full blur-3xl animate-pulse" />
                <div className="absolute bottom-1/4 right-1/4 w-96 h-96 bg-purple-500/5 rounded-full blur-3xl animate-pulse" style={{ animationDelay: '1s' }} />
                <div className="absolute inset-0 bg-[url('/grid-pattern.svg')] opacity-30" />
            </div>

            <div className="relative z-10 w-full max-w-md px-6">
                {/* Logo */}
                <div className="text-center mb-8">
                    <div className="inline-flex items-center justify-center w-16 h-16 rounded-2xl bg-gradient-to-br from-cyan-400 to-blue-600 shadow-[0_0_40px_rgba(0,242,255,0.3)] mb-4">
                        <Activity size={32} className="text-white" strokeWidth={2.5} />
                    </div>
                    <h1 className="text-3xl font-bold tracking-tight">
                        <span className="text-white">Hyperscale</span>
                        <span className="text-cyan-400">LCM</span>
                    </h1>
                    <p className="text-gray-500 mt-2 text-sm">万级节点生命周期管理平台</p>
                </div>

                {/* Login Card */}
                <form onSubmit={handleSubmit}
                    className="glass-panel rounded-2xl p-8 space-y-6"
                    style={{ background: 'rgba(15, 16, 20, 0.8)', backdropFilter: 'blur(24px)', border: '1px solid rgba(255,255,255,0.08)' }}
                >
                    <div className="text-center mb-2">
                        <h2 className="text-lg font-semibold text-white">Sign In</h2>
                    </div>

                    {/* Error */}
                    {displayError && (
                        <div className="flex items-center gap-2 px-4 py-3 rounded-lg bg-red-500/10 border border-red-500/20 text-red-400 text-sm">
                            <AlertCircle size={16} />
                            <span>{displayError}</span>
                        </div>
                    )}

                    {/* Username */}
                    <div className="space-y-2">
                        <label className="text-xs font-medium text-gray-400 uppercase tracking-wider">Username</label>
                        <div className="relative">
                            <User size={18} className="absolute left-3 top-1/2 -translate-y-1/2 text-gray-500" />
                            <input
                                id="login-username"
                                type="text"
                                value={username}
                                onChange={e => setUsername(e.target.value)}
                                placeholder="admin"
                                autoComplete="username"
                                className="w-full pl-10 pr-4 py-3 rounded-lg bg-white/5 border border-white/10 text-white placeholder-gray-600 focus:outline-none focus:border-cyan-400/50 focus:ring-1 focus:ring-cyan-400/20 transition-all"
                            />
                        </div>
                    </div>

                    {/* Password */}
                    <div className="space-y-2">
                        <label className="text-xs font-medium text-gray-400 uppercase tracking-wider">Password</label>
                        <div className="relative">
                            <Lock size={18} className="absolute left-3 top-1/2 -translate-y-1/2 text-gray-500" />
                            <input
                                id="login-password"
                                type="password"
                                value={password}
                                onChange={e => setPassword(e.target.value)}
                                placeholder="••••••••"
                                autoComplete="current-password"
                                className="w-full pl-10 pr-4 py-3 rounded-lg bg-white/5 border border-white/10 text-white placeholder-gray-600 focus:outline-none focus:border-cyan-400/50 focus:ring-1 focus:ring-cyan-400/20 transition-all"
                            />
                        </div>
                    </div>

                    {/* Tenant */}
                    <div className="space-y-2">
                        <label className="text-xs font-medium text-gray-400 uppercase tracking-wider">Tenant</label>
                        <div className="relative">
                            <Building2 size={18} className="absolute left-3 top-1/2 -translate-y-1/2 text-gray-500" />
                            <input
                                id="login-tenant"
                                type="text"
                                value={tenantId}
                                onChange={e => setTenantId(e.target.value)}
                                placeholder="default"
                                className="w-full pl-10 pr-4 py-3 rounded-lg bg-white/5 border border-white/10 text-white placeholder-gray-600 focus:outline-none focus:border-cyan-400/50 focus:ring-1 focus:ring-cyan-400/20 transition-all"
                            />
                        </div>
                    </div>

                    {/* Submit */}
                    <button
                        id="login-submit"
                        type="submit"
                        disabled={isLoading}
                        className="w-full flex items-center justify-center gap-2 py-3 rounded-lg font-semibold text-sm transition-all duration-300
                            bg-gradient-to-r from-cyan-500 to-blue-600 text-white
                            hover:from-cyan-400 hover:to-blue-500 hover:shadow-[0_0_30px_rgba(0,242,255,0.3)]
                            disabled:opacity-50 disabled:cursor-not-allowed"
                    >
                        {isLoading ? (
                            <Activity size={18} className="animate-spin" />
                        ) : (
                            <>Sign In <ArrowRight size={16} /></>
                        )}
                    </button>

                    {/* Hint */}
                    <p className="text-center text-xs text-gray-600 mt-4">
                        Default: admin / admin123
                    </p>
                </form>
            </div>
        </div>
    );
};
