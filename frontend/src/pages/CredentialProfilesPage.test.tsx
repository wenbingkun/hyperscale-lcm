import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { CredentialProfilesPage } from './CredentialProfilesPage';
import {
    createCredentialProfile,
    deleteCredentialProfile,
    fetchCredentialProfiles,
    fetchRedfishTemplates,
    importBootstrapCredentialProfiles,
    syncCmdbCredentialProfiles,
    updateCredentialProfile,
    validateCredentialProfile,
} from '../api/client';

vi.mock('../api/client', async () => {
    const actual = await vi.importActual<typeof import('../api/client')>('../api/client');
    return {
        ...actual,
        createCredentialProfile: vi.fn(),
        deleteCredentialProfile: vi.fn(),
        fetchCredentialProfiles: vi.fn(),
        fetchRedfishTemplates: vi.fn(),
        importBootstrapCredentialProfiles: vi.fn(),
        syncCmdbCredentialProfiles: vi.fn(),
        updateCredentialProfile: vi.fn(),
        validateCredentialProfile: vi.fn(),
    };
});

const mockedCreateCredentialProfile = vi.mocked(createCredentialProfile);
const mockedDeleteCredentialProfile = vi.mocked(deleteCredentialProfile);
const mockedFetchCredentialProfiles = vi.mocked(fetchCredentialProfiles);
const mockedFetchRedfishTemplates = vi.mocked(fetchRedfishTemplates);
const mockedImportBootstrapCredentialProfiles = vi.mocked(importBootstrapCredentialProfiles);
const mockedSyncCmdbCredentialProfiles = vi.mocked(syncCmdbCredentialProfiles);
const mockedUpdateCredentialProfile = vi.mocked(updateCredentialProfile);
const mockedValidateCredentialProfile = vi.mocked(validateCredentialProfile);

const profiles = [
    {
        id: 'profile-1',
        name: 'rack-a-openbmc',
        protocol: 'REDFISH',
        enabled: true,
        autoClaim: true,
        priority: 1000,
        sourceType: 'CMDB',
        externalRef: 'asset-001',
        vendorPattern: 'OpenBMC',
        modelPattern: 'AST2600',
        subnetCidr: '10.10.0.0/24',
        deviceType: 'BMC_ENABLED',
        hostnamePattern: 'bmc-rack-a-.*',
        ipAddressPattern: '^10\\.10\\.0\\.50$',
        macAddressPattern: '^00:11:22:33:44:55$',
        redfishTemplate: 'openbmc-baseline',
        redfishAuthMode: 'SESSION_PREFERRED',
        usernameSecretRef: 'vault://bmc/rack-a#username',
        passwordSecretRef: 'vault://bmc/rack-a#password',
        managedAccountEnabled: true,
        managedUsernameSecretRef: 'vault://bmc/rack-a-managed#username',
        managedPasswordSecretRef: 'vault://bmc/rack-a-managed#password',
        managedAccountRoleId: 'Administrator',
        description: 'Rack A bootstrap profile',
    },
];

describe('CredentialProfilesPage', () => {
    beforeEach(() => {
        mockedFetchCredentialProfiles.mockResolvedValue(profiles);
        mockedFetchRedfishTemplates.mockResolvedValue([
            {
                name: 'openbmc-baseline',
                description: 'OpenBMC baseline',
                priority: 100,
                manufacturerPatterns: ['OpenBMC'],
                modelPatterns: ['AST2600'],
                source: 'documentation',
            },
        ]);
        mockedValidateCredentialProfile.mockResolvedValue({
            id: 'profile-1',
            name: 'rack-a-openbmc',
            ready: true,
            credentialSource: 'vault',
            message: 'Bootstrap and managed credentials are ready.',
            usernameReady: true,
            usernameMessage: 'Username ref resolved',
            passwordReady: true,
            passwordMessage: 'Password ref resolved',
            managedAccountEnabled: true,
            managedAccountReady: true,
            managedAccountMessage: 'Managed account refs resolved',
            managedUsernameReady: true,
            managedUsernameMessage: 'Managed username ref resolved',
            managedPasswordReady: true,
            managedPasswordMessage: 'Managed password ref resolved',
        });
        mockedCreateCredentialProfile.mockResolvedValue(undefined);
        mockedUpdateCredentialProfile.mockResolvedValue(undefined);
        mockedDeleteCredentialProfile.mockResolvedValue(undefined);
        mockedImportBootstrapCredentialProfiles.mockResolvedValue({ created: 0, updated: 0, skipped: 0, results: [] });
        mockedSyncCmdbCredentialProfiles.mockResolvedValue({
            status: 'SUCCESS',
            fetched: 1,
            created: 0,
            updated: 1,
            skipped: 0,
            message: 'Synced from CMDB',
        });
    });

    it('loads credential profiles and wires edit plus validation actions through the split components', async () => {
        const user = userEvent.setup();

        render(<CredentialProfilesPage />);

        expect(await screen.findByText('rack-a-openbmc')).toBeInTheDocument();
        expect(screen.getByText('Rack A bootstrap profile')).toBeInTheDocument();

        await user.click(screen.getByRole('button', { name: /Edit/i }));
        await waitFor(() => expect(screen.getByDisplayValue('rack-a-openbmc')).toBeInTheDocument());

        await user.click(screen.getByRole('button', { name: /Validate/i }));
        await waitFor(() => expect(mockedValidateCredentialProfile).toHaveBeenCalledWith('profile-1'));
        expect(await screen.findByText('Ready to claim')).toBeInTheDocument();
        expect(screen.getByText('Bootstrap and managed credentials are ready.')).toBeInTheDocument();
    });
});
