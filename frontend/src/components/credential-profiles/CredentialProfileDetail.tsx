import React from 'react';
import { AlertTriangle, CheckCircle2, KeyRound, Pencil, RefreshCw, Shield, Trash2 } from 'lucide-react';
import { GlassCard } from '../GlassCard';
import type { CredentialProfile, CredentialProfileValidation } from '../../api/client';

interface CredentialProfileDetailProps {
    profile: CredentialProfile;
    validation?: CredentialProfileValidation;
    isEditing: boolean;
    isDeleting: boolean;
    isValidating: boolean;
    onEdit: (profile: CredentialProfile) => void;
    onDelete: (id: string) => void;
    onValidate: (id: string) => void;
}

export const CredentialProfileDetail: React.FC<CredentialProfileDetailProps> = ({
    profile,
    validation,
    isEditing,
    isDeleting,
    isValidating,
    onEdit,
    onDelete,
    onValidate,
}) => {
    return (
        <GlassCard className={isEditing ? 'ring-1 ring-cyan-400/40' : undefined}>
            <div className="flex flex-col gap-4 lg:flex-row lg:items-start lg:justify-between">
                <div className="space-y-3">
                    <div className="flex flex-wrap items-center gap-2">
                        <h3 className="text-lg font-semibold text-white">{profile.name}</h3>
                        <span className={`rounded-full px-2.5 py-0.5 text-xs ${profile.enabled ? 'bg-emerald-500/10 text-emerald-300' : 'bg-white/5 text-gray-400'}`}>
                            {profile.enabled ? 'Enabled' : 'Disabled'}
                        </span>
                        <span className={`rounded-full px-2.5 py-0.5 text-xs ${profile.autoClaim ? 'bg-blue-500/10 text-blue-300' : 'bg-white/5 text-gray-400'}`}>
                            {profile.autoClaim ? 'Auto Claim' : 'Manual Claim'}
                        </span>
                        {profile.sourceType && (
                            <span className="rounded-full bg-white/5 px-2.5 py-0.5 text-xs text-gray-300">
                                {profile.sourceType}
                            </span>
                        )}
                        {profile.managedAccountEnabled && (
                            <span className="rounded-full bg-amber-500/10 px-2.5 py-0.5 text-xs text-amber-300">
                                Managed Account
                            </span>
                        )}
                    </div>

                    <div className="flex flex-wrap gap-2 text-xs">
                        <span className="rounded-full bg-white/5 px-2.5 py-0.5 text-gray-300">
                            Priority {profile.priority}
                        </span>
                        <span className="rounded-full bg-white/5 px-2.5 py-0.5 text-gray-300">
                            {profile.protocol}
                        </span>
                        {profile.redfishTemplate && (
                            <span className="inline-flex items-center gap-1 rounded-full bg-blue-500/10 px-2.5 py-0.5 text-blue-300">
                                <Shield size={12} />
                                {profile.redfishTemplate}
                            </span>
                        )}
                        {profile.redfishAuthMode && (
                            <span className="rounded-full bg-cyan-500/10 px-2.5 py-0.5 text-cyan-200">
                                {profile.redfishAuthMode}
                            </span>
                        )}
                    </div>

                    <div className="grid grid-cols-1 gap-3 text-sm text-gray-300 md:grid-cols-2">
                        <div>
                            <div className="mb-1 text-xs uppercase tracking-wide text-gray-500">Matching</div>
                            <div>Vendor: {profile.vendorPattern || '—'}</div>
                            <div>Model: {profile.modelPattern || '—'}</div>
                            <div>Subnet: {profile.subnetCidr || '—'}</div>
                            <div>Type: {profile.deviceType || '—'}</div>
                            <div>Host: {profile.hostnamePattern || '—'}</div>
                            <div>IP: {profile.ipAddressPattern || '—'}</div>
                            <div>MAC: {profile.macAddressPattern || '—'}</div>
                            <div>Ref: {profile.externalRef || '—'}</div>
                        </div>
                        <div>
                            <div className="mb-1 text-xs uppercase tracking-wide text-gray-500">Secret Refs</div>
                            <div className="inline-flex items-center gap-2">
                                <KeyRound size={13} className="text-cyan-400" />
                                <span className="font-mono text-xs">{profile.usernameSecretRef || '—'}</span>
                            </div>
                            <div className="inline-flex items-center gap-2">
                                <KeyRound size={13} className="text-cyan-400" />
                                <span className="font-mono text-xs">{profile.passwordSecretRef || '—'}</span>
                            </div>
                            {profile.managedAccountEnabled && (
                                <>
                                    <div className="mt-2 text-xs uppercase tracking-wide text-gray-500">Managed Account</div>
                                    <div className="inline-flex items-center gap-2">
                                        <KeyRound size={13} className="text-amber-400" />
                                        <span className="font-mono text-xs">{profile.managedUsernameSecretRef || '—'}</span>
                                    </div>
                                    <div className="inline-flex items-center gap-2">
                                        <KeyRound size={13} className="text-amber-400" />
                                        <span className="font-mono text-xs">{profile.managedPasswordSecretRef || '—'}</span>
                                    </div>
                                    <div>Role: {profile.managedAccountRoleId || 'Administrator'}</div>
                                </>
                            )}
                        </div>
                    </div>

                    {profile.description && (
                        <p className="text-sm text-gray-400">{profile.description}</p>
                    )}

                    {validation && (
                        <div className={`rounded-lg border px-4 py-3 text-sm ${
                            validation.ready
                                ? 'border-emerald-500/30 bg-emerald-500/10 text-emerald-200'
                                : 'border-amber-500/30 bg-amber-500/10 text-amber-100'
                        }`}>
                            <div className="flex items-center gap-2 font-medium">
                                {validation.ready ? <CheckCircle2 size={16} /> : <AlertTriangle size={16} />}
                                {validation.ready ? 'Ready to claim' : 'Validation needs attention'}
                            </div>
                            <p className="mt-2 text-sm">{validation.message}</p>
                            <div className="mt-2 text-xs opacity-90">
                                <div>Username: {validation.usernameMessage}</div>
                                <div>Password: {validation.passwordMessage}</div>
                                {validation.credentialSource && (
                                    <div>Source: {validation.credentialSource}</div>
                                )}
                                {validation.managedAccountEnabled && (
                                    <>
                                        <div className="mt-2 font-medium">Managed Account: {validation.managedAccountMessage}</div>
                                        <div>Managed Username: {validation.managedUsernameMessage}</div>
                                        <div>Managed Password: {validation.managedPasswordMessage}</div>
                                    </>
                                )}
                            </div>
                        </div>
                    )}
                </div>

                <div className="flex flex-wrap gap-2">
                    <button
                        onClick={() => onValidate(profile.id)}
                        disabled={isValidating}
                        className="inline-flex items-center justify-center gap-2 rounded-lg bg-cyan-500/15 px-3 py-2 text-cyan-200 hover:bg-cyan-500/25 disabled:opacity-50"
                        title="Validate profile secret refs"
                    >
                        <RefreshCw size={16} className={isValidating ? 'animate-spin' : ''} />
                        Validate
                    </button>
                    <button
                        onClick={() => onEdit(profile)}
                        className="inline-flex items-center justify-center gap-2 rounded-lg bg-white/5 px-3 py-2 text-gray-200 hover:bg-white/10"
                        title="Edit profile"
                    >
                        <Pencil size={16} />
                        Edit
                    </button>
                    <button
                        onClick={() => onDelete(profile.id)}
                        disabled={isDeleting}
                        className="inline-flex items-center justify-center gap-2 rounded-lg bg-red-500/15 px-3 py-2 text-red-300 hover:bg-red-500/25 disabled:opacity-50"
                        title="Delete profile"
                    >
                        <Trash2 size={16} />
                        Delete
                    </button>
                </div>
            </div>
        </GlassCard>
    );
};
