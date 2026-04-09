import React from 'react';
import { Pencil, Plus, X } from 'lucide-react';
import { GlassCard } from '../GlassCard';
import type { RedfishTemplateSummary } from '../../api/client';
import type { CredentialProfileFormState } from './formState';

interface CredentialProfileFormProps {
    editingId: string | null;
    form: CredentialProfileFormState;
    setForm: React.Dispatch<React.SetStateAction<CredentialProfileFormState>>;
    templates: RedfishTemplateSummary[];
    submitting: boolean;
    onSubmit: (event: React.FormEvent<HTMLFormElement>) => void;
    onReset: () => void;
}

export const CredentialProfileForm: React.FC<CredentialProfileFormProps> = ({
    editingId,
    form,
    setForm,
    templates,
    submitting,
    onSubmit,
    onReset,
}) => {
    const updateField = <K extends keyof CredentialProfileFormState>(field: K, value: CredentialProfileFormState[K]) => {
        setForm((current) => ({ ...current, [field]: value }));
    };

    return (
        <GlassCard>
            <div className="mb-5 flex items-center gap-3">
                <div className="rounded-lg bg-cyan-500/20 p-2">
                    {editingId ? <Pencil size={18} className="text-cyan-400" /> : <Plus size={18} className="text-cyan-400" />}
                </div>
                <div>
                    <h3 className="text-lg font-semibold text-white">{editingId ? 'Edit Profile' : 'New Profile'}</h3>
                    <p className="text-sm text-gray-400">Profiles describe matching rules and secret references.</p>
                </div>
            </div>

            <form onSubmit={onSubmit} className="space-y-4">
                <div>
                    <label className="mb-2 block text-sm text-gray-400">Name</label>
                    <input
                        type="text"
                        value={form.name}
                        onChange={(event) => updateField('name', event.target.value)}
                        placeholder="rack-a-openbmc"
                        className="w-full rounded-lg border border-white/10 bg-white/5 px-4 py-2 text-white placeholder-gray-500 focus:border-cyan-500 focus:outline-none"
                    />
                </div>

                <div className="grid grid-cols-2 gap-3">
                    <div>
                        <label className="mb-2 block text-sm text-gray-400">Protocol</label>
                        <input
                            type="text"
                            value={form.protocol}
                            onChange={(event) => updateField('protocol', event.target.value)}
                            className="w-full rounded-lg border border-white/10 bg-white/5 px-4 py-2 text-white focus:border-cyan-500 focus:outline-none"
                        />
                    </div>
                    <div>
                        <label className="mb-2 block text-sm text-gray-400">Priority</label>
                        <input
                            type="number"
                            value={form.priority}
                            onChange={(event) => updateField('priority', event.target.value)}
                            className="w-full rounded-lg border border-white/10 bg-white/5 px-4 py-2 text-white focus:border-cyan-500 focus:outline-none"
                        />
                    </div>
                </div>

                <div className="grid grid-cols-2 gap-3">
                    <div>
                        <label className="mb-2 block text-sm text-gray-400">Source Type</label>
                        <input
                            type="text"
                            value={form.sourceType}
                            onChange={(event) => updateField('sourceType', event.target.value)}
                            placeholder="MANUAL|CMDB|DELIVERY_LEDGER"
                            className="w-full rounded-lg border border-white/10 bg-white/5 px-4 py-2 text-white placeholder-gray-500 focus:border-cyan-500 focus:outline-none"
                        />
                    </div>
                    <div>
                        <label className="mb-2 block text-sm text-gray-400">External Ref</label>
                        <input
                            type="text"
                            value={form.externalRef}
                            onChange={(event) => updateField('externalRef', event.target.value)}
                            placeholder="asset-001"
                            className="w-full rounded-lg border border-white/10 bg-white/5 px-4 py-2 text-white placeholder-gray-500 focus:border-cyan-500 focus:outline-none"
                        />
                    </div>
                </div>

                <div className="grid grid-cols-2 gap-3">
                    <div>
                        <label className="mb-2 block text-sm text-gray-400">Vendor Pattern</label>
                        <input
                            type="text"
                            value={form.vendorPattern}
                            onChange={(event) => updateField('vendorPattern', event.target.value)}
                            placeholder="OpenBMC|Dell"
                            className="w-full rounded-lg border border-white/10 bg-white/5 px-4 py-2 text-white placeholder-gray-500 focus:border-cyan-500 focus:outline-none"
                        />
                    </div>
                    <div>
                        <label className="mb-2 block text-sm text-gray-400">Model Pattern</label>
                        <input
                            type="text"
                            value={form.modelPattern}
                            onChange={(event) => updateField('modelPattern', event.target.value)}
                            placeholder="R760|HGX"
                            className="w-full rounded-lg border border-white/10 bg-white/5 px-4 py-2 text-white placeholder-gray-500 focus:border-cyan-500 focus:outline-none"
                        />
                    </div>
                </div>

                <div className="grid grid-cols-2 gap-3">
                    <div>
                        <label className="mb-2 block text-sm text-gray-400">Subnet CIDR</label>
                        <input
                            type="text"
                            value={form.subnetCidr}
                            onChange={(event) => updateField('subnetCidr', event.target.value)}
                            placeholder="10.10.0.0/24"
                            className="w-full rounded-lg border border-white/10 bg-white/5 px-4 py-2 text-white placeholder-gray-500 focus:border-cyan-500 focus:outline-none"
                        />
                    </div>
                    <div>
                        <label className="mb-2 block text-sm text-gray-400">Device Type</label>
                        <input
                            type="text"
                            value={form.deviceType}
                            onChange={(event) => updateField('deviceType', event.target.value)}
                            className="w-full rounded-lg border border-white/10 bg-white/5 px-4 py-2 text-white focus:border-cyan-500 focus:outline-none"
                        />
                    </div>
                </div>

                <div className="grid grid-cols-1 gap-3">
                    <div>
                        <label className="mb-2 block text-sm text-gray-400">Hostname Pattern</label>
                        <input
                            type="text"
                            value={form.hostnamePattern}
                            onChange={(event) => updateField('hostnamePattern', event.target.value)}
                            placeholder="bmc-rack-a-.*"
                            className="w-full rounded-lg border border-white/10 bg-white/5 px-4 py-2 text-white placeholder-gray-500 focus:border-cyan-500 focus:outline-none"
                        />
                    </div>
                    <div>
                        <label className="mb-2 block text-sm text-gray-400">IP Address Pattern</label>
                        <input
                            type="text"
                            value={form.ipAddressPattern}
                            onChange={(event) => updateField('ipAddressPattern', event.target.value)}
                            placeholder="^10\\.10\\.0\\.50$"
                            className="w-full rounded-lg border border-white/10 bg-white/5 px-4 py-2 text-white placeholder-gray-500 focus:border-cyan-500 focus:outline-none"
                        />
                    </div>
                    <div>
                        <label className="mb-2 block text-sm text-gray-400">MAC Address Pattern</label>
                        <input
                            type="text"
                            value={form.macAddressPattern}
                            onChange={(event) => updateField('macAddressPattern', event.target.value)}
                            placeholder="^00:11:22:33:44:55$"
                            className="w-full rounded-lg border border-white/10 bg-white/5 px-4 py-2 text-white placeholder-gray-500 focus:border-cyan-500 focus:outline-none"
                        />
                    </div>
                </div>

                <div>
                    <label className="mb-2 block text-sm text-gray-400">Redfish Template</label>
                    <input
                        type="text"
                        value={form.redfishTemplate}
                        onChange={(event) => updateField('redfishTemplate', event.target.value)}
                        placeholder="openbmc-baseline"
                        list="redfish-template-options"
                        className="w-full rounded-lg border border-white/10 bg-white/5 px-4 py-2 text-white placeholder-gray-500 focus:border-cyan-500 focus:outline-none"
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
                                    onClick={() => updateField('redfishTemplate', template.name)}
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
                        <label className="mb-2 block text-sm text-gray-400">Username Secret Ref</label>
                        <input
                            type="text"
                            value={form.usernameSecretRef}
                            onChange={(event) => updateField('usernameSecretRef', event.target.value)}
                            placeholder="vault://bmc/rack-a#username"
                            className="w-full rounded-lg border border-white/10 bg-white/5 px-4 py-2 text-white placeholder-gray-500 focus:border-cyan-500 focus:outline-none"
                        />
                    </div>
                    <div>
                        <label className="mb-2 block text-sm text-gray-400">Password Secret Ref</label>
                        <input
                            type="text"
                            value={form.passwordSecretRef}
                            onChange={(event) => updateField('passwordSecretRef', event.target.value)}
                            placeholder="vault://bmc/rack-a#password"
                            className="w-full rounded-lg border border-white/10 bg-white/5 px-4 py-2 text-white placeholder-gray-500 focus:border-cyan-500 focus:outline-none"
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
                                onChange={(event) => updateField('managedAccountEnabled', event.target.checked)}
                                className="rounded border-white/10 bg-white/5"
                            />
                            Enable
                        </label>
                    </div>

                    {form.managedAccountEnabled && (
                        <div className="mt-4 grid grid-cols-1 gap-3">
                            <div>
                                <label className="mb-2 block text-sm text-gray-400">Managed Username Secret Ref</label>
                                <input
                                    type="text"
                                    value={form.managedUsernameSecretRef}
                                    onChange={(event) => updateField('managedUsernameSecretRef', event.target.value)}
                                    placeholder="vault://bmc/rack-a-managed#username"
                                    className="w-full rounded-lg border border-white/10 bg-white/5 px-4 py-2 text-white placeholder-gray-500 focus:border-cyan-500 focus:outline-none"
                                />
                            </div>
                            <div>
                                <label className="mb-2 block text-sm text-gray-400">Managed Password Secret Ref</label>
                                <input
                                    type="text"
                                    value={form.managedPasswordSecretRef}
                                    onChange={(event) => updateField('managedPasswordSecretRef', event.target.value)}
                                    placeholder="vault://bmc/rack-a-managed#password"
                                    className="w-full rounded-lg border border-white/10 bg-white/5 px-4 py-2 text-white placeholder-gray-500 focus:border-cyan-500 focus:outline-none"
                                />
                            </div>
                            <div>
                                <label className="mb-2 block text-sm text-gray-400">Managed Account Role</label>
                                <input
                                    type="text"
                                    value={form.managedAccountRoleId}
                                    onChange={(event) => updateField('managedAccountRoleId', event.target.value)}
                                    placeholder="Administrator"
                                    className="w-full rounded-lg border border-white/10 bg-white/5 px-4 py-2 text-white placeholder-gray-500 focus:border-cyan-500 focus:outline-none"
                                />
                            </div>
                        </div>
                    )}
                </div>

                <div>
                    <label className="mb-2 block text-sm text-gray-400">Description</label>
                    <textarea
                        value={form.description}
                        onChange={(event) => updateField('description', event.target.value)}
                        rows={3}
                        placeholder="Used for rack A OpenBMC nodes delivered in batch 2026-Q1."
                        className="w-full rounded-lg border border-white/10 bg-white/5 px-4 py-2 text-white placeholder-gray-500 focus:border-cyan-500 focus:outline-none"
                    />
                </div>

                <div className="flex items-center gap-6 text-sm text-gray-300">
                    <label className="inline-flex items-center gap-2">
                        <input
                            type="checkbox"
                            checked={form.enabled}
                            onChange={(event) => updateField('enabled', event.target.checked)}
                            className="rounded border-white/10 bg-white/5"
                        />
                        Enabled
                    </label>
                    <label className="inline-flex items-center gap-2">
                        <input
                            type="checkbox"
                            checked={form.autoClaim}
                            onChange={(event) => updateField('autoClaim', event.target.checked)}
                            className="rounded border-white/10 bg-white/5"
                        />
                        Auto Claim
                    </label>
                </div>

                <div className="flex gap-3">
                    {editingId && (
                        <button
                            type="button"
                            onClick={onReset}
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
    );
};
