import type { CredentialProfile, CredentialProfileRequest } from '../../api/client';

export interface CredentialProfileFormState {
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
    redfishAuthMode: string;
    usernameSecretRef: string;
    passwordSecretRef: string;
    managedAccountEnabled: boolean;
    managedUsernameSecretRef: string;
    managedPasswordSecretRef: string;
    managedAccountRoleId: string;
    description: string;
}

export const createEmptyCredentialProfileForm = (): CredentialProfileFormState => ({
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
    redfishAuthMode: 'SESSION_PREFERRED',
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

export const toCredentialProfileFormState = (profile: CredentialProfile): CredentialProfileFormState => ({
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
    redfishAuthMode: profile.redfishAuthMode || 'SESSION_PREFERRED',
    usernameSecretRef: profile.usernameSecretRef || '',
    passwordSecretRef: profile.passwordSecretRef || '',
    managedAccountEnabled: profile.managedAccountEnabled || false,
    managedUsernameSecretRef: profile.managedUsernameSecretRef || '',
    managedPasswordSecretRef: profile.managedPasswordSecretRef || '',
    managedAccountRoleId: profile.managedAccountRoleId || 'Administrator',
    description: profile.description || '',
});

export const toCredentialProfileRequest = (form: CredentialProfileFormState): CredentialProfileRequest => ({
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
    redfishAuthMode: optionalValue(form.redfishAuthMode),
    usernameSecretRef: optionalValue(form.usernameSecretRef),
    passwordSecretRef: optionalValue(form.passwordSecretRef),
    managedAccountEnabled: form.managedAccountEnabled,
    managedUsernameSecretRef: optionalValue(form.managedUsernameSecretRef),
    managedPasswordSecretRef: optionalValue(form.managedPasswordSecretRef),
    managedAccountRoleId: optionalValue(form.managedAccountRoleId),
    description: optionalValue(form.description),
});
