import React from 'react';
import { Check, KeyRound, RefreshCw, Shield, X } from 'lucide-react';
import type { DiscoveredDevice } from '../../api/client';

interface DiscoveryApprovalPanelProps {
    device: DiscoveredDevice;
    busyAction: string | null;
    formatDate: (value?: string) => string;
    onRefreshClaimPlan: (id: string) => void;
    onExecuteClaim: (id: string) => void;
    onApproveDevice: (id: string) => void;
    onRejectDevice: (id: string) => void;
}

export const DiscoveryApprovalPanel: React.FC<DiscoveryApprovalPanelProps> = ({
    device,
    busyAction,
    formatDate,
    onRefreshClaimPlan,
    onExecuteClaim,
    onApproveDevice,
    onRejectDevice,
}) => {
    return (
        <>
            <td className="min-w-[320px] px-4 py-4">
                <div className="space-y-2">
                    <div className="flex flex-wrap items-center gap-2">
                        {device.credentialProfileName ? (
                            <span className="inline-flex items-center gap-1 rounded-full bg-emerald-500/10 px-2.5 py-0.5 text-xs text-emerald-300">
                                <KeyRound size={12} />
                                {device.credentialProfileName}
                            </span>
                        ) : (
                            <span className="inline-flex items-center gap-1 rounded-full bg-white/5 px-2.5 py-0.5 text-xs text-gray-400">
                                <KeyRound size={12} />
                                No profile matched
                            </span>
                        )}
                        {device.recommendedRedfishTemplate && (
                            <span className="inline-flex items-center gap-1 rounded-full bg-blue-500/10 px-2.5 py-0.5 text-xs text-blue-300">
                                <Shield size={12} />
                                {device.recommendedRedfishTemplate}
                            </span>
                        )}
                    </div>
                    <p className="text-sm text-gray-300">{device.claimMessage || 'No claim plan generated yet.'}</p>
                    <div className="text-xs text-gray-500">
                        {device.credentialSource && <div>Source: {device.credentialSource}</div>}
                        {device.lastAuthAttemptAt && <div>Last auth attempt: {formatDate(device.lastAuthAttemptAt)}</div>}
                    </div>
                </div>
            </td>
            <td className="min-w-[180px] px-4 py-4">
                <div className="flex flex-wrap gap-2">
                    <button
                        onClick={() => onRefreshClaimPlan(device.id)}
                        disabled={busyAction === `replan:${device.id}`}
                        className="inline-flex items-center gap-2 rounded-lg bg-white/5 px-3 py-2 text-gray-300 hover:bg-white/10 disabled:opacity-50"
                        title="Recalculate claim plan"
                    >
                        <RefreshCw size={14} className={busyAction === `replan:${device.id}` ? 'animate-spin' : ''} />
                        Replan
                    </button>
                    {device.claimStatus === 'READY_TO_CLAIM' && (
                        <button
                            onClick={() => onExecuteClaim(device.id)}
                            disabled={busyAction === `claim:${device.id}`}
                            className="inline-flex items-center gap-2 rounded-lg bg-cyan-500/15 px-3 py-2 text-cyan-200 hover:bg-cyan-500/25 disabled:opacity-50"
                            title="Execute first Redfish claim"
                        >
                            <Shield size={14} />
                            {busyAction === `claim:${device.id}` ? 'Claiming...' : 'Claim'}
                        </button>
                    )}
                    {device.status === 'PENDING' && (
                        <>
                            <button
                                onClick={() => onApproveDevice(device.id)}
                                disabled={busyAction === `approve:${device.id}`}
                                className="rounded bg-green-500/20 p-2 text-green-400 hover:bg-green-500/30 disabled:opacity-50"
                                title="Approve"
                            >
                                <Check size={16} />
                            </button>
                            <button
                                onClick={() => onRejectDevice(device.id)}
                                disabled={busyAction === `reject:${device.id}`}
                                className="rounded bg-red-500/20 p-2 text-red-400 hover:bg-red-500/30 disabled:opacity-50"
                                title="Reject"
                            >
                                <X size={16} />
                            </button>
                        </>
                    )}
                </div>
            </td>
        </>
    );
};
