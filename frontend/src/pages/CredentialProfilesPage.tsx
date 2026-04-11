import React, { useEffect, useState } from 'react';
import { AlertTriangle, CheckCircle2, Plus, RefreshCw, X } from 'lucide-react';
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
import { GlassCard } from '../components/GlassCard';
import { CredentialProfileForm } from '../components/credential-profiles/CredentialProfileForm';
import { CredentialProfileList } from '../components/credential-profiles/CredentialProfileList';
import {
    type CredentialProfileFormState,
    createEmptyCredentialProfileForm,
    toCredentialProfileFormState,
    toCredentialProfileRequest,
} from '../components/credential-profiles/formState';

export const CredentialProfilesPage: React.FC = () => {
    const [profiles, setProfiles] = useState<CredentialProfile[]>([]);
    const [templates, setTemplates] = useState<RedfishTemplateSummary[]>([]);
    const [loading, setLoading] = useState(true);
    const [submitting, setSubmitting] = useState(false);
    const [deletingId, setDeletingId] = useState<string | null>(null);
    const [form, setForm] = useState<CredentialProfileFormState>(createEmptyCredentialProfileForm());
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
    "redfishAuthMode": "SESSION_PREFERRED",
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
        void loadProfiles();
    }, []);

    const handleSubmit = async (event: React.FormEvent<HTMLFormElement>) => {
        event.preventDefault();
        if (!form.name.trim()) {
            return;
        }

        const payload = toCredentialProfileRequest(form);

        setSubmitting(true);
        try {
            if (editingId) {
                await updateCredentialProfile(editingId, payload);
            } else {
                await createCredentialProfile(payload);
            }
            setEditingId(null);
            setForm(createEmptyCredentialProfileForm());
            await loadProfiles();
        } catch (error) {
            console.error(`Failed to ${editingId ? 'update' : 'create'} credential profile:`, error);
        } finally {
            setSubmitting(false);
        }
    };

    const handleEdit = (profile: CredentialProfile) => {
        setEditingId(profile.id);
        setForm(toCredentialProfileFormState(profile));
    };

    const resetForm = () => {
        setEditingId(null);
        setForm(createEmptyCredentialProfileForm());
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
                    <p className="mt-1 text-gray-400">Define first-claim matching rules and bind them to Redfish templates</p>
                </div>
                <div className="flex items-center gap-3">
                    <div className="rounded-lg border border-cyan-500/30 bg-cyan-500/15 px-4 py-2 text-cyan-300">
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
                        onClick={() => void loadProfiles()}
                        className="rounded-lg border border-white/10 bg-white/5 p-2 text-white transition-colors hover:bg-white/10"
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
                            onChange={(event) => setImportPayload(event.target.value)}
                            rows={18}
                            className="mt-4 w-full rounded-lg border border-white/10 bg-slate-950 px-4 py-3 font-mono text-sm text-white focus:border-cyan-500 focus:outline-none"
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

            <div className="grid grid-cols-1 gap-6 xl:grid-cols-[420px_1fr]">
                <CredentialProfileForm
                    editingId={editingId}
                    form={form}
                    setForm={setForm}
                    templates={templates}
                    submitting={submitting}
                    onSubmit={handleSubmit}
                    onReset={resetForm}
                />
                <CredentialProfileList
                    profiles={profiles}
                    editingId={editingId}
                    deletingId={deletingId}
                    validatingId={validatingId}
                    validationResults={validationResults}
                    onEdit={handleEdit}
                    onDelete={(id) => void handleDelete(id)}
                    onValidate={(id) => void handleValidate(id)}
                />
            </div>
        </div>
    );
};
