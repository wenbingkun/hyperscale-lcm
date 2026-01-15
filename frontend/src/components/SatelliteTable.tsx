import React, { useEffect, useState } from 'react';
import { GlassCard } from './GlassCard';
import { fetchSatellites } from '../api/client';
import type { Satellite } from '../api/client';

export const SatelliteTable: React.FC = () => {
    const [satellites, setSatellites] = useState<Satellite[]>([]);
    const [loading, setLoading] = useState(true);

    // Poll every 2 seconds
    useEffect(() => {
        const loadData = async () => {
            const data = await fetchSatellites();
            setSatellites(data);
            setLoading(false);
        };

        loadData(); // Initial load
        const interval = setInterval(loadData, 2000);
        return () => clearInterval(interval);
    }, []);

    return (
        <GlassCard title="Satellite Fleet">
            {loading ? (
                <div className="p-4 text-cyan-400 animate-pulse">Scanning Network...</div>
            ) : (
                <div className="overflow-x-auto">
                    <table className="w-full text-left border-collapse">
                        <thead>
                            <tr className="text-sm text-gray-500 border-b border-gray-800">
                                <th className="py-3 font-medium">Status</th>
                                <th className="py-3 font-medium">Hostname</th>
                                <th className="py-3 font-medium">IP Address</th>
                                <th className="py-3 font-medium">OS Version</th>
                                <th className="py-3 font-medium">Last Heartbeat</th>
                            </tr>
                        </thead>
                        <tbody>
                            {satellites.length === 0 ? (
                                <tr>
                                    <td colSpan={5} className="py-4 text-center text-gray-600">No satellites discovered yet.</td>
                                </tr>
                            ) : (
                                satellites.map((sat) => (
                                    <tr key={sat.id} className="border-b border-gray-800/50 hover:bg-white/5 transition-colors">
                                        <td className="py-3">
                                            <span className={`inline-flex h-2.5 w-2.5 rounded-full ${sat.status === 'ONLINE' ? 'bg-green-400 shadow-[0_0_8px_rgba(74,222,128,0.5)]' : 'bg-red-500'}`}></span>
                                        </td>
                                        <td className="py-3 font-medium text-white">{sat.hostname}</td>
                                        <td className="py-3 text-cyan-300 font-mono text-sm">{sat.ipAddress}</td>
                                        <td className="py-3 text-gray-400 text-sm">{sat.osVersion}</td>
                                        <td className="py-3 text-gray-500 text-xs font-mono">{new Date(sat.lastHeartbeat).toLocaleTimeString()}</td>
                                    </tr>
                                ))
                            )}
                        </tbody>
                    </table>
                </div>
            )}
        </GlassCard>
    );
};
