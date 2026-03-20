package com.sc.lcm.core.service;

import com.sc.lcm.core.api.CredentialProfileResource.CredentialProfileRequest;
import com.sc.lcm.core.domain.CredentialProfile;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class BootstrapCredentialImportServiceTest {

    @Test
    @DisplayName("导入条目应映射为高优先级的外部来源凭据档案")
    void shouldApplyImportedProfileFields() {
        CredentialProfileRequest request = new CredentialProfileRequest(
                null,
                null,
                null,
                null,
                null,
                "CMDB",
                "asset-001",
                "Dell",
                "R760",
                null,
                "BMC_ENABLED",
                "bmc-rack-a-01",
                "^10\\.10\\.0\\.50$",
                null,
                "dell-idrac",
                "vault://cmdb/rack-a#username",
                "vault://cmdb/rack-a#password",
                null,
                null,
                null,
                null,
                null);

        CredentialProfile profile = new CredentialProfile();
        BootstrapCredentialImportService.applyImportedProfile(profile, request, "CMDB", "cmdb-asset-001");

        assertEquals("cmdb-asset-001", profile.getName());
        assertEquals("CMDB", profile.getSourceType());
        assertEquals("asset-001", profile.getExternalRef());
        assertEquals(1000, profile.getPriority());
        assertEquals("^10\\.10\\.0\\.50$", profile.getIpAddressPattern());
        assertEquals("vault://cmdb/rack-a#username", profile.getUsernameSecretRef());
        assertEquals("BMC_ENABLED", profile.getDeviceType());
    }
}
