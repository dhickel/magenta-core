package io.mindspice.magenta2.ai.skills;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.util.StringUtils;

@Repository
public class AgentSkillRepository {
    private static final TypeReference<Map<String, String>> STRING_MAP = new TypeReference<>() { };
    private static final TypeReference<List<AgentSkillDiagnostic>> DIAGNOSTIC_LIST = new TypeReference<>() { };

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public AgentSkillRepository(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        ensureSchema();
    }

    public List<AgentSkill> findAll() {
        return jdbcTemplate.query("select * from agent_skills order by directory_slug", (rs, rowNum) -> toSkill(rs));
    }

    public Optional<AgentSkill> findByDirectorySlug(String directorySlug) {
        if (!StringUtils.hasText(directorySlug)) {
            return Optional.empty();
        }
        return jdbcTemplate.query(
            "select * from agent_skills where directory_slug = ?",
            rs -> rs.next() ? Optional.of(toSkill(rs)) : Optional.empty(),
            directorySlug
        );
    }

    public AgentSkill save(AgentSkill skill) {
        Instant now = Instant.now();
        Instant discoveredAt = skill.discoveredAt() == null ? now : skill.discoveredAt();
        Instant lastScannedAt = skill.lastScannedAt() == null ? now : skill.lastScannedAt();
        Instant createdAt = skill.createdAt() == null ? now : skill.createdAt();
        Instant updatedAt = now;
        jdbcTemplate.update(
            """
                insert into agent_skills (
                    name, directory_slug, description, license, compatibility, metadata_json, allowed_tools,
                    skill_root_relative_path, skill_md_root_relative_path, status, diagnostics_json,
                    has_scripts, has_references, has_assets, content_hash, discovered_at, last_scanned_at, created_at, updated_at
                )
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                on conflict(directory_slug) do update set
                    name = excluded.name,
                    description = excluded.description,
                    license = excluded.license,
                    compatibility = excluded.compatibility,
                    metadata_json = excluded.metadata_json,
                    allowed_tools = excluded.allowed_tools,
                    skill_root_relative_path = excluded.skill_root_relative_path,
                    skill_md_root_relative_path = excluded.skill_md_root_relative_path,
                    status = excluded.status,
                    diagnostics_json = excluded.diagnostics_json,
                    has_scripts = excluded.has_scripts,
                    has_references = excluded.has_references,
                    has_assets = excluded.has_assets,
                    content_hash = excluded.content_hash,
                    last_scanned_at = excluded.last_scanned_at,
                    created_at = agent_skills.created_at,
                    discovered_at = agent_skills.discovered_at,
                    updated_at = excluded.updated_at
                """,
            skill.name(),
            skill.directorySlug(),
            skill.description(),
            skill.license(),
            skill.compatibility(),
            jsonMap(skill.metadata()),
            skill.allowedTools(),
            skill.skillRootRelativePath(),
            skill.skillMdRootRelativePath(),
            skill.status().name(),
            jsonDiagnostics(skill.diagnostics()),
            skill.hasScripts() ? 1 : 0,
            skill.hasReferences() ? 1 : 0,
            skill.hasAssets() ? 1 : 0,
            skill.contentHash(),
            discoveredAt.toString(),
            lastScannedAt.toString(),
            createdAt.toString(),
            updatedAt.toString()
        );
        return findByDirectorySlug(skill.directorySlug()).orElseThrow();
    }

    public int deleteByDirectorySlugNotIn(Collection<String> directorySlugs) {
        if (directorySlugs == null || directorySlugs.isEmpty()) {
            return jdbcTemplate.update("delete from agent_skills");
        }
        String placeholders = directorySlugs.stream().map(v -> "?").collect(Collectors.joining(","));
        String sql = "delete from agent_skills where directory_slug not in (" + placeholders + ")";
        return jdbcTemplate.update(sql, directorySlugs.toArray());
    }

    private AgentSkill toSkill(ResultSet rs) throws SQLException {
        Number id = (Number) rs.getObject("id");
        return new AgentSkill(
            id == null ? null : id.longValue(),
            rs.getString("name"),
            rs.getString("directory_slug"),
            rs.getString("description"),
            rs.getString("license"),
            rs.getString("compatibility"),
            map(rs.getString("metadata_json")),
            rs.getString("allowed_tools"),
            rs.getString("skill_root_relative_path"),
            rs.getString("skill_md_root_relative_path"),
            AgentSkillStatus.valueOf(rs.getString("status")),
            diagnostics(rs.getString("diagnostics_json")),
            rs.getInt("has_scripts") == 1,
            rs.getInt("has_references") == 1,
            rs.getInt("has_assets") == 1,
            rs.getString("content_hash"),
            instant(rs.getString("discovered_at")),
            instant(rs.getString("last_scanned_at")),
            instant(rs.getString("created_at")),
            instant(rs.getString("updated_at"))
        );
    }

    private Instant instant(String value) {
        return StringUtils.hasText(value) ? Instant.parse(value) : null;
    }

    private String jsonMap(Map<String, String> value) {
        try {
            return objectMapper.writeValueAsString(value == null ? Map.of() : value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("failed to serialize skill metadata", exception);
        }
    }

    private String jsonDiagnostics(List<AgentSkillDiagnostic> value) {
        try {
            return objectMapper.writeValueAsString(value == null ? List.of() : value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("failed to serialize skill diagnostics", exception);
        }
    }

    private Map<String, String> map(String value) {
        if (!StringUtils.hasText(value)) {
            return Map.of();
        }
        try {
            return Map.copyOf(objectMapper.readValue(value, STRING_MAP));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("failed to parse skill metadata", exception);
        }
    }

    private List<AgentSkillDiagnostic> diagnostics(String value) {
        if (!StringUtils.hasText(value)) {
            return List.of();
        }
        try {
            return List.copyOf(objectMapper.readValue(value, DIAGNOSTIC_LIST));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("failed to parse skill diagnostics", exception);
        }
    }

    private void ensureSchema() {
        jdbcTemplate.execute("""
            create table if not exists agent_skills (
                id integer primary key autoincrement,
                name text,
                directory_slug text not null unique,
                description text,
                license text,
                compatibility text,
                metadata_json text not null,
                allowed_tools text,
                skill_root_relative_path text not null,
                skill_md_root_relative_path text,
                status text not null,
                diagnostics_json text not null,
                has_scripts integer not null,
                has_references integer not null,
                has_assets integer not null,
                content_hash text,
                discovered_at text not null,
                last_scanned_at text not null,
                created_at text not null,
                updated_at text not null
            )
            """);
        jdbcTemplate.execute("create index if not exists idx_agent_skills_name on agent_skills(name)");
        jdbcTemplate.execute("create index if not exists idx_agent_skills_status on agent_skills(status)");
    }
}
