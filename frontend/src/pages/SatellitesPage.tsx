import React, { useEffect, useState } from 'react';
import { SatelliteTable } from '../components/SatelliteTable';
import { useWebSocketContext } from '../contexts/WebSocketContext';
import { fetchClusterStats } from '../api/client';
import { Wifi, RefreshCw } from 'lucide-react';

export const SatellitesPage: React.FC = () => {
    const { isConnected } = useWebSocketContext();
    const [totalNodes, setTotalNodes] = useState(0);
    const [onlineNodes, setOnlineNodes] = useState(0);

    useEffect(() => {
        const loadStats = async () => {
            const stats = await fetchClusterStats();
            setTotalNodes(stats.totalNodes);
            setOnlineNodes(stats.onlineNodes);
        };
        loadStats();
        const interval = setInterval(loadStats, 5000);
        return () => clearInterval(interval);
    }, []);

    return (
        <div className="space-y-6">
            <header className="flex items-center justify-between">
                <div>
                    <h2 className="text-2xl font-bold text-white">Satellite Fleet</h2>
                    <p className="text-gray-400 mt-1">Manage and monitor connected compute nodes</p>
                </div>
                <div className="flex gap-4 items-center">
                    <div className="flex items-center gap-2 px-3 py-1.5 rounded-lg bg-white/5 border border-white/10 text-sm">
                        {isConnected ? (
                            <>
                                <span className="w-2 h-2 rounded-full bg-green-400 animate-pulse" />
                                <span className="text-green-400">Live</span>
                            </>
                        ) : (
                            <>
                                <RefreshCw size={14} className="text-yellow-400 animate-spin" />
                                <span className="text-yellow-400">Connecting...</span>
                            </>
                        )}
                    </div>
                    <div className="px-4 py-2 rounded-lg bg-white/5 border border-white/10 text-sm text-gray-300">
                        <Wifi size={14} className="inline mr-2 text-cyan-400" />
                        Online: <span className="text-cyan-400 font-mono ml-1">{onlineNodes.toLocaleString()}</span>
                        <span className="text-gray-500 mx-1">/</span>
                        <span className="text-gray-400 font-mono">{totalNodes.toLocaleString()}</span>
                    </div>
                </div>
            </header>

            <SatelliteTable />
        </div>
    );
};
