package com.sc.lcm.core.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sc.lcm.core.api.CredentialProfileResource.CredentialProfileRequest;
import io.smallrye.mutiny.Uni;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.*;

class CmdbBootstrapSyncServiceTest {

    @Test
    @DisplayName("应支持从嵌套 payload root 提取在线 CMDB bootstrap 记录")
    void shouldExtractEntriesFromNestedPayloadRoot() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();

        List<CredentialProfileRequest> entries = CmdbBootstrapSyncService.extractEntries(
                objectMapper,
                objectMapper.readTree("""
                        {
                          "data": {
                            "entries": [
                              {
                                "externalRef": "asset-001",
                                "ipAddressPattern": "^10\\\\.10\\\\.0\\\\.50$",
                                "usernameSecretRef": "vault://cmdb/rack-a#username",
                                "passwordSecretRef": "vault://cmdb/rack-a#password"
                              }
                            ]
                          }
                        }
                        """),
                "data.entries");

        assertEquals(1, entries.size());
        assertEquals("asset-001", entries.getFirst().externalRef());
        assertEquals("^10\\.10\\.0\\.50$", entries.getFirst().ipAddressPattern());
    }

    @Test
    @DisplayName("在线 CMDB 同步应规范化 sourceType 并复用既有导入链路")
    @SuppressWarnings("unchecked")
    void shouldSyncEntriesViaImportService() throws Exception {
        BootstrapCredentialImportService importService = mock(BootstrapCredentialImportService.class);
        when(importService.importEntries(anyList()))
                .thenReturn(Uni.createFrom().item(new BootstrapCredentialImportService.ImportResult(1, 0, 0, List.of())));

        CmdbBootstrapSyncService service = new CmdbBootstrapSyncService();
        service.objectMapper = new ObjectMapper();
        service.bootstrapCredentialImportService = importService;
        service.enabled = true;
        service.url = Optional.of("https://cmdb.example.internal/api/bootstrap");
        service.payloadRoot = "entries";
        service.sourceType = "cmdb";
        service.transport = request -> service.objectMapper.readTree("""
                {
                  "entries": [
                    {
                      "externalRef": "asset-001",
                      "ipAddressPattern": "^10\\\\.10\\\\.0\\\\.50$",
                      "usernameSecretRef": "vault://cmdb/rack-a#username",
                      "passwordSecretRef": "vault://cmdb/rack-a#password"
                    }
                  ]
                }
                """);

        CmdbBootstrapSyncService.SyncResult result = service.syncNow().await().indefinitely();

        ArgumentCaptor<List> entriesCaptor = ArgumentCaptor.forClass(List.class);
        verify(importService).importEntries(entriesCaptor.capture());

        List<CredentialProfileRequest> importedEntries = (List<CredentialProfileRequest>) entriesCaptor.getValue();
        assertEquals(1, importedEntries.size());
        assertEquals("CMDB", importedEntries.getFirst().sourceType());
        assertEquals(CmdbBootstrapSyncService.SyncStatus.SUCCESS, result.status());
        assertEquals(1, result.fetched());
        assertEquals(1, result.created());
    }

    @Test
    @DisplayName("应支持通过映射文件语义从企业 CMDB API 提取字段")
    void shouldMapEnterpriseCmdbPayload() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();

        CmdbBootstrapSyncService.CmdbMappingProfile profile = new CmdbBootstrapSyncService.CmdbMappingProfile();
        profile.entriesPath = "result.items";
        profile.literalPatternFields = List.of("hostname_pattern", "ip_address_pattern", "mac_address_pattern");
        profile.defaults = new CmdbBootstrapSyncService.CmdbDefaults();
        profile.defaults.protocol = "REDFISH";
        profile.defaults.sourceType = "CMDB";
        profile.defaults.enabled = true;
        profile.defaults.autoClaim = true;
        profile.defaults.priority = 1000;
        profile.defaults.deviceType = "BMC_ENABLED";
        profile.fields = new CmdbBootstrapSyncService.CmdbFieldMap();
        profile.fields.externalRef = "asset.id";
        profile.fields.hostnamePattern = "asset.hostname";
        profile.fields.ipAddressPattern = "network.bmc_ip";
        profile.fields.macAddressPattern = "network.bmc_mac";
        profile.fields.vendorPattern = "hardware.vendor";
        profile.fields.modelPattern = "hardware.model";
        profile.fields.redfishTemplate = "redfish.template";
        profile.fields.usernameSecretRef = "secrets.username_ref";
        profile.fields.passwordSecretRef = "secrets.password_ref";

        List<CredentialProfileRequest> entries = CmdbBootstrapSyncService.mapEntries(
                objectMapper,
                objectMapper.readTree("""
                        {
                          "result": {
                            "items": [
                              {
                                "asset": {
                                  "id": "srv-001",
                                  "hostname": "bmc-rack-a-01"
                                },
                                "network": {
                                  "bmc_ip": "10.10.0.50",
                                  "bmc_mac": "00:11:22:33:44:55"
                                },
                                "hardware": {
                                  "vendor": "Dell",
                                  "model": "PowerEdge R760"
                                },
                                "redfish": {
                                  "template": "dell-idrac"
                                },
                                "secrets": {
                                  "username_ref": "vault://cmdb/rack-a#username",
                                  "password_ref": "vault://cmdb/rack-a#password"
                                }
                              }
                            ]
                          }
                        }
                        """),
                profile,
                "entries",
                "CMDB");

        assertEquals(1, entries.size());
        assertEquals("srv-001", entries.getFirst().externalRef());
        assertEquals("^\\Qbmc-rack-a-01\\E$", entries.getFirst().hostnamePattern());
        assertEquals("^\\Q10.10.0.50\\E$", entries.getFirst().ipAddressPattern());
        assertEquals("^\\Q00:11:22:33:44:55\\E$", entries.getFirst().macAddressPattern());
        assertEquals("dell-idrac", entries.getFirst().redfishTemplate());
        assertEquals("REDFISH", entries.getFirst().protocol());
    }

    @Test
    @DisplayName("应支持跟随企业 CMDB API 的 next link 分页同步")
    @SuppressWarnings("unchecked")
    void shouldFollowNextPageLinks() throws Exception {
        BootstrapCredentialImportService importService = mock(BootstrapCredentialImportService.class);
        when(importService.importEntries(anyList()))
                .thenReturn(Uni.createFrom().item(new BootstrapCredentialImportService.ImportResult(2, 0, 0, List.of())));

        CmdbBootstrapSyncService service = new CmdbBootstrapSyncService();
        service.objectMapper = new ObjectMapper();
        service.bootstrapCredentialImportService = importService;
        service.enabled = true;
        service.url = Optional.of("https://cmdb.example.internal/api/bootstrap?page=1");
        service.maxPages = 5;
        Path mappingFile = Files.createTempFile("cmdb-sync-profile", ".json");
        Files.writeString(mappingFile, """
                {
                  "entries_path": "result.items",
                  "next_page_path": "result.next",
                  "literal_pattern_fields": ["ip_address_pattern"],
                  "defaults": {
                    "sourceType": "CMDB"
                  },
                  "fields": {
                    "external_ref": "id",
                    "ip_address_pattern": "ip",
                    "username_secret_ref": "username_ref",
                    "password_secret_ref": "password_ref"
                  }
                }
                """);
        service.mappingFile = Optional.of(mappingFile.toString());

        service.transport = request -> switch (request.url()) {
            case "https://cmdb.example.internal/api/bootstrap?page=1" -> service.objectMapper.readTree("""
                    {
                      "result": {
                        "items": [
                          {
                            "id": "asset-001",
                            "ip": "10.10.0.50",
                            "username_ref": "vault://cmdb/a#username",
                            "password_ref": "vault://cmdb/a#password"
                          }
                        ],
                        "next": "/api/bootstrap?page=2"
                      }
                    }
                    """);
            case "https://cmdb.example.internal/api/bootstrap?page=2" -> service.objectMapper.readTree("""
                    {
                      "result": {
                        "items": [
                          {
                            "id": "asset-002",
                            "ip": "10.10.0.51",
                            "username_ref": "vault://cmdb/b#username",
                            "password_ref": "vault://cmdb/b#password"
                          }
                        ]
                      }
                    }
                    """);
            default -> throw new IllegalStateException("Unexpected URL: " + request.url());
        };

        CmdbBootstrapSyncService.SyncResult result = service.syncNow().await().indefinitely();

        ArgumentCaptor<List> entriesCaptor = ArgumentCaptor.forClass(List.class);
        verify(importService).importEntries(entriesCaptor.capture());
        List<CredentialProfileRequest> importedEntries = (List<CredentialProfileRequest>) entriesCaptor.getValue();
        assertEquals(2, importedEntries.size());
        assertEquals("asset-001", importedEntries.getFirst().externalRef());
        assertEquals("^\\Q10.10.0.51\\E$", importedEntries.get(1).ipAddressPattern());
        assertEquals(CmdbBootstrapSyncService.SyncStatus.SUCCESS, result.status());
        assertEquals(2, result.fetched());
    }
}
