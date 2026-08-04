package io.mindspice.magenta2.ai.skills;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class AgentSkillParser {
    private static final Pattern FRONTMATTER_PATTERN = Pattern.compile("(?s)^---\\R(.*?)\\R---(?:\\R|$)(.*)$");
    private static final Pattern TOP_LEVEL_YAML_LINE = Pattern.compile("^([A-Za-z0-9_-]+):(\\s*)(.+)$");
    private static final Pattern SKILL_NAME_SHAPE = Pattern.compile("^[a-z0-9]+(?:-[a-z0-9]+)*$");
    private static final TypeReference<Map<String, Object>> MAP_OF_OBJECT = new TypeReference<>() { };

    private final ObjectMapper yamlMapper = new ObjectMapper(new YAMLFactory());

    public AgentSkillParseResult parse(String markdown, String directorySlug, String sourcePath) {
        List<AgentSkillDiagnostic> diagnostics = new ArrayList<>();
        if (!StringUtils.hasText(markdown)) {
            diagnostics.add(error(
                AgentSkillDiagnosticCode.SKILL_FRONTMATTER_MISSING,
                "SKILL.md is empty or missing frontmatter",
                sourcePath
            ));
            return result(AgentSkillStatus.INVALID, null, "", diagnostics);
        }
        Matcher matcher = FRONTMATTER_PATTERN.matcher(markdown);
        if (!matcher.matches()) {
            diagnostics.add(error(
                AgentSkillDiagnosticCode.SKILL_FRONTMATTER_MISSING,
                "SKILL.md must start with --- frontmatter and include a closing --- delimiter",
                sourcePath
            ));
            return result(AgentSkillStatus.INVALID, null, "", diagnostics);
        }

        String frontmatterText = matcher.group(1);
        String body = matcher.group(2) == null ? "" : matcher.group(2).trim();
        Map<String, Object> fields = parseFrontmatter(frontmatterText, sourcePath, diagnostics);
        if (fields == null) {
            return result(AgentSkillStatus.INVALID, null, body, diagnostics);
        }

        String name = readRequiredString(fields, "name", AgentSkillDiagnosticCode.SKILL_NAME_MISSING, diagnostics, sourcePath);
        if (StringUtils.hasText(name)) {
            String normalizedName = name.trim();
            if (normalizedName.length() > 64) {
                diagnostics.add(warn(
                    AgentSkillDiagnosticCode.SKILL_NAME_TOO_LONG,
                    "name exceeds 64 characters; loading with warning for compatibility",
                    sourcePath
                ));
            }
            if (!SKILL_NAME_SHAPE.matcher(normalizedName).matches()) {
                diagnostics.add(warn(
                    AgentSkillDiagnosticCode.SKILL_NAME_INVALID,
                    "name should match ^[a-z0-9]+(?:-[a-z0-9]+)*$",
                    sourcePath
                ));
            }
            if (StringUtils.hasText(directorySlug) && !normalizedName.equals(directorySlug)) {
                diagnostics.add(warn(
                    AgentSkillDiagnosticCode.SKILL_NAME_DIRECTORY_MISMATCH,
                    "name does not match parent directory; loading with warning for compatibility",
                    sourcePath
                ));
            }
            name = normalizedName;
        }

        String description = readRequiredString(
            fields,
            "description",
            AgentSkillDiagnosticCode.SKILL_DESCRIPTION_MISSING,
            diagnostics,
            sourcePath
        );
        if (StringUtils.hasText(description)) {
            description = description.trim();
            if (description.length() > 1024) {
                diagnostics.add(warn(
                    AgentSkillDiagnosticCode.SKILL_DESCRIPTION_TOO_LONG,
                    "description exceeds 1024 characters; loading with warning for compatibility",
                    sourcePath
                ));
            }
        }

        String license = readOptionalString(fields, "license", null, diagnostics, sourcePath);
        String compatibility = readOptionalString(
            fields,
            "compatibility",
            AgentSkillDiagnosticCode.SKILL_COMPATIBILITY_INVALID,
            diagnostics,
            sourcePath
        );
        if (compatibility != null) {
            compatibility = compatibility.trim();
            if (compatibility.isEmpty() || compatibility.length() > 500) {
                diagnostics.add(warn(
                    AgentSkillDiagnosticCode.SKILL_COMPATIBILITY_INVALID,
                    "compatibility must be 1-500 characters when provided",
                    sourcePath
                ));
            }
        }
        String allowedTools = readOptionalString(
            fields,
            "allowed-tools",
            AgentSkillDiagnosticCode.SKILL_ALLOWED_TOOLS_INVALID,
            diagnostics,
            sourcePath
        );

        Map<String, String> metadata = Map.of();
        Object metadataRaw = fields.get("metadata");
        if (metadataRaw != null) {
            if (metadataRaw instanceof Map<?, ?> rawMap) {
                LinkedHashMap<String, String> parsed = new LinkedHashMap<>();
                for (Map.Entry<?, ?> entry : rawMap.entrySet()) {
                    if (entry.getKey() instanceof String key && entry.getValue() instanceof String value) {
                        parsed.put(key, value);
                    } else {
                        diagnostics.add(warn(
                            AgentSkillDiagnosticCode.SKILL_METADATA_INVALID,
                            "metadata entries must be string keys and string values",
                            sourcePath
                        ));
                    }
                }
                metadata = Map.copyOf(parsed);
            } else {
                diagnostics.add(warn(
                    AgentSkillDiagnosticCode.SKILL_METADATA_INVALID,
                    "metadata must be a map of string keys and string values",
                    sourcePath
                ));
            }
        }

        AgentSkillFrontmatter frontmatter = new AgentSkillFrontmatter(
            name,
            description,
            license,
            compatibility,
            metadata,
            StringUtils.hasText(allowedTools) ? allowedTools.trim() : null
        );
        return result(statusFromDiagnostics(diagnostics), frontmatter, body, diagnostics);
    }

    private Map<String, Object> parseFrontmatter(
        String frontmatterText,
        String sourcePath,
        List<AgentSkillDiagnostic> diagnostics
    ) {
        try {
            Map<String, Object> parsed = yamlMapper.readValue(frontmatterText, MAP_OF_OBJECT);
            return parsed == null ? Map.of() : parsed;
        } catch (JsonProcessingException firstFailure) {
            String fallback = applyYamlColonFallback(frontmatterText);
            if (!fallback.equals(frontmatterText)) {
                try {
                    Map<String, Object> parsed = yamlMapper.readValue(fallback, MAP_OF_OBJECT);
                    diagnostics.add(warn(
                        AgentSkillDiagnosticCode.SKILL_FRONTMATTER_YAML_FALLBACK_USED,
                        "frontmatter required compatibility fallback for colon-delimited scalar values",
                        sourcePath
                    ));
                    return parsed == null ? Map.of() : parsed;
                } catch (JsonProcessingException ignored) {
                    // fallthrough to final parse failure handling
                }
            }
            diagnostics.add(error(
                AgentSkillDiagnosticCode.SKILL_FRONTMATTER_YAML_INVALID,
                "frontmatter YAML is unparseable",
                sourcePath
            ));
            return null;
        }
    }

    private String applyYamlColonFallback(String frontmatterText) {
        String[] lines = frontmatterText.split("\\R", -1);
        StringBuilder repaired = new StringBuilder(frontmatterText.length() + 16);
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            if (!line.startsWith(" ") && !line.startsWith("\t")) {
                Matcher topLevel = TOP_LEVEL_YAML_LINE.matcher(line);
                if (topLevel.matches()) {
                    String key = topLevel.group(1).toLowerCase(Locale.ROOT);
                    String spaces = topLevel.group(2);
                    String value = topLevel.group(3);
                    if (value.contains(":")
                        && !value.startsWith("\"")
                        && !value.startsWith("'")
                        && !value.startsWith("[")
                        && !value.startsWith("{")
                        && !value.startsWith("|")
                        && !value.startsWith(">")) {
                        if ("name".equals(key)
                            || "description".equals(key)
                            || "license".equals(key)
                            || "compatibility".equals(key)
                            || "allowed-tools".equals(key)) {
                            line = topLevel.group(1) + ":" + spaces + "\"" + escapeYamlDoubleQuoted(value) + "\"";
                        }
                    }
                }
            }
            repaired.append(line);
            if (i < lines.length - 1) {
                repaired.append('\n');
            }
        }
        return repaired.toString();
    }

    private String escapeYamlDoubleQuoted(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private String readRequiredString(
        Map<String, Object> fields,
        String fieldName,
        AgentSkillDiagnosticCode missingCode,
        List<AgentSkillDiagnostic> diagnostics,
        String sourcePath
    ) {
        Object value = fields.get(fieldName);
        if (!(value instanceof String stringValue) || !StringUtils.hasText(stringValue.trim())) {
            diagnostics.add(error(
                missingCode,
                fieldName + " is required and must be a non-empty string",
                sourcePath
            ));
            return null;
        }
        return stringValue;
    }

    private String readOptionalString(
        Map<String, Object> fields,
        String fieldName,
        AgentSkillDiagnosticCode invalidCode,
        List<AgentSkillDiagnostic> diagnostics,
        String sourcePath
    ) {
        Object value = fields.get(fieldName);
        if (value == null) {
            return null;
        }
        if (value instanceof String stringValue) {
            return stringValue;
        }
        if (invalidCode != null) {
            diagnostics.add(warn(invalidCode, fieldName + " must be a string when provided", sourcePath));
        }
        return null;
    }

    private AgentSkillStatus statusFromDiagnostics(List<AgentSkillDiagnostic> diagnostics) {
        boolean hasError = diagnostics.stream().anyMatch(d -> d.severity() == AgentSkillDiagnosticSeverity.ERROR);
        if (hasError) {
            return AgentSkillStatus.INVALID;
        }
        boolean hasWarning = diagnostics.stream().anyMatch(d -> d.severity() == AgentSkillDiagnosticSeverity.WARNING);
        return hasWarning ? AgentSkillStatus.WARNING : AgentSkillStatus.VALID;
    }

    private AgentSkillParseResult result(
        AgentSkillStatus status,
        AgentSkillFrontmatter frontmatter,
        String body,
        List<AgentSkillDiagnostic> diagnostics
    ) {
        return new AgentSkillParseResult(status, frontmatter, body, List.copyOf(diagnostics));
    }

    private AgentSkillDiagnostic error(AgentSkillDiagnosticCode code, String message, String sourcePath) {
        return new AgentSkillDiagnostic(AgentSkillDiagnosticSeverity.ERROR, code, message, sourcePath);
    }

    private AgentSkillDiagnostic warn(AgentSkillDiagnosticCode code, String message, String sourcePath) {
        return new AgentSkillDiagnostic(AgentSkillDiagnosticSeverity.WARNING, code, message, sourcePath);
    }
}
