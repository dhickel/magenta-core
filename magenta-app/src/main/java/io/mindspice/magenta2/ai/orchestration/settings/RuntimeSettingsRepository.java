package io.mindspice.magenta2.ai.orchestration.settings;

import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class RuntimeSettingsRepository {
    private static final String SETTINGS_ID = "runtime";

    private final JdbcTemplate jdbcTemplate;

    public RuntimeSettingsRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
        ensureSchema();
    }

    public Optional<RuntimeSettings> find() {
        return jdbcTemplate.query(
            """
                select default_agent_id, default_agent_name, default_model,
                       planning_model, summary_model, compaction_model, context_buffer_percent,
                       system_chat_model, system_chat_prompt, system_chat_approved_tools,
                       system_chat_context_limit, system_chat_enabled, assignment_history_auto_purge_days,
                       retain_temp_work
                from runtime_settings
                where id = ?
                """,
            rs -> rs.next()
                ? Optional.of(new RuntimeSettings(
                    rs.getString("default_agent_id"),
                    rs.getString("default_agent_name"),
                    rs.getString("default_model"),
                    rs.getString("planning_model"),
                    rs.getString("summary_model"),
                    rs.getString("compaction_model"),
                    (Integer) rs.getObject("context_buffer_percent"),
                    rs.getString("system_chat_model"),
                    rs.getString("system_chat_prompt"),
                    rs.getString("system_chat_approved_tools"),
                    (Integer) rs.getObject("system_chat_context_limit"),
                    rs.getObject("system_chat_enabled") == null ? null : rs.getInt("system_chat_enabled") == 1,
                    rs.getObject("assignment_history_auto_purge_days") == null
                        ? -1
                        : rs.getInt("assignment_history_auto_purge_days"),
                    rs.getObject("retain_temp_work") != null && rs.getInt("retain_temp_work") == 1
                ))
                : Optional.empty(),
            SETTINGS_ID
        );
    }

    public RuntimeSettings save(RuntimeSettings settings) {
        jdbcTemplate.update(
            """
                insert into runtime_settings (
                    id, default_agent_id, default_agent_name, default_model,
                    planning_model, summary_model, compaction_model, context_buffer_percent,
                    system_chat_model, system_chat_prompt, system_chat_approved_tools,
                    system_chat_context_limit, system_chat_enabled, assignment_history_auto_purge_days,
                    retain_temp_work
                )
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                on conflict(id) do update set
                    default_agent_id = excluded.default_agent_id,
                    default_agent_name = excluded.default_agent_name,
                    default_model = excluded.default_model,
                    planning_model = excluded.planning_model,
                    summary_model = excluded.summary_model,
                    compaction_model = excluded.compaction_model,
                    context_buffer_percent = excluded.context_buffer_percent,
                    system_chat_model = excluded.system_chat_model,
                    system_chat_prompt = excluded.system_chat_prompt,
                    system_chat_approved_tools = excluded.system_chat_approved_tools,
                    system_chat_context_limit = excluded.system_chat_context_limit,
                    system_chat_enabled = excluded.system_chat_enabled,
                    assignment_history_auto_purge_days = excluded.assignment_history_auto_purge_days,
                    retain_temp_work = excluded.retain_temp_work
                """,
            SETTINGS_ID,
            settings.defaultAgentId(),
            settings.defaultAgentName(),
            settings.defaultModel(),
            settings.planningModel(),
            settings.summaryModel(),
            settings.compactionModel(),
            settings.contextBufferPercent(),
            settings.systemChatModel(),
            settings.systemChatPrompt(),
            settings.systemChatApprovedTools(),
            settings.systemChatContextLimit(),
            settings.systemChatEnabled() == null || settings.systemChatEnabled() ? 1 : 0,
            settings.assignmentHistoryAutoPurgeDays() == null ? -1 : settings.assignmentHistoryAutoPurgeDays(),
            Boolean.TRUE.equals(settings.retainTempWork()) ? 1 : 0
        );
        return find().orElseThrow();
    }

    private void ensureSchema() {
        jdbcTemplate.execute("""
            create table if not exists runtime_settings (
                id text primary key,
                default_agent_id text,
                default_agent_name text,
                default_model text,
                planning_model text,
                summary_model text,
                compaction_model text,
                context_buffer_percent integer,
                system_chat_model text,
                system_chat_prompt text,
                system_chat_approved_tools text,
                system_chat_context_limit integer,
                system_chat_enabled integer,
                assignment_history_auto_purge_days integer not null default -1,
                retain_temp_work integer not null default 0
            )
            """);
        ensureColumn("system_chat_model", "text");
        ensureColumn("system_chat_prompt", "text");
        ensureColumn("system_chat_approved_tools", "text");
        ensureColumn("system_chat_context_limit", "integer");
        ensureColumn("system_chat_enabled", "integer");
        ensureColumn("assignment_history_auto_purge_days", "integer not null default -1");
        ensureColumn("retain_temp_work", "integer not null default 0");
    }

    private void ensureColumn(String columnName, String type) {
        Boolean exists = jdbcTemplate.query("pragma table_info(runtime_settings)",
            rs -> {
                while (rs.next()) {
                    if (columnName.equalsIgnoreCase(rs.getString("name"))) {
                        return true;
                    }
                }
                return false;
            });
        if (!Boolean.TRUE.equals(exists)) {
            jdbcTemplate.execute("alter table runtime_settings add column " + columnName + " " + type);
        }
    }
}
