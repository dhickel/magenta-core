package io.mindspice.magenta2.ai.orchestration.runtime;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Typed event publication service. Events are persisted with payload, then
 * dispatched through {@link EventReactionService} for reaction evaluation.
 *
 * <p>Reaction dispatch is causal, with deduplication, loop detection, and
 * rate limits enforced at the reaction service boundary.
 */
@Service
public class OrchestrationEventService {
    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final AssignmentService assignmentService;
    private final EventReactionService reactionService;
    private final boolean reactionsEnabled;

    public OrchestrationEventService(JdbcTemplate jdbc, ObjectMapper objectMapper,
                                     AssignmentService assignmentService,
                                     EventReactionService reactionService,
                                     @org.springframework.beans.factory.annotation.Value("${magenta.features.reactions-enabled:false}") boolean reactionsEnabled) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper.findAndRegisterModules();
        this.assignmentService = assignmentService;
        this.reactionService = reactionService;
        this.reactionsEnabled = reactionsEnabled;
    }

    @Transactional
    public OrchestrationEvent publish(EventType eventType, String sourceType, String sourceId,
                                       Map<String, Object> payload) {
        String eventId = UUID.randomUUID().toString();
        Instant now = Instant.now();

        jdbc.update("""
                insert into orchestration_events (
                    id, event_type, source_type, source_id, payload_json, created_at, handled_at
                ) values (?, ?, ?, ?, ?, ?, ?)
                """,
                eventId, eventType.name(), sourceType, sourceId,
                jsonOrNull(payload == null ? Map.of() : payload),
                now.toString(), null);

        OrchestrationEvent event = findEvent(eventId);
        if (event == null) throw new IllegalStateException("Failed to persist event");

        handle(event);
        return findEvent(eventId);
    }

    @Transactional
    public void handle(OrchestrationEvent event) {
        if (!reactionsEnabled) {
            markHandled(event.id());
            return;
        }

        // Dispatch through the reaction service
        List<EventReactionService.ReactionDispatch> dispatches =
                reactionService.dispatch(event);

        // Auto-resume WAITING assignments for inbox messages
        if (event.eventType() == EventType.INBOX_MESSAGE_RECEIVED) {
            String agentId = stringValue(event.payload(), "toAgentId");
            if (StringUtils.hasText(agentId)) {
                List<Map<String, Object>> waiting = jdbc.queryForList("""
                        select id from work_assignments
                        where agent_id = ? and status = ?
                        and assignment_type = ?
                        """, agentId, OrchestrationStatus.WAITING.name(),
                        AssignmentType.WAIT_FOR_MESSAGE.name());
                for (Map<String, Object> row : waiting) {
                    try {
                        assignmentService.resume((String) row.get("id"));
                    } catch (Exception e) {
                        // Ignore individual resume failures
                    }
                }
            }
        }

        markHandled(event.id());
    }

    private void markHandled(String eventId) {
        jdbc.update("""
                update orchestration_events set handled_at = ?
                where id = ? and handled_at is null
                """, Instant.now().toString(), eventId);
    }

    public OrchestrationEvent findEvent(String id) {
        return jdbc.query("select * from orchestration_events where id = ?",
                rs -> rs.next() ? eventFromRow(rs) : null, id);
    }

    public List<OrchestrationEvent> findEventsForSource(String sourceType, String sourceId) {
        return jdbc.query("""
                select * from orchestration_events
                where source_type = ? and source_id = ?
                order by created_at desc
                """, (rs, row) -> eventFromRow(rs), sourceType, sourceId);
    }

    public List<OrchestrationEvent> findEventsForSourcePaginated(
            String sourceType, String sourceId, int offset, int limit) {
        int boundedLimit = Math.min(Math.max(limit, 1), 200);
        return jdbc.query("""
                select * from orchestration_events
                where source_type = ? and source_id = ?
                order by created_at desc
                limit ? offset ?
                """, (rs, row) -> eventFromRow(rs), sourceType, sourceId, boundedLimit, offset);
    }

    // ── Helpers ──

    private String jsonOrNull(Map<String, Object> payload) {
        if (payload == null || payload.isEmpty()) return null;
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            return null;
        }
    }

    private String stringValue(Map<String, Object> values, String key) {
        if (values == null || values.get(key) == null) return null;
        return values.get(key).toString();
    }

    private OrchestrationEvent eventFromRow(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new OrchestrationEvent(
                rs.getString("id"),
                EventType.valueOf(rs.getString("event_type")),
                rs.getString("source_type"),
                rs.getString("source_id"),
                parseJson(rs.getString("payload_json")),
                Instant.parse(rs.getString("created_at")),
                nullableInstant(rs.getString("handled_at")));
    }

    private Map<String, Object> parseJson(String json) {
        if (!StringUtils.hasText(json)) return Map.of();
        try {
            return objectMapper.readValue(json, Map.class);
        } catch (Exception e) {
            return Map.of();
        }
    }

    private static Instant nullableInstant(String value) {
        return StringUtils.hasText(value) ? Instant.parse(value) : null;
    }
}
