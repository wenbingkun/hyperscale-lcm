import React from 'react';
import { Monitor, Search, Wifi } from 'lucide-react';
import type { DiscoveredDevice } from '../../api/client';
import { GlassCard } from '../GlassCard';
import { DiscoveryApprovalPanel } from './DiscoveryApprovalPanel';

interface DiscoveryListProps {
    devices: DiscoveredDevice[];
    filteredDevices: DiscoveredDevice[];
    searchQuery: string;
    statusFilter: string;
    authFilter: string;
    claimFilter: string;
    busyAction: string | null;
    formatDate: (value?: string) => string;
    getStatusColor: (status?: string) => string;
    getAuthColor: (status?: string) => string;
    getClaimColor: (status?: string) => string;
    onSearchQueryChange: (value: string) => void;
    onStatusFilterChange: (value: string) => void;
    onAuthFilterChange: (value: string) => void;
    onClaimFilterChange: (value: string) => void;
    onRefreshClaimPlan: (id: string) => void;
    onExecuteClaim: (id: string) => void;
    onApproveDevice: (id: string) => void;
    onRejectDevice: (id: string) => void;
}

export const DiscoveryList: React.FC<DiscoveryListProps> = ({
    devices,
    filteredDevices,
    searchQuery,
    statusFilter,
    authFilter,
    claimFilter,
    busyAction,
    formatDate,
    getStatusColor,
    getAuthColor,
    getClaimColor,
    onSearchQueryChange,
    onStatusFilterChange,
    onAuthFilterChange,
    onClaimFilterChange,
    onRefreshClaimPlan,
    onExecuteClaim,
    onApproveDevice,
    onRejectDevice,
}) => {
    return (
        <GlassCard className="min-h-[400px]">
            <div className="mb-4 flex flex-col gap-3 border-b border-white/5 pb-4 lg:flex-row lg:items-center">
                <div className="relative flex-1">
                    <Search size={16} className="absolute left-3 top-1/2 -translate-y-1/2 text-gray-500" />
                    <input
                        type="text"
                        value={searchQuery}
                        onChange={(event) => onSearchQueryChange(event.target.value)}
                        placeholder="Search by IP, host, vendor, model, profile, template"
                        className="w-full rounded-lg border border-white/10 bg-white/5 py-2 pl-10 pr-4 text-sm text-white placeholder-gray-500 focus:border-cyan-500 focus:outline-none"
                    />
                </div>
                <select
                    value={statusFilter}
                    onChange={(event) => onStatusFilterChange(event.target.value)}
                    className="rounded-lg border border-white/10 bg-slate-950 px-3 py-2 text-sm text-white focus:border-cyan-500 focus:outline-none"
                >
                    <option value="ALL">All status</option>
                    <option value="PENDING">Pending</option>
                    <option value="APPROVED">Approved</option>
                    <option value="REJECTED">Rejected</option>
                    <option value="MANAGED">Managed</option>
                </select>
                <select
                    value={authFilter}
                    onChange={(event) => onAuthFilterChange(event.target.value)}
                    className="rounded-lg border border-white/10 bg-slate-950 px-3 py-2 text-sm text-white focus:border-cyan-500 focus:outline-none"
                >
                    <option value="ALL">All auth</option>
                    <option value="PENDING">Pending</option>
                    <option value="PROFILE_MATCHED">Profile matched</option>
                    <option value="AUTH_PENDING">Auth pending</option>
                    <option value="AUTH_FAILED">Auth failed</option>
                    <option value="AUTHENTICATED">Authenticated</option>
                </select>
                <select
                    value={claimFilter}
                    onChange={(event) => onClaimFilterChange(event.target.value)}
                    className="rounded-lg border border-white/10 bg-slate-950 px-3 py-2 text-sm text-white focus:border-cyan-500 focus:outline-none"
                >
                    <option value="ALL">All claim</option>
                    <option value="DISCOVERED">Discovered</option>
                    <option value="READY_TO_CLAIM">Ready to claim</option>
                    <option value="CLAIMING">Claiming</option>
                    <option value="CLAIMED">Claimed</option>
                    <option value="MANAGED">Managed</option>
                </select>
            </div>
            <div className="overflow-x-auto">
                <table className="w-full text-left">
                    <thead>
                        <tr className="border-b border-white/5 text-sm text-gray-500">
                            <th className="px-4 py-4 font-medium">Discovery</th>
                            <th className="px-4 py-4 font-medium">Endpoint</th>
                            <th className="px-4 py-4 font-medium">Classification</th>
                            <th className="px-4 py-4 font-medium">Claim Plan</th>
                            <th className="px-4 py-4 font-medium">Actions</th>
                        </tr>
                    </thead>
                    <tbody>
                        {filteredDevices.length === 0 ? (
                            <tr>
                                <td colSpan={5} className="py-12 text-center text-gray-500">
                                    <Wifi size={48} className="mx-auto mb-4 opacity-30" />
                                    {devices.length === 0 ? 'No devices discovered yet' : 'No devices match the current filters'}
                                    <p className="mt-2 text-sm">
                                        {devices.length === 0 ? 'Start a network scan to discover devices' : 'Adjust the filters or search query'}
                                    </p>
                                </td>
                            </tr>
                        ) : (
                            filteredDevices.map((device) => (
                                <tr key={device.id} className="align-top border-b border-white/5 transition-colors hover:bg-white/5">
                                    <td className="min-w-[220px] px-4 py-4">
                                        <div className="space-y-2">
                                            <div className="flex flex-wrap gap-2">
                                                <span className={`rounded-full px-2.5 py-0.5 text-xs font-medium ${getStatusColor(device.status)}`}>
                                                    {device.status}
                                                </span>
                                                <span className={`rounded-full px-2.5 py-0.5 text-xs font-medium ${getAuthColor(device.authStatus)}`}>
                                                    {device.authStatus || 'PENDING'}
                                                </span>
                                                <span className={`rounded-full px-2.5 py-0.5 text-xs font-medium ${getClaimColor(device.claimStatus)}`}>
                                                    {device.claimStatus || 'DISCOVERED'}
                                                </span>
                                            </div>
                                            <div className="text-xs text-gray-500">
                                                <div>{device.discoveryMethod}</div>
                                                <div>{formatDate(device.discoveredAt)}</div>
                                                {device.lastProbedAt && <div>Last probe: {formatDate(device.lastProbedAt)}</div>}
                                            </div>
                                        </div>
                                    </td>
                                    <td className="min-w-[240px] px-4 py-4">
                                        <div className="space-y-1">
                                            <div className="font-mono text-cyan-300">{device.ipAddress}</div>
                                            <div className="text-white">{device.hostname || '—'}</div>
                                            {device.bmcAddress && (
                                                <div className="font-mono text-xs text-cyan-500">BMC: {device.bmcAddress}</div>
                                            )}
                                            {device.macAddress && (
                                                <div className="font-mono text-xs text-gray-500">{device.macAddress}</div>
                                            )}
                                        </div>
                                    </td>
                                    <td className="min-w-[260px] px-4 py-4">
                                        <div className="space-y-2">
                                            <div className="flex items-center gap-2 text-gray-300">
                                                <Monitor size={14} />
                                                <span>{device.inferredType || 'UNKNOWN'}</span>
                                            </div>
                                            <div className="text-sm text-gray-400">
                                                {device.manufacturerHint && <div>Vendor: {device.manufacturerHint}</div>}
                                                {device.modelHint && <div>Model: {device.modelHint}</div>}
                                                {device.openPorts && <div className="text-xs text-gray-500">Ports: {device.openPorts}</div>}
                                            </div>
                                        </div>
                                    </td>
                                    <DiscoveryApprovalPanel
                                        device={device}
                                        busyAction={busyAction}
                                        formatDate={formatDate}
                                        onRefreshClaimPlan={onRefreshClaimPlan}
                                        onExecuteClaim={onExecuteClaim}
                                        onApproveDevice={onApproveDevice}
                                        onRejectDevice={onRejectDevice}
                                    />
                                </tr>
                            ))
                        )}
                    </tbody>
                </table>
            </div>
        </GlassCard>
    );
};
