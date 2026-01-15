import React from 'react';

interface LayoutProps {
    children: React.ReactNode;
}

export const DashboardLayout: React.FC<LayoutProps> = ({ children }) => {
    return (
        <div className="min-h-screen flex flex-col">
            {/* Header */}
            <header className="h-16 border-b border-white/10 flex items-center px-8 bg-black/40 backdrop-blur-md sticky top-0 z-50">
                <div className="flex items-center gap-3">
                    <div className="w-8 h-8 rounded bg-gradient-to-br from-cyan-400 to-blue-600 flex items-center justify-center font-bold text-white">
                        H
                    </div>
                    <h1 className="text-xl font-bold tracking-tight">
                        <span className="text-white">Hyperscale</span>
                        <span className="text-cyan-400">LCM</span>
                    </h1>
                </div>
                <nav className="ml-auto flex gap-6 text-sm font-medium text-gray-400">
                    <a href="#" className="hover:text-cyan-400 transition-colors">Overview</a>
                    <a href="#" className="hover:text-cyan-400 transition-colors">Satellites</a>
                    <a href="#" className="hover:text-cyan-400 transition-colors">Jobs</a>
                    <a href="#" className="hover:text-cyan-400 transition-colors">Settings</a>
                </nav>
            </header>

            {/* Main Content */}
            <main className="flex-1 p-8 container mx-auto max-w-7xl animate-fade-in">
                {children}
            </main>
        </div>
    );
};
