-- Managed BMC service-account provisioning after successful Redfish claim

ALTER TABLE credential_profiles ADD COLUMN IF NOT EXISTS managed_account_enabled BOOLEAN DEFAULT FALSE;
ALTER TABLE credential_profiles ADD COLUMN IF NOT EXISTS managed_username_secret_ref VARCHAR(255);
ALTER TABLE credential_profiles ADD COLUMN IF NOT EXISTS managed_password_secret_ref VARCHAR(255);
ALTER TABLE credential_profiles ADD COLUMN IF NOT EXISTS managed_account_role_id VARCHAR(100) DEFAULT 'Administrator';
