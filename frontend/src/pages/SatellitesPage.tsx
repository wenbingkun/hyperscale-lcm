import React from 'react';
import { SatelliteTable } from '../components/SatelliteTable';

export const SatellitesPage: React.FC = () => {
    return (
        <div className="space-y-6">
            <header className="flex items-center justify-between">
                <div>
                    <h2 className="text-2xl font-bold text-white">Satellite Fleet</h2>
                    <p className="text-gray-400 mt-1">Manage and monitor connected compute nodes</p>
                </div>
                <div className="flex gap-4">
                    <div className="px-4 py-2 rounded-lg bg-white/5 border border-white/10 text-sm text-gray-300">
                        Total Nodes: <span className="text-cyan-400 font-mono ml-2">12,450</span>
                    </div>
                </div>
            </header>

            <SatelliteTable />
        </div>
    );
};
