import React from 'react';
import { Check, KeyRound, RefreshCw, Shield, X, Zap } from 'lucide-react';
import type {
    BmcCapabilitySnapshot,
    BmcPowerAction,
    BmcPowerActionResult,
    DiscoveredDevice,
} from '../../api/client';

interface DiscoveryApprovalPanelProps {
    device: DiscoveredDevice;
    busyAction: string | null;
    canManageBmc: boolean;
    powerActions: readonly BmcPowerAction[];
    capabilitySnapshot: BmcCapabilitySnapshot | null;
    powerPreview?: BmcPowerActionResult;
    selectedPowerAction: BmcPowerAction;
    powerSystemId: string;
    powerConfirmation: string;
    formatDate: (value?: string) => string;
    onRefreshClaimPlan: (id: string) => void;
    onExecuteClaim: (id: string) => void;
    onInspectBmc: (id: string) => void;
    onRotateBmcCredentials: (id: string) => void;
    onPreviewPowerAction: (id: string) => void;
    onExecutePowerAction: (id: string) => void;
    onPowerActionChange: (id: string, action: BmcPowerAction) => void;
    onPowerSystemIdChange: (id: string, value: string) => void;
    onPowerConfirmationChange: (id: string, value: string) => void;
    onApproveDevice: (id: string) => void;
    onRejectDevice: (id: string) => void;
}

function readBoolean(value: unknown): boolean | null {
    return typeof value === 'boolean' ? value : null;
}

function readNumber(value: unknown): number | null {
    return typeof value === 'number' ? value : null;
}

function readStringArray(value: unknown): string[] {
    return Array.isArray(value) ? value.filter((item): item is string => typeof item === 'string' && item.length > 0) : [];
}

export const DiscoveryApprovalPanel: React.FC<DiscoveryApprovalPanelProps> = ({
    device,
    busyAction,
    canManageBmc,
    powerActions,
    capabilitySnapshot,
    powerPreview,
    selectedPowerAction,
    powerSystemId,
    powerConfirmation,
    formatDate,
    onRefreshClaimPlan,
    onExecuteClaim,
    onInspectBmc,
    onRotateBmcCredentials,
    onPreviewPowerAction,
    onExecutePowerAction,
    onPowerActionChange,
    onPowerSystemIdChange,
    onPowerConfirmationChange,
    onApproveDevice,
    onRejectDevice,
}) => {
    const capabilities = capabilitySnapshot?.capabilities ?? device.bmcCapabilities ?? null;
    const sessionAuth = readBoolean(capabilities?.sessionAuth);
    const powerControl = readBoolean(capabilities?.powerControl);
    const systemCount = readNumber(capabilities?.systemCount);
    const resetActions = readStringArray(capabilities?.resetActions);
    const showBmcPanel = device.inferredType === 'BMC' || capabilitySnapshot !== null || Boolean(device.bmcAddress);
    const canRotate = device.claimStatus === 'CLAIMED' || device.claimStatus === 'MANAGED';
    const canExecutePowerAction = powerPreview?.status === 'DRY_RUN'
        && powerPreview.action === selectedPowerAction
        && powerConfirmation.trim() === selectedPowerAction;

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
                    {showBmcPanel && (
                        <div className="rounded-xl border border-white/5 bg-slate-950/50 p-3">
                            <div className="flex flex-wrap items-center gap-2">
                                <span className="text-xs font-medium uppercase tracking-[0.2em] text-gray-500">BMC Snapshot</span>
                                {sessionAuth !== null && (
                                    <span className={`rounded-full px-2.5 py-0.5 text-xs ${
                                        sessionAuth ? 'bg-cyan-500/10 text-cyan-300' : 'bg-white/5 text-gray-400'
                                    }`}>
                                        {sessionAuth ? 'Session Auth' : 'Basic Only'}
                                    </span>
                                )}
                                {powerControl !== null && (
                                    <span className={`rounded-full px-2.5 py-0.5 text-xs ${
                                        powerControl ? 'bg-emerald-500/10 text-emerald-300' : 'bg-amber-500/10 text-amber-300'
                                    }`}>
                                        {powerControl ? 'Power Control' : 'No Reset Action'}
                                    </span>
                                )}
                                {systemCount !== null && (
                                    <span className="rounded-full bg-white/5 px-2.5 py-0.5 text-xs text-gray-300">
                                        Systems {systemCount}
                                    </span>
                                )}
                                {capabilitySnapshot?.lastSuccessfulAuthMode && (
                                    <span className="rounded-full bg-blue-500/10 px-2.5 py-0.5 text-xs text-blue-300">
                                        Last OK {capabilitySnapshot.lastSuccessfulAuthMode}
                                    </span>
                                )}
                            </div>
                            <div className="mt-2 space-y-1 text-xs text-gray-400">
                                {capabilitySnapshot?.lastCapabilityProbeAt && (
                                    <div>Last capability probe: {formatDate(capabilitySnapshot.lastCapabilityProbeAt)}</div>
                                )}
                                {capabilitySnapshot?.redfishAuthModeOverride && (
                                    <div>Auth override: {capabilitySnapshot.redfishAuthModeOverride}</div>
                                )}
                                {capabilitySnapshot?.lastAuthFailureCode && (
                                    <div className="text-amber-300">
                                        Last failure: {capabilitySnapshot.lastAuthFailureCode}
                                        {capabilitySnapshot.lastAuthFailureReason ? ` - ${capabilitySnapshot.lastAuthFailureReason}` : ''}
                                    </div>
                                )}
                                {resetActions.length > 0 && (
                                    <div>Reset actions: {resetActions.join(', ')}</div>
                                )}
                                {!capabilitySnapshot?.lastCapabilityProbeAt && !capabilities && (
                                    <div>No capability snapshot has been captured yet.</div>
                                )}
                            </div>
                            {canManageBmc && (
                                <div className="mt-3 space-y-2">
                                    <div className="grid gap-2 lg:grid-cols-[minmax(0,1fr)_minmax(0,1fr)]">
                                        <select
                                            aria-label={`Power action for ${device.ipAddress}`}
                                            value={selectedPowerAction}
                                            onChange={(event) => onPowerActionChange(device.id, event.target.value as BmcPowerAction)}
                                            className="rounded-lg border border-white/10 bg-slate-950 px-3 py-2 text-sm text-white focus:border-cyan-500 focus:outline-none"
                                        >
                                            {powerActions.map((action) => (
                                                <option key={action} value={action}>{action}</option>
                                            ))}
                                        </select>
                                        <input
                                            type="text"
                                            aria-label={`System ID for ${device.ipAddress}`}
                                            value={powerSystemId}
                                            onChange={(event) => onPowerSystemIdChange(device.id, event.target.value)}
                                            placeholder="Optional systemId"
                                            className="rounded-lg border border-white/10 bg-white/5 px-3 py-2 text-sm text-white placeholder-gray-500 focus:border-cyan-500 focus:outline-none"
                                        />
                                    </div>
                                    <p className="text-[11px] text-gray-500">
                                        Dry-run uses the new Phase 7 BMC management API. When a BMC exposes multiple systems, fill in `systemId`.
                                    </p>
                                    {powerPreview && (
                                        <div className={`rounded-lg border px-3 py-2 text-xs ${
                                            powerPreview.status === 'DRY_RUN'
                                                ? 'border-cyan-500/20 bg-cyan-500/10 text-cyan-100'
                                                : powerPreview.status === 'COMPLETED' || powerPreview.status === 'ACCEPTED'
                                                    ? 'border-emerald-500/20 bg-emerald-500/10 text-emerald-100'
                                                    : 'border-red-500/20 bg-red-500/10 text-red-100'
                                        }`}>
                                            <div className="font-medium">{powerPreview.status}</div>
                                            {powerPreview.message && <div className="mt-1">{powerPreview.message}</div>}
                                            {powerPreview.targetUri && <div className="mt-1 font-mono text-[11px]">{powerPreview.targetUri}</div>}
                                            {powerPreview.taskLocation && <div className="mt-1 font-mono text-[11px]">Task: {powerPreview.taskLocation}</div>}
                                            {powerPreview.allowedValues && powerPreview.allowedValues.length > 0 && (
                                                <div className="mt-1">Allowed: {powerPreview.allowedValues.join(', ')}</div>
                                            )}
                                        </div>
                                    )}
                                    {powerPreview?.status === 'DRY_RUN' && (
                                        <div className="rounded-lg border border-amber-500/20 bg-amber-500/10 p-3 text-xs text-amber-100">
                                            <p>
                                                Type <span className="font-mono">{selectedPowerAction}</span> to enable the real BMC power action.
                                            </p>
                                            <input
                                                type="text"
                                                aria-label={`Confirm power action for ${device.ipAddress}`}
                                                value={powerConfirmation}
                                                onChange={(event) => onPowerConfirmationChange(device.id, event.target.value)}
                                                placeholder={`Type ${selectedPowerAction} to confirm`}
                                                className="mt-2 w-full rounded-lg border border-amber-400/20 bg-slate-950/70 px-3 py-2 text-sm text-white placeholder-amber-200/40 focus:border-amber-300 focus:outline-none"
                                            />
                                        </div>
                                    )}
                                </div>
                            )}
                        </div>
                    )}
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
                    <button
                        onClick={() => onInspectBmc(device.id)}
                        disabled={busyAction === `inspect:${device.id}`}
                        className="inline-flex items-center gap-2 rounded-lg bg-blue-500/10 px-3 py-2 text-blue-200 hover:bg-blue-500/20 disabled:opacity-50"
                        title="Load the current BMC capability snapshot"
                    >
                        <RefreshCw size={14} className={busyAction === `inspect:${device.id}` ? 'animate-spin' : ''} />
                        Inspect
                    </button>
                    {device.claimStatus === 'READY_TO_CLAIM' && canManageBmc && (
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
                    {canManageBmc && (
                        <button
                            onClick={() => onPreviewPowerAction(device.id)}
                            disabled={busyAction === `power-preview:${device.id}`}
                            className="inline-flex items-center gap-2 rounded-lg bg-violet-500/15 px-3 py-2 text-violet-200 hover:bg-violet-500/25 disabled:opacity-50"
                            title="Preview the selected power action through dry-run"
                        >
                            <Zap size={14} />
                            {busyAction === `power-preview:${device.id}` ? 'Previewing...' : 'Dry Run'}
                        </button>
                    )}
                    {canManageBmc && powerPreview?.status === 'DRY_RUN' && (
                        <button
                            onClick={() => onExecutePowerAction(device.id)}
                            disabled={!canExecutePowerAction || busyAction === `power-execute:${device.id}`}
                            className="inline-flex items-center gap-2 rounded-lg bg-red-500/15 px-3 py-2 text-red-100 hover:bg-red-500/25 disabled:opacity-50"
                            title="Execute the selected power action against the BMC"
                        >
                            <Zap size={14} />
                            {busyAction === `power-execute:${device.id}` ? 'Executing...' : 'Execute'}
                        </button>
                    )}
                    {canManageBmc && canRotate && (
                        <button
                            onClick={() => onRotateBmcCredentials(device.id)}
                            disabled={busyAction === `rotate:${device.id}`}
                            className="inline-flex items-center gap-2 rounded-lg bg-amber-500/15 px-3 py-2 text-amber-100 hover:bg-amber-500/25 disabled:opacity-50"
                            title="Rotate the managed Redfish account"
                        >
                            <KeyRound size={14} />
                            {busyAction === `rotate:${device.id}` ? 'Rotating...' : 'Rotate'}
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
                {!canManageBmc && showBmcPanel && (
                    <p className="mt-3 text-xs text-gray-500">BMC claim, rotate, and power actions require the `ADMIN` role.</p>
                )}
            </td>
        </>
    );
};
