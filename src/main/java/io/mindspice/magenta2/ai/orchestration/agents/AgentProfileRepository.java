package io.mindspice.magenta2.ai.orchestration.agents;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.util.StringUtils;

@Repository
public class AgentProfileRepository {
    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() { };

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public AgentProfileRepository(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        ensureSchema();
    }

    public boolean isEmpty() {
        Integer count = jdbcTemplate.queryForObject("select count(*) from agent_profiles", Integer.class);
        return count == null || count == 0;
    }

    public List<AgentProfile> findAll() {
        return jdbcTemplate.query("select * from agent_profiles order by name", (rs, rowNum) -> toProfile(rs));
    }

    public Optional<AgentProfile> findById(String id) {
        if (!StringUtils.hasText(id)) {
            return Optional.empty();
        }
        return jdbcTemplate.query(
            "select * from agent_profiles where id = ?",
            rs -> rs.next() ? Optional.of(toProfile(rs)) : Optional.empty(),
            id
        );
    }

    public boolean existsById(String id) {
        if (!StringUtils.hasText(id)) {
            return false;
        }
        Integer count = jdbcTemplate.queryForObject(
                "select count(*) from agent_profiles where id = ?",
                Integer.class,
                id);
        return count != null && count > 0;
    }

    public Optional<AgentProfile> findByName(String name) {
        if (!StringUtils.hasText(name)) {
            return Optional.empty();
        }
        return jdbcTemplate.query(
            "select * from agent_profiles where name = ?",
            rs -> rs.next() ? Optional.of(toProfile(rs)) : Optional.empty(),
            name
        );
    }

    public AgentProfile save(AgentProfile profile) {
        Instant now = Instant.now();
        Instant createdAt = profile.createdAt() == null ? now : profile.createdAt();
        Instant updatedAt = now;
        jdbcTemplate.update(
            """
                insert into agent_profiles (
                    id, name, status, default_model, system_prompt_text, approved_tool_names_json,
                    allowed_shell_commands_json, direct_line_enabled, created_at, updated_at
                )
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                on conflict(id) do update set
                    name = excluded.name,
                    status = excluded.status,
                    default_model = excluded.default_model,
                    system_prompt_text = excluded.system_prompt_text,
                    approved_tool_names_json = excluded.approved_tool_names_json,
                    allowed_shell_commands_json = excluded.allowed_shell_commands_json,
                    direct_line_enabled = excluded.direct_line_enabled,
                    updated_at = excluded.updated_at
                """,
            profile.id(),
            profile.name(),
            profile.status().name(),
            profile.defaultModel(),
            profile.systemPrompt(),
            json(profile.approvedTools()),
            json(profile.allowedShellCommands()),
            profile.directLineEnabled() ? 1 : 0,
            createdAt.toString(),
            updatedAt.toString()
        );
        return findById(profile.id()).orElseThrow();
    }

    public void delete(String id) {
        jdbcTemplate.update("delete from agent_profiles where id = ?", id);
    }

    private AgentProfile toProfile(ResultSet rs) throws SQLException {
        return new AgentProfile(
            rs.getString("id"),
            rs.getString("name"),
            AgentProfileStatus.valueOf(rs.getString("status")),
            rs.getString("default_model"),
            rs.getString("system_prompt_text"),
            list(rs.getString("approved_tool_names_json")),
            list(rs.getString("allowed_shell_commands_json")),
            rs.getInt("direct_line_enabled") == 1,
            instant(rs.getString("created_at")),
            instant(rs.getString("updated_at"))
        );
    }

    private String json(List<String> values) {
        try {
            return objectMapper.writeValueAsString(values == null ? List.of() : values);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Failed to serialize agent profile list", exception);
        }
    }

    private List<String> list(String json) {
        if (!StringUtils.hasText(json)) {
            return List.of();
        }
        try {
            return List.copyOf(objectMapper.readValue(json, STRING_LIST));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Failed to parse agent profile list", exception);
        }
    }

    private Instant instant(String value) {
        return StringUtils.hasText(value) ? Instant.parse(value) : null;
    }

    private void ensureSchema() {
        jdbcTemplate.execute("""
            create table if not exists agent_profiles (
                id text primary key,
                name text not null unique,
                status text not null,
                default_model text,
                system_prompt_text text,
                approved_tool_names_json text not null,
                allowed_shell_commands_json text not null,
                direct_line_enabled integer not null,
                created_at text not null,
                updated_at text not null
            )
            """);
    }
}
