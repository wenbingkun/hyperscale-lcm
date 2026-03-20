package com.sc.lcm.core.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sc.lcm.core.domain.DiscoveredDevice;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class RedfishTemplateCatalogTest {

    @Test
    @DisplayName("内置模板目录应能匹配主流厂商模板")
    void shouldMatchBuiltInTemplateByVendorAndModel() {
        RedfishTemplateCatalog catalog = newCatalog("");

        DiscoveredDevice device = new DiscoveredDevice();
        device.setManufacturerHint("Dell Inc.");
        device.setModelHint("PowerEdge R760");

        assertEquals("dell-idrac", catalog.recommend(device).map(RedfishTemplateCatalog.TemplateMetadata::name).orElse(null));
    }

    @Test
    @DisplayName("已知厂商但型号未知时仍应推荐模板")
    void shouldRecommendTemplateWhenVendorMatchesButModelIsMissing() {
        RedfishTemplateCatalog catalog = newCatalog("");

        assertEquals("hpe-ilo", catalog.recommend("HPE", null)
                .map(RedfishTemplateCatalog.TemplateMetadata::name)
                .orElse(null));
    }

    @Test
    @DisplayName("外部模板目录应允许覆盖或追加模板")
    void shouldLoadExternalTemplates() throws IOException {
        Path dir = Files.createTempDirectory("redfish-template-catalog");
        Files.writeString(dir.resolve("vendor-z.json"), """
                {
                  "name": "vendor-z",
                  "description": "Vendor Z template",
                  "priority": 150,
                  "match": {
                    "manufacturer_patterns": ["Vendor Z"],
                    "model_patterns": ["ZX-9000"]
                  }
                }
                """);

        RedfishTemplateCatalog catalog = newCatalog(dir.toString());

        assertEquals("vendor-z", catalog.recommend("Vendor Z", "ZX-9000")
                .map(RedfishTemplateCatalog.TemplateMetadata::name)
                .orElse(null));
    }

    private static RedfishTemplateCatalog newCatalog(String templateDir) {
        RedfishTemplateCatalog catalog = new RedfishTemplateCatalog();
        catalog.objectMapper = new ObjectMapper();
        catalog.templateDir = templateDir;
        catalog.invalidateCache();
        return catalog;
    }
}
