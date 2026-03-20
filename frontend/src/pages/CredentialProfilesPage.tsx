import React, { useEffect, useState } from 'react';
import { GlassCard } from '../components/GlassCard';
import {
    type CredentialProfile,
    type CredentialProfileRequest,
    type CredentialProfileValidation,
    type CmdbSyncResult,
    type RedfishTemplateSummary,
    createCredentialProfile,
    deleteCredentialProfile,
    fetchCredentialProfiles,
    fetchRedfishTemplates,
    importBootstrapCredentialProfiles,
    syncCmdbCredentialProfiles,
    updateCredentialProfile,
    validateCredentialProfile,
} from '../api/client';
import { RefreshCw, Shield, KeyRound, Plus, Trash2, Database, Pencil, X, CheckCircle2, AlertTriangle } from 'lucide-react';

interface CredentialProfileFormState {
    name: string;
    protocol: string;
    enabled: boolean;
    autoClaim: boolean;
    priority: string;
    sourceType: string;
    externalRef: string;
    vendorPattern: string;
    modelPattern: string;
    subnetCidr: string;
    deviceType: string;
    hostnamePattern: string;
    ipAddressPattern: string;
    macAddressPattern: string;
    redfishTemplate: string;
    usernameSecretRef: string;
    passwordSecretRef: string;
    managedAccountEnabled: boolean;
    managedUsernameSecretRef: string;
    managedPasswordSecretRef: string;
    managedAccountRoleId: string;
    description: string;
}

const createEmptyForm = (): CredentialProfileFormState => ({
    name: '',
    protocol: 'REDFISH',
    enabled: true,
    autoClaim: true,
    priority: '100',
    sourceType: 'MANUAL',
    externalRef: '',
    vendorPattern: '',
    modelPattern: '',
    subnetCidr: '',
    deviceType: 'BMC_ENABLED',
    hostnamePattern: '',
    ipAddressPattern: '',
    macAddressPattern: '',
    redfishTemplate: 'openbmc-baseline',
    usernameSecretRef: '',
    passwordSecretRef: '',
    managedAccountEnabled: false,
    managedUsernameSecretRef: '',
    managedPasswordSecretRef: '',
    managedAccountRoleId: 'Administrator',
    description: '',
});

const optionalValue = (value: string): string | undefined => {
    const trimmed = value.trim();
    return trimmed === '' ? undefined : trimmed;
};

const toFormState = (profile: CredentialProfile): CredentialProfileFormState => ({
    name: profile.name,
    protocol: profile.protocol || 'REDFISH',
    enabled: profile.enabled,
    autoClaim: profile.autoClaim,
    priority: String(profile.priority ?? 100),
    sourceType: profile.sourceType || 'MANUAL',
    externalRef: profile.externalRef || '',
    vendorPattern: profile.vendorPattern || '',
    modelPattern: profile.modelPattern || '',
    subnetCidr: profile.subnetCidr || '',
    deviceType: profile.deviceType || 'BMC_ENABLED',
    hostnamePattern: profile.hostnamePattern || '',
    ipAddressPattern: profile.ipAddressPattern || '',
    macAddressPattern: profile.macAddressPattern || '',
    redfishTemplate: profile.redfishTemplate || 'openbmc-baseline',
    usernameSecretRef: profile.usernameSecretRef || '',
    passwordSecretRef: profile.passwordSecretRef || '',
    managedAccountEnabled: profile.managedAccountEnabled || false,
    managedUsernameSecretRef: profile.managedUsernameSecretRef || '',
    managedPasswordSecretRef: profile.managedPasswordSecretRef || '',
    managedAccountRoleId: profile.managedAccountRoleId || 'Administrator',
    description: profile.description || '',
});

export const CredentialProfilesPage: React.FC = () => {
    const [profiles, setProfiles] = useState<CredentialProfile[]>([]);
    const [templates, setTemplates] = useState<RedfishTemplateSummary[]>([]);
    const [loading, setLoading] = useState(true);
    const [submitting, setSubmitting] = useState(false);
    const [deletingId, setDeletingId] = useState<string | null>(null);
    const [form, setForm] = useState<CredentialProfileFormState>(createEmptyForm());
    const [editingId, setEditingId] = useState<string | null>(null);
    const [validatingId, setValidatingId] = useState<string | null>(null);
    const [validationResults, setValidationResults] = useState<Record<string, CredentialProfileValidation>>({});
    const [showImportModal, setShowImportModal] = useState(false);
    const [syncingCmdb, setSyncingCmdb] = useState(false);
    const [cmdbSyncResult, setCmdbSyncResult] = useState<CmdbSyncResult | null>(null);
    const [importPayload, setImportPayload] = useState(`[
  {
    "name": "rack-a-openbmc",
    "protocol": "REDFISH",
    "enabled": true,
    "autoClaim": true,
    "priority": 1000,
    "sourceType": "CMDB",
    "externalRef": "asset-001",
    "ipAddressPattern": "^10\\\\.10\\\\.0\\\\.50$",
    "vendorPattern": "OpenBMC",
    "deviceType": "BMC_ENABLED",
    "redfishTemplate": "openbmc-baseline",
    "usernameSecretRef": "vault://bmc/rack-a#username",
    "passwordSecretRef": "vault://bmc/rack-a#password",
    "managedAccountEnabled": true,
    "managedUsernameSecretRef": "vault://bmc/rack-a-managed#username",
    "managedPasswordSecretRef": "vault://bmc/rack-a-managed#password",
    "managedAccountRoleId": "Administrator"
  }
]`);
    const [importing, setImporting] = useState(false);

    const loadProfiles = async () => {
        setLoading(true);
        try {
            const [profileData, templateData] = await Promise.all([
                fetchCredentialProfiles(),
                fetchRedfishTemplates(),
            ]);
            setProfiles(profileData);
            setTemplates(templateData);
        } finally {
            setLoading(false);
        }
    };

    useEffect(() => {
        loadProfiles();
    }, []);

    const handleSubmit = async (event: React.FormEvent<HTMLFormElement>) => {
        event.preventDefault();
        if (!form.name.trim()) return;

        const payload = {
            name: form.name.trim(),
            protocol: form.protocol.trim() || 'REDFISH',
            enabled: form.enabled,
            autoClaim: form.autoClaim,
            priority: Number.parseInt(form.priority, 10) || 100,
            sourceType: optionalValue(form.sourceType),
            externalRef: optionalValue(form.externalRef),
            vendorPattern: optionalValue(form.vendorPattern),
            modelPattern: optionalValue(form.modelPattern),
            subnetCidr: optionalValue(form.subnetCidr),
            deviceType: optionalValue(form.deviceType),
            hostnamePattern: optionalValue(form.hostnamePattern),
            ipAddressPattern: optionalValue(form.ipAddressPattern),
            macAddressPattern: optionalValue(form.macAddressPattern),
            redfishTemplate: optionalValue(form.redfishTemplate),
            usernameSecretRef: optionalValue(form.usernameSecretRef),
            passwordSecretRef: optionalValue(form.passwordSecretRef),
            managedAccountEnabled: form.managedAccountEnabled,
            managedUsernameSecretRef: optionalValue(form.managedUsernameSecretRef),
            managedPasswordSecretRef: optionalValue(form.managedPasswordSecretRef),
            managedAccountRoleId: optionalValue(form.managedAccountRoleId),
            description: optionalValue(form.description),
        };

        setSubmitting(true);
        try {
            if (editingId) {
                await updateCredentialProfile(editingId, payload);
            } else {
                await createCredentialProfile(payload);
            }
            setEditingId(null);
            setForm(createEmptyForm());
            await loadProfiles();
        } catch (error) {
            console.error(`Failed to ${editingId ? 'update' : 'create'} credential profile:`, error);
        } finally {
            setSubmitting(false);
        }
    };

    const handleEdit = (profile: CredentialProfile) => {
        setEditingId(profile.id);
        setForm(toFormState(profile));
    };

    const resetForm = () => {
        setEditingId(null);
        setForm(createEmptyForm());
    };

    const handleDelete = async (id: string) => {
        setDeletingId(id);
        try {
            await deleteCredentialProfile(id);
            if (editingId === id) {
                resetForm();
            }
            setValidationResults((current) => {
                const next = { ...current };
                delete next[id];
                return next;
            });
            await loadProfiles();
        } catch (error) {
            console.error('Failed to delete credential profile:', error);
        } finally {
            setDeletingId(null);
        }
    };

    const handleValidate = async (id: string) => {
        setValidatingId(id);
        try {
            const result = await validateCredentialProfile(id);
            setValidationResults((current) => ({ ...current, [id]: result }));
        } catch (error) {
            console.error('Failed to validate credential profile:', error);
        } finally {
            setValidatingId(null);
        }
    };

    const handleImport = async () => {
        let entries: CredentialProfileRequest[];
        try {
            const parsed = JSON.parse(importPayload);
            if (!Array.isArray(parsed)) {
                throw new Error('Import payload must be a JSON array');
            }
            entries = parsed;
        } catch (error) {
            console.error('Failed to parse import payload:', error);
            return;
        }

        setImporting(true);
        try {
            await importBootstrapCredentialProfiles(entries);
            setShowImportModal(false);
            await loadProfiles();
        } catch (error) {
            console.error('Failed to import credential profiles:', error);
        } finally {
            setImporting(false);
        }
    };

    const handleCmdbSync = async () => {
        setSyncingCmdb(true);
        try {
            const result = await syncCmdbCredentialProfiles();
            setCmdbSyncResult(result);
            await loadProfiles();
        } catch (error) {
            console.error('Failed to sync CMDB credential profiles:', error);
            setCmdbSyncResult({
                status: 'FAILURE',
                fetched: 0,
                created: 0,
                updated: 0,
                skipped: 0,
                message: error instanceof Error ? error.message : 'CMDB sync failed',
            });
        } finally {
            setSyncingCmdb(false);
        }
    };

    return (
        <div className="space-y-6">
            <header className="flex flex-col gap-4 lg:flex-row lg:items-center lg:justify-between">
                <div>
                    <h2 className="text-2xl font-bold text-white">Credential Profiles</h2>
                    <p className="text-gray-400 mt-1">Define first-claim matching rules and bind them to Redfish templates</p>
                </div>
                <div className="flex items-center gap-3">
                    <div className="px-4 py-2 rounded-lg bg-cyan-500/15 border border-cyan-500/30 text-cyan-300">
                        <span className="font-bold">{profiles.length}</span> profiles
                    </div>
                    <button
                        onClick={() => void handleCmdbSync()}
                        disabled={syncingCmdb}
                        className="inline-flex items-center gap-2 rounded-lg bg-emerald-500/15 px-4 py-2 text-emerald-200 hover:bg-emerald-500/25 disabled:opacity-50"
                    >
                        <RefreshCw size={16} className={syncingCmdb ? 'animate-spin' : ''} />
                        {syncingCmdb ? 'Syncing CMDB...' : 'Sync CMDB'}
                    </button>
                    <button
                        onClick={() => setShowImportModal(true)}
                        className="inline-flex items-center gap-2 rounded-lg bg-blue-500/15 px-4 py-2 text-blue-200 hover:bg-blue-500/25"
                    >
                        <Plus size={16} />
                        Import
                    </button>
                    <button
                        onClick={loadProfiles}
                        className="p-2 rounded-lg bg-white/5 border border-white/10 hover:bg-white/10 text-white transition-colors"
                        title="Refresh"
                    >
                        <RefreshCw size={18} className={loading ? 'animate-spin' : ''} />
                    </button>
                </div>
            </header>

            {cmdbSyncResult && (
                <GlassCard className={`border ${
                    cmdbSyncResult.status === 'SUCCESS'
                        ? 'border-emerald-500/30 bg-emerald-500/10'
                        : cmdbSyncResult.status === 'SKIPPED'
                            ? 'border-amber-500/30 bg-amber-500/10'
                            : 'border-red-500/30 bg-red-500/10'
                }`}>
                    <div className="flex flex-col gap-2 text-sm">
                        <div className="flex items-center gap-2">
                            {cmdbSyncResult.status === 'SUCCESS' ? <CheckCircle2 size={16} className="text-emerald-300" /> : <AlertTriangle size={16} className="text-amber-200" />}
                            <span className="font-medium text-white">{cmdbSyncResult.message}</span>
                        </div>
                        <div className="text-gray-300">
                            Fetched {cmdbSyncResult.fetched}, created {cmdbSyncResult.created}, updated {cmdbSyncResult.updated}, skipped {cmdbSyncResult.skipped}
                            {cmdbSyncResult.sourceType ? `, source ${cmdbSyncResult.sourceType}` : ''}
                            {cmdbSyncResult.endpoint ? `, endpoint ${cmdbSyncResult.endpoint}` : ''}
                        </div>
                    </div>
                </GlassCard>
            )}

            {showImportModal && (
                <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/60 p-4">
                    <GlassCard className="w-full max-w-3xl">
                        <div className="flex items-center justify-between gap-4">
                            <div>
                                <h3 className="text-lg font-semibold text-white">Import Credential Profiles</h3>
                                <p className="mt-1 text-sm text-gray-400">Paste a JSON array. Existing profiles are upserted by sourceType + externalRef, or by name when no externalRef exists.</p>
                            </div>
                            <button
                                onClick={() => setShowImportModal(false)}
                                className="rounded-lg bg-white/5 p-2 text-gray-300 hover:bg-white/10"
                            >
                                <X size={18} />
                            </button>
                        </div>

                        <textarea
                            value={importPayload}
                            onChange={(e) => setImportPayload(e.target.value)}
                            rows={18}
                            className="mt-4 w-full rounded-lg border border-white/10 bg-slate-950 px-4 py-3 font-mono text-sm text-white focus:outline-none focus:border-cyan-500"
                        />

                        <div className="mt-4 flex gap-3">
                            <button
                                onClick={() => setShowImportModal(false)}
                                className="inline-flex flex-1 items-center justify-center rounded-lg bg-white/5 px-4 py-3 text-gray-300 hover:bg-white/10"
                            >
                                Cancel
                            </button>
                            <button
                                onClick={() => void handleImport()}
                                disabled={importing}
                                className="inline-flex flex-1 items-center justify-center gap-2 rounded-lg bg-cyan-500 px-4 py-3 font-medium text-slate-950 hover:bg-cyan-400 disabled:opacity-50"
                            >
                                <Plus size={18} />
                                {importing ? 'Importing...' : 'Import Profiles'}
                            </button>
                        </div>
                    </GlassCard>
                </div>
            )}

            <div className="grid grid-cols-1 xl:grid-cols-[420px_1fr] gap-6">
                <GlassCard>
                        <div className="flex items-center gap-3 mb-5">
                            <div className="p-2 rounded-lg bg-cyan-500/20">
                                {editingId ? <Pencil size={18} className="text-cyan-400" /> : <Plus size={18} className="text-cyan-400" />}
                            </div>
                            <div>
                                <h3 className="text-lg font-semibold text-white">{editingId ? 'Edit Profile' : 'New Profile'}</h3>
                                <p className="text-sm text-gray-400">Profiles describe matching rules and secret references.</p>
                            </div>
                        </div>

                    <form onSubmit={handleSubmit} className="space-y-4">
                        <div>
                            <label className="block text-sm text-gray-400 mb-2">Name</label>
                            <input
                                type="text"
                                value={form.name}
                                onChange={(e) => setForm({ ...form, name: e.target.value })}
                                placeholder="rack-a-openbmc"
                                className="w-full px-4 py-2 rounded-lg bg-white/5 border border-white/10 text-white placeholder-gray-500 focus:outline-none focus:border-cyan-500"
                            />
                        </div>

                        <div className="grid grid-cols-2 gap-3">
                            <div>
                                <label className="block text-sm text-gray-400 mb-2">Protocol</label>
                                <input
                                    type="text"
                                    value={form.protocol}
                                    onChange={(e) => setForm({ ...form, protocol: e.target.value })}
                                    className="w-full px-4 py-2 rounded-lg bg-white/5 border border-white/10 text-white focus:outline-none focus:border-cyan-500"
                                />
                            </div>
                            <div>
                                <label className="block text-sm text-gray-400 mb-2">Priority</label>
                                <input
                                    type="number"
                                    value={form.priority}
                                    onChange={(e) => setForm({ ...form, priority: e.target.value })}
                                    className="w-full px-4 py-2 rounded-lg bg-white/5 border border-white/10 text-white focus:outline-none focus:border-cyan-500"
                                />
                            </div>
                        </div>

                        <div className="grid grid-cols-2 gap-3">
                            <div>
                                <label className="block text-sm text-gray-400 mb-2">Source Type</label>
                                <input
                                    type="text"
                                    value={form.sourceType}
                                    onChange={(e) => setForm({ ...form, sourceType: e.target.value })}
                                    placeholder="MANUAL|CMDB|DELIVERY_LEDGER"
                                    className="w-full px-4 py-2 rounded-lg bg-white/5 border border-white/10 text-white placeholder-gray-500 focus:outline-none focus:border-cyan-500"
                                />
                            </div>
                            <div>
                                <label className="block text-sm text-gray-400 mb-2">External Ref</label>
                                <input
                                    type="text"
                                    value={form.externalRef}
                                    onChange={(e) => setForm({ ...form, externalRef: e.target.value })}
                                    placeholder="asset-001"
                                    className="w-full px-4 py-2 rounded-lg bg-white/5 border border-white/10 text-white placeholder-gray-500 focus:outline-none focus:border-cyan-500"
                                />
                            </div>
                        </div>

                        <div className="grid grid-cols-2 gap-3">
                            <div>
                                <label className="block text-sm text-gray-400 mb-2">Vendor Pattern</label>
                                <input
                                    type="text"
                                    value={form.vendorPattern}
                                    onChange={(e) => setForm({ ...form, vendorPattern: e.target.value })}
                                    placeholder="OpenBMC|Dell"
                                    className="w-full px-4 py-2 rounded-lg bg-white/5 border border-white/10 text-white placeholder-gray-500 focus:outline-none focus:border-cyan-500"
                                />
                            </div>
                            <div>
                                <label className="block text-sm text-gray-400 mb-2">Model Pattern</label>
                                <input
                                    type="text"
                                    value={form.modelPattern}
                                    onChange={(e) => setForm({ ...form, modelPattern: e.target.value })}
                                    placeholder="R760|HGX"
                                    className="w-full px-4 py-2 rounded-lg bg-white/5 border border-white/10 text-white placeholder-gray-500 focus:outline-none focus:border-cyan-500"
                                />
                            </div>
                        </div>

                        <div className="grid grid-cols-2 gap-3">
                            <div>
                                <label className="block text-sm text-gray-400 mb-2">Subnet CIDR</label>
                                <input
                                    type="text"
                                    value={form.subnetCidr}
                                    onChange={(e) => setForm({ ...form, subnetCidr: e.target.value })}
                                    placeholder="10.10.0.0/24"
                                    className="w-full px-4 py-2 rounded-lg bg-white/5 border border-white/10 text-white placeholder-gray-500 focus:outline-none focus:border-cyan-500"
                                />
                            </div>
                            <div>
                                <label className="block text-sm text-gray-400 mb-2">Device Type</label>
                                <input
                                    type="text"
                                    value={form.deviceType}
                                    onChange={(e) => setForm({ ...form, deviceType: e.target.value })}
                                    className="w-full px-4 py-2 rounded-lg bg-white/5 border border-white/10 text-white focus:outline-none focus:border-cyan-500"
                                />
                            </div>
                        </div>

                        <div className="grid grid-cols-1 gap-3">
                            <div>
                                <label className="block text-sm text-gray-400 mb-2">Hostname Pattern</label>
                                <input
                                    type="text"
                                    value={form.hostnamePattern}
                                    onChange={(e) => setForm({ ...form, hostnamePattern: e.target.value })}
                                    placeholder="bmc-rack-a-.*"
                                    className="w-full px-4 py-2 rounded-lg bg-white/5 border border-white/10 text-white placeholder-gray-500 focus:outline-none focus:border-cyan-500"
                                />
                            </div>
                            <div>
                                <label className="block text-sm text-gray-400 mb-2">IP Address Pattern</label>
                                <input
                                    type="text"
                                    value={form.ipAddressPattern}
                                    onChange={(e) => setForm({ ...form, ipAddressPattern: e.target.value })}
                                    placeholder="^10\\.10\\.0\\.50$"
                                    className="w-full px-4 py-2 rounded-lg bg-white/5 border border-white/10 text-white placeholder-gray-500 focus:outline-none focus:border-cyan-500"
                                />
                            </div>
                            <div>
                                <label className="block text-sm text-gray-400 mb-2">MAC Address Pattern</label>
                                <input
                                    type="text"
                                    value={form.macAddressPattern}
                                    onChange={(e) => setForm({ ...form, macAddressPattern: e.target.value })}
                                    placeholder="^00:11:22:33:44:55$"
                                    className="w-full px-4 py-2 rounded-lg bg-white/5 border border-white/10 text-white placeholder-gray-500 focus:outline-none focus:border-cyan-500"
                                />
                            </div>
                        </div>

                        <div>
                            <label className="block text-sm text-gray-400 mb-2">Redfish Template</label>
                            <input
                                type="text"
                                value={form.redfishTemplate}
                                onChange={(e) => setForm({ ...form, redfishTemplate: e.target.value })}
                                placeholder="openbmc-baseline"
                                list="redfish-template-options"
                                className="w-full px-4 py-2 rounded-lg bg-white/5 border border-white/10 text-white placeholder-gray-500 focus:outline-none focus:border-cyan-500"
                            />
                            <datalist id="redfish-template-options">
                                {templates.map((template) => (
                                    <option key={template.name} value={template.name}>
                                        {template.description}
                                    </option>
                                ))}
                            </datalist>
                            {templates.length > 0 && (
                                <div className="mt-2 flex flex-wrap gap-2">
                                    {templates.map((template) => (
                                        <button
                                            key={template.name}
                                            type="button"
                                            onClick={() => setForm({ ...form, redfishTemplate: template.name })}
                                            className={`rounded-full border px-2.5 py-1 text-xs transition-colors ${
                                                form.redfishTemplate === template.name
                                                    ? 'border-cyan-400/50 bg-cyan-400/15 text-cyan-200'
                                                    : 'border-white/10 bg-white/5 text-gray-300 hover:bg-white/10'
                                            }`}
                                        >
                                            {template.name}
                                        </button>
                                    ))}
                                </div>
                            )}
                        </div>

                        <div className="grid grid-cols-1 gap-3">
                            <div>
                                <label className="block text-sm text-gray-400 mb-2">Username Secret Ref</label>
                                <input
                                    type="text"
                                    value={form.usernameSecretRef}
                                    onChange={(e) => setForm({ ...form, usernameSecretRef: e.target.value })}
                                    placeholder="vault://bmc/rack-a#username"
                                    className="w-full px-4 py-2 rounded-lg bg-white/5 border border-white/10 text-white placeholder-gray-500 focus:outline-none focus:border-cyan-500"
                                />
                            </div>
                            <div>
                                <label className="block text-sm text-gray-400 mb-2">Password Secret Ref</label>
                                <input
                                    type="text"
                                    value={form.passwordSecretRef}
                                    onChange={(e) => setForm({ ...form, passwordSecretRef: e.target.value })}
                                    placeholder="vault://bmc/rack-a#password"
                                    className="w-full px-4 py-2 rounded-lg bg-white/5 border border-white/10 text-white placeholder-gray-500 focus:outline-none focus:border-cyan-500"
                                />
                            </div>
                        </div>
                        <p className="text-xs text-gray-500">
                            Supported secret refs: <span className="font-mono">vault://mount/path#field</span> and <span className="font-mono">env://LCM_BMC_*</span>.
                            <span className="ml-1">Use <span className="font-mono">literal://</span> only for local dev/test.</span>
                        </p>

                        <div className="rounded-xl border border-white/10 bg-white/[0.03] p-4">
                            <div className="flex items-center justify-between gap-4">
                                <div>
                                    <div className="text-sm font-medium text-white">Managed BMC Account</div>
                                    <p className="mt-1 text-xs text-gray-400">Create or rotate a platform-owned Redfish account after bootstrap claim succeeds.</p>
                                </div>
                                <label className="inline-flex items-center gap-2 text-sm text-gray-300">
                                    <input
                                        type="checkbox"
                                        checked={form.managedAccountEnabled}
                                        onChange={(e) => setForm({ ...form, managedAccountEnabled: e.target.checked })}
                                        className="rounded border-white/10 bg-white/5"
                                    />
                                    Enable
                                </label>
                            </div>

                            {form.managedAccountEnabled && (
                                <div className="mt-4 grid grid-cols-1 gap-3">
                                    <div>
                                        <label className="block text-sm text-gray-400 mb-2">Managed Username Secret Ref</label>
                                        <input
                                            type="text"
                                            value={form.managedUsernameSecretRef}
                                            onChange={(e) => setForm({ ...form, managedUsernameSecretRef: e.target.value })}
                                            placeholder="vault://bmc/rack-a-managed#username"
                                            className="w-full px-4 py-2 rounded-lg bg-white/5 border border-white/10 text-white placeholder-gray-500 focus:outline-none focus:border-cyan-500"
                                        />
                                    </div>
                                    <div>
                                        <label className="block text-sm text-gray-400 mb-2">Managed Password Secret Ref</label>
                                        <input
                                            type="text"
                                            value={form.managedPasswordSecretRef}
                                            onChange={(e) => setForm({ ...form, managedPasswordSecretRef: e.target.value })}
                                            placeholder="vault://bmc/rack-a-managed#password"
                                            className="w-full px-4 py-2 rounded-lg bg-white/5 border border-white/10 text-white placeholder-gray-500 focus:outline-none focus:border-cyan-500"
                                        />
                                    </div>
                                    <div>
                                        <label className="block text-sm text-gray-400 mb-2">Managed Account Role</label>
                                        <input
                                            type="text"
                                            value={form.managedAccountRoleId}
                                            onChange={(e) => setForm({ ...form, managedAccountRoleId: e.target.value })}
                                            placeholder="Administrator"
                                            className="w-full px-4 py-2 rounded-lg bg-white/5 border border-white/10 text-white placeholder-gray-500 focus:outline-none focus:border-cyan-500"
                                        />
                                    </div>
                                </div>
                            )}
                        </div>

                        <div>
                            <label className="block text-sm text-gray-400 mb-2">Description</label>
                            <textarea
                                value={form.description}
                                onChange={(e) => setForm({ ...form, description: e.target.value })}
                                rows={3}
                                placeholder="Used for rack A OpenBMC nodes delivered in batch 2026-Q1."
                                className="w-full px-4 py-2 rounded-lg bg-white/5 border border-white/10 text-white placeholder-gray-500 focus:outline-none focus:border-cyan-500"
                            />
                        </div>

                        <div className="flex items-center gap-6 text-sm text-gray-300">
                            <label className="inline-flex items-center gap-2">
                                <input
                                    type="checkbox"
                                    checked={form.enabled}
                                    onChange={(e) => setForm({ ...form, enabled: e.target.checked })}
                                    className="rounded border-white/10 bg-white/5"
                                />
                                Enabled
                            </label>
                            <label className="inline-flex items-center gap-2">
                                <input
                                    type="checkbox"
                                    checked={form.autoClaim}
                                    onChange={(e) => setForm({ ...form, autoClaim: e.target.checked })}
                                    className="rounded border-white/10 bg-white/5"
                                />
                                Auto Claim
                            </label>
                        </div>

                        <div className="flex gap-3">
                            {editingId && (
                                <button
                                    type="button"
                                    onClick={resetForm}
                                    className="inline-flex flex-1 items-center justify-center gap-2 rounded-lg bg-white/5 px-4 py-3 text-gray-300 hover:bg-white/10"
                                >
                                    <X size={18} />
                                    Cancel Edit
                                </button>
                            )}
                            <button
                                type="submit"
                                disabled={submitting || !form.name.trim()}
                                className="inline-flex flex-1 items-center justify-center gap-2 rounded-lg bg-cyan-500 px-4 py-3 font-medium text-slate-950 hover:bg-cyan-400 disabled:opacity-50"
                            >
                                {editingId ? <Pencil size={18} /> : <Plus size={18} />}
                                {editingId ? 'Update Profile' : 'Create Profile'}
                            </button>
                        </div>
                    </form>
                </GlassCard>

                <div className="space-y-4">
                    {profiles.length === 0 ? (
                        <GlassCard className="py-12 text-center">
                            <Database size={48} className="mx-auto mb-4 text-gray-500 opacity-30" />
                            <p className="text-gray-500">No credential profiles configured</p>
                            <p className="text-sm text-gray-600 mt-2">Create one to let claim planning match BMC bootstrap credentials.</p>
                        </GlassCard>
                    ) : (
                        profiles.map((profile) => (
                            <GlassCard key={profile.id}>
                                <div className="flex flex-col gap-4 lg:flex-row lg:items-start lg:justify-between">
                                    <div className="space-y-3">
                                        <div className="flex flex-wrap items-center gap-2">
                                            <h3 className="text-lg font-semibold text-white">{profile.name}</h3>
                                            <span className={`px-2.5 py-0.5 rounded-full text-xs ${profile.enabled ? 'bg-emerald-500/10 text-emerald-300' : 'bg-white/5 text-gray-400'}`}>
                                                {profile.enabled ? 'Enabled' : 'Disabled'}
                                            </span>
                                            <span className={`px-2.5 py-0.5 rounded-full text-xs ${profile.autoClaim ? 'bg-blue-500/10 text-blue-300' : 'bg-white/5 text-gray-400'}`}>
                                                {profile.autoClaim ? 'Auto Claim' : 'Manual Claim'}
                                            </span>
                                            {profile.sourceType && (
                                                <span className="px-2.5 py-0.5 rounded-full text-xs bg-white/5 text-gray-300">
                                                    {profile.sourceType}
                                                </span>
                                            )}
                                            {profile.managedAccountEnabled && (
                                                <span className="px-2.5 py-0.5 rounded-full text-xs bg-amber-500/10 text-amber-300">
                                                    Managed Account
                                                </span>
                                            )}
                                        </div>

                                        <div className="flex flex-wrap gap-2 text-xs">
                                            <span className="px-2.5 py-0.5 rounded-full bg-white/5 text-gray-300">
                                                Priority {profile.priority}
                                            </span>
                                            <span className="px-2.5 py-0.5 rounded-full bg-white/5 text-gray-300">
                                                {profile.protocol}
                                            </span>
                                            {profile.redfishTemplate && (
                                                <span className="inline-flex items-center gap-1 px-2.5 py-0.5 rounded-full bg-blue-500/10 text-blue-300">
                                                    <Shield size={12} />
                                                    {profile.redfishTemplate}
                                                </span>
                                            )}
                                        </div>

                                        <div className="grid grid-cols-1 md:grid-cols-2 gap-3 text-sm text-gray-300">
                                            <div>
                                                <div className="text-gray-500 text-xs uppercase tracking-wide mb-1">Matching</div>
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
                                                <div className="text-gray-500 text-xs uppercase tracking-wide mb-1">Secret Refs</div>
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
                                                        <div className="mt-2 text-gray-500 text-xs uppercase tracking-wide">Managed Account</div>
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

                                        {validationResults[profile.id] && (
                                            <div className={`rounded-lg border px-4 py-3 text-sm ${
                                                validationResults[profile.id].ready
                                                    ? 'border-emerald-500/30 bg-emerald-500/10 text-emerald-200'
                                                    : 'border-amber-500/30 bg-amber-500/10 text-amber-100'
                                            }`}>
                                                <div className="flex items-center gap-2 font-medium">
                                                    {validationResults[profile.id].ready ? <CheckCircle2 size={16} /> : <AlertTriangle size={16} />}
                                                    {validationResults[profile.id].ready ? 'Ready to claim' : 'Validation needs attention'}
                                                </div>
                                                <p className="mt-2 text-sm">{validationResults[profile.id].message}</p>
                                                <div className="mt-2 text-xs opacity-90">
                                                    <div>Username: {validationResults[profile.id].usernameMessage}</div>
                                                    <div>Password: {validationResults[profile.id].passwordMessage}</div>
                                                    {validationResults[profile.id].credentialSource && (
                                                        <div>Source: {validationResults[profile.id].credentialSource}</div>
                                                    )}
                                                    {validationResults[profile.id].managedAccountEnabled && (
                                                        <>
                                                            <div className="mt-2 font-medium">Managed Account: {validationResults[profile.id].managedAccountMessage}</div>
                                                            <div>Managed Username: {validationResults[profile.id].managedUsernameMessage}</div>
                                                            <div>Managed Password: {validationResults[profile.id].managedPasswordMessage}</div>
                                                        </>
                                                    )}
                                                </div>
                                            </div>
                                        )}
                                    </div>

                                    <div className="flex flex-wrap gap-2">
                                        <button
                                            onClick={() => void handleValidate(profile.id)}
                                            disabled={validatingId === profile.id}
                                            className="inline-flex items-center justify-center gap-2 rounded-lg bg-cyan-500/15 px-3 py-2 text-cyan-200 hover:bg-cyan-500/25 disabled:opacity-50"
                                            title="Validate profile secret refs"
                                        >
                                            <RefreshCw size={16} className={validatingId === profile.id ? 'animate-spin' : ''} />
                                            Validate
                                        </button>
                                        <button
                                            onClick={() => handleEdit(profile)}
                                            className="inline-flex items-center justify-center gap-2 rounded-lg bg-white/5 px-3 py-2 text-gray-200 hover:bg-white/10"
                                            title="Edit profile"
                                        >
                                            <Pencil size={16} />
                                            Edit
                                        </button>
                                        <button
                                            onClick={() => void handleDelete(profile.id)}
                                            disabled={deletingId === profile.id}
                                            className="inline-flex items-center justify-center gap-2 rounded-lg bg-red-500/15 px-3 py-2 text-red-300 hover:bg-red-500/25 disabled:opacity-50"
                                            title="Delete profile"
                                        >
                                            <Trash2 size={16} />
                                            Delete
                                        </button>
                                    </div>
                                </div>
                            </GlassCard>
                        ))
                    )}
                </div>
            </div>
        </div>
    );
};
