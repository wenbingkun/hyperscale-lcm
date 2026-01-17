import React from 'react';
import { NavLink } from 'react-router-dom';
import { LayoutDashboard, Satellite, Cpu, Settings, Activity } from 'lucide-react';

interface LayoutProps {
    children: React.ReactNode;
}

export const DashboardLayout: React.FC<LayoutProps> = ({ children }) => {
    return (
        <div className="min-h-screen flex flex-col bg-[url('/grid-pattern.svg')] bg-fixed">
            {/* Header */}
            <header className="h-16 border-b border-glass-border flex items-center px-6 lg:px-8 bg-black/40 backdrop-blur-xl sticky top-0 z-50">
                <div className="flex items-center gap-3 mr-12">
                    <div className="w-8 h-8 rounded-lg bg-gradient-to-br from-cyan-400 to-blue-600 flex items-center justify-center text-white shadow-[0_0_15px_rgba(0,242,255,0.3)]">
                        <Activity size={20} strokeWidth={2.5} />
                    </div>
                    <h1 className="text-xl font-bold tracking-tight select-none">
                        <span className="text-white">Hyperscale</span>
                        <span className="text-cyan-400">LCM</span>
                    </h1>
                </div>

                <nav className="hidden md:flex items-center gap-1">
                    <NavLink
                        to="/"
                        className={({ isActive }) => `
                            flex items-center gap-2 px-4 py-2 rounded-lg text-sm font-medium transition-all duration-200
                            ${isActive
                                ? 'bg-white/10 text-white shadow-[0_0_10px_rgba(255,255,255,0.05)] border border-white/5'
                                : 'text-gray-400 hover:text-cyan-400 hover:bg-white/5'}
                        `}
                    >
                        <LayoutDashboard size={18} />
                        Overview
                    </NavLink>
                    <NavLink
                        to="/satellites"
                        className={({ isActive }) => `
                            flex items-center gap-2 px-4 py-2 rounded-lg text-sm font-medium transition-all duration-200
                            ${isActive
                                ? 'bg-white/10 text-white shadow-[0_0_10px_rgba(255,255,255,0.05)] border border-white/5'
                                : 'text-gray-400 hover:text-cyan-400 hover:bg-white/5'}
                        `}
                    >
                        <Satellite size={18} />
                        Satellites
                    </NavLink>
                    <NavLink
                        to="/jobs"
                        className={({ isActive }) => `
                            flex items-center gap-2 px-4 py-2 rounded-lg text-sm font-medium transition-all duration-200
                            ${isActive
                                ? 'bg-white/10 text-white shadow-[0_0_10px_rgba(255,255,255,0.05)] border border-white/5'
                                : 'text-gray-400 hover:text-cyan-400 hover:bg-white/5'}
                        `}
                    >
                        <Cpu size={18} />
                        Jobs
                    </NavLink>
                </nav>

                <div className="ml-auto flex items-center gap-4">
                    <button className="p-2 rounded-lg text-gray-400 hover:text-white hover:bg-white/10 transition-colors">
                        <Settings size={20} />
                    </button>
                    <div className="h-8 w-8 rounded-full bg-gradient-to-tr from-purple-500 to-pink-500 ring-2 ring-white/10"></div>
                </div>
            </header>

            {/* Main Content */}
            <main className="flex-1 p-6 lg:p-8 container mx-auto max-w-7xl animate-fade-in text-gray-100">
                {children}
            </main>
        </div>
    );
};
