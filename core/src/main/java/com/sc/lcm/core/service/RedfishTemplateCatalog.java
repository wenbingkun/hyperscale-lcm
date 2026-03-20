package com.sc.lcm.core.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sc.lcm.core.domain.DiscoveredDevice;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * 管理 Redfish 模板元数据，并基于厂商/型号提示做模板推荐。
 * 运行时优先使用内置模板，可选地从外部 JSON 目录加载同格式模板覆盖。
 */
@ApplicationScoped
@Slf4j
public class RedfishTemplateCatalog {

    @Inject
    ObjectMapper objectMapper;

    @ConfigProperty(name = "lcm.redfish.template-catalog.dir", defaultValue = "documentation/redfish-templates")
    String templateDir = "documentation/redfish-templates";

    private volatile List<TemplateMetadata> cachedTemplates;

    public List<TemplateMetadata> listTemplates() {
        List<TemplateMetadata> templates = cachedTemplates;
        if (templates != null) {
            return templates;
        }

        synchronized (this) {
            if (cachedTemplates == null) {
                cachedTemplates = List.copyOf(loadTemplates());
            }
            return cachedTemplates;
        }
    }

    public Optional<TemplateMetadata> recommend(DiscoveredDevice device) {
        if (device == null) {
            return Optional.empty();
        }
        return recommend(device.getManufacturerHint(), device.getModelHint());
    }

    public Optional<TemplateMetadata> recommend(String manufacturerHint, String modelHint) {
        return listTemplates().stream()
                .filter(template -> template.matches(manufacturerHint, modelHint))
                .findFirst();
    }

    void invalidateCache() {
        cachedTemplates = null;
    }

    private List<TemplateMetadata> loadTemplates() {
        Map<String, TemplateMetadata> merged = new LinkedHashMap<>();
        for (TemplateMetadata template : builtInTemplates()) {
            merged.put(template.name(), template);
        }

        if (templateDir == null || templateDir.isBlank()) {
            return merged.values().stream()
                    .sorted(Comparator.comparingInt(TemplateMetadata::priority).reversed()
                            .thenComparing(TemplateMetadata::name))
                    .toList();
        }

        Path directory = Path.of(templateDir);
        if (Files.isDirectory(directory)) {
            try (var paths = Files.list(directory)) {
                paths.filter(path -> path.getFileName().toString().endsWith(".json"))
                        .sorted()
                        .forEach(path -> {
                            TemplateMetadata template = readTemplate(path);
                            if (template != null) {
                                merged.put(template.name(), template);
                            }
                        });
            } catch (IOException e) {
                log.warn("Failed to load Redfish templates from {}", directory, e);
            }
        } else {
            log.debug("Redfish template directory {} not found, using built-in catalog only.", directory);
        }

        return merged.values().stream()
                .sorted(Comparator.comparingInt(TemplateMetadata::priority).reversed()
                        .thenComparing(TemplateMetadata::name))
                .toList();
    }

    private TemplateMetadata readTemplate(Path path) {
        try {
            TemplateFile parsed = objectMapper.readValue(path.toFile(), TemplateFile.class);
            if (parsed.name == null || parsed.name.isBlank()) {
                log.warn("Skip template {} because name is missing.", path);
                return null;
            }
            return new TemplateMetadata(
                    parsed.name,
                    parsed.description != null ? parsed.description : "",
                    parsed.priority,
                    safeList(parsed.match != null ? parsed.match.manufacturerPatterns : null),
                    safeList(parsed.match != null ? parsed.match.modelPatterns : null),
                    path.toString());
        } catch (IOException e) {
            log.warn("Failed to parse Redfish template {}", path, e);
            return null;
        }
    }

    private static List<TemplateMetadata> builtInTemplates() {
        List<TemplateMetadata> templates = new ArrayList<>();
        templates.add(new TemplateMetadata(
                "openbmc-baseline",
                "OpenBMC-compatible Redfish baseline template",
                100,
                List.of("OpenBMC", "AST2600"),
                List.of(),
                "builtin"));
        templates.add(new TemplateMetadata(
                "dell-idrac",
                "Dell iDRAC-compatible Redfish template",
                95,
                List.of("Dell", "iDRAC"),
                List.of("PowerEdge", "R7[0-9]{2}", "XE[0-9]{2}"),
                "builtin"));
        templates.add(new TemplateMetadata(
                "hpe-ilo",
                "HPE iLO-compatible Redfish template",
                95,
                List.of("HPE", "Hewlett Packard", "iLO"),
                List.of("ProLiant", "Apollo"),
                "builtin"));
        templates.add(new TemplateMetadata(
                "lenovo-xcc",
                "Lenovo XClarity Controller-compatible Redfish template",
                95,
                List.of("Lenovo", "XClarity", "XCC"),
                List.of("ThinkSystem", "SR[0-9]{3}"),
                "builtin"));
        return templates;
    }

    private static List<String> safeList(Collection<String> values) {
        if (values == null) {
            return List.of();
        }
        return values.stream()
                .filter(value -> value != null && !value.isBlank())
                .toList();
    }

    public record TemplateMetadata(
            String name,
            String description,
            int priority,
            List<String> manufacturerPatterns,
            List<String> modelPatterns,
            String source) {

        public boolean matches(String manufacturerHint, String modelHint) {
            boolean manufacturerMatched = !manufacturerPatterns.isEmpty()
                    && manufacturerPatterns.stream()
                            .anyMatch(pattern -> CredentialProfileMatcher.matchesPattern(manufacturerHint, pattern));
            boolean modelMatched = !modelPatterns.isEmpty()
                    && modelPatterns.stream()
                            .anyMatch(pattern -> CredentialProfileMatcher.matchesPattern(modelHint, pattern));

            if (manufacturerHint != null && !manufacturerHint.isBlank() && !manufacturerPatterns.isEmpty() && !manufacturerMatched) {
                return false;
            }
            if (modelHint != null && !modelHint.isBlank() && !modelPatterns.isEmpty() && !modelMatched) {
                return false;
            }

            return manufacturerMatched || modelMatched;
        }
    }

    static final class TemplateFile {
        public String name;
        public String description;
        public int priority;
        public TemplateMatch match;
    }

    static final class TemplateMatch {
        @com.fasterxml.jackson.annotation.JsonProperty("manufacturer_patterns")
        public List<String> manufacturerPatterns;

        @com.fasterxml.jackson.annotation.JsonProperty("model_patterns")
        public List<String> modelPatterns;
    }
}
