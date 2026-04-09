import React from 'react';
import { Database } from 'lucide-react';
import type { CredentialProfile, CredentialProfileValidation } from '../../api/client';
import { GlassCard } from '../GlassCard';
import { CredentialProfileDetail } from './CredentialProfileDetail';

interface CredentialProfileListProps {
    profiles: CredentialProfile[];
    editingId: string | null;
    deletingId: string | null;
    validatingId: string | null;
    validationResults: Record<string, CredentialProfileValidation>;
    onEdit: (profile: CredentialProfile) => void;
    onDelete: (id: string) => void;
    onValidate: (id: string) => void;
}

export const CredentialProfileList: React.FC<CredentialProfileListProps> = ({
    profiles,
    editingId,
    deletingId,
    validatingId,
    validationResults,
    onEdit,
    onDelete,
    onValidate,
}) => {
    if (profiles.length === 0) {
        return (
            <GlassCard className="py-12 text-center">
                <Database size={48} className="mx-auto mb-4 text-gray-500 opacity-30" />
                <p className="text-gray-500">No credential profiles configured</p>
                <p className="mt-2 text-sm text-gray-600">Create one to let claim planning match BMC bootstrap credentials.</p>
            </GlassCard>
        );
    }

    return (
        <div className="space-y-4">
            {profiles.map((profile) => (
                <CredentialProfileDetail
                    key={profile.id}
                    profile={profile}
                    validation={validationResults[profile.id]}
                    isEditing={editingId === profile.id}
                    isDeleting={deletingId === profile.id}
                    isValidating={validatingId === profile.id}
                    onEdit={onEdit}
                    onDelete={onDelete}
                    onValidate={onValidate}
                />
            ))}
        </div>
    );
};
