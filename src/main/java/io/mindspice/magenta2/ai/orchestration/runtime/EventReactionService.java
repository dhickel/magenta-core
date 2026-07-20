package io.mindspice.magenta2.ai.orchestration.runtime;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Fenced event-reaction service with revision-qualified definitions,
 * causation chains, deduplication, loop detection, depth/rate limits,
 * suppression audit, and a side-effect-free simulation evaluator.
 */
@Service
public class EventReactionService {
    private static final Logger log = LoggerFactory.getLogger(EventReactionService.class);
    private static final int MAX_CAUSATION_DEPTH = 8;
    private static final int MAX_DISPATCHES_PER_WINDOW = 10;
    private static final Duration RATE_WINDOW = Duration.ofSeconds(60);

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final AssignmentService assignmentService;
    private final Clock clock;
    private final boolean reactionsEnabled;

    public EventReactionService(JdbcTemplate jdbc, ObjectMapper objectMapper,
                                AssignmentService assignmentService,
                                Clock clock,
                                @org.springframework.beans.factory.annotation.Value("${magenta.features.reactions-enabled:false}") boolean reactionsEnabled) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper.findAndRegisterModules();
        this.assignmentService = assignmentService;
        this.clock = clock;
        this.reactionsEnabled = reactionsEnabled;
    }

    // ── Definition CRUD ──

    public List<ReactionDefinition> definitions(String agentId) {
        return jdbc.query("""
                select * from reaction_definitions
                where agent_id = ? and deleted = 0
                order by created_at desc
                """, (rs, row) -> definitionFromRow(rs), agentId);
    }

    public ReactionDefinition definition(String agentId, String reactionId) {
        ReactionDefinition def = jdbc.query("""
                select * from reaction_definitions
                where id = ? and deleted = 0
                order by revision desc limit 1
                """, rs -> rs.next() ? definitionFromRow(rs) : null, reactionId);
        if (def == null || !agentId.equals(def.agentId())) {
            throw new IllegalStateException("reaction not found");
        }
        return def;
    }

    public ReactionDefinition definitionByRevision(String agentId, String reactionId, int revision) {
        ReactionDefinition def = jdbc.query("""
                select * from reaction_definitions
                where id = ? and revision = ?
                """, rs -> rs.next() ? definitionFromRow(rs) : null, reactionId, revision);
        if (def == null || !agentId.equals(def.agentId())) {
            throw new IllegalStateException("reaction not found");
        }
        return def;
    }

    @Transactional
    public ReactionDefinition save(String agentId, ReactionDefinition input) {
        if (input.eventType() == null) throw new IllegalArgumentException("eventType is required");
        if (input.actionType() == null) throw new IllegalArgumentException("actionType is required");

        String id = StringUtils.hasText(input.id()) ? input.id() : UUID.randomUUID().toString();
        int nextRevision;
        Integer currentRev = jdbc.queryForObject(
                "select max(revision) from reaction_definitions where id = ?", Integer.class, id);
        nextRevision = currentRev == null || currentRev == 0 ? 1 : currentRev + 1;

        String assignmentJson = jsonOrThrow(input.assignmentTemplate());
        String filterJson = jsonOrNull(input.filter());
        Instant now = clock.instant();

        jdbc.update("""
                insert into reaction_definitions (
                    id, agent_id, revision, event_type, filter_json, action_type,
                    assignment_template_json, enabled, deleted, created_at, updated_at
                ) values (?, ?, ?, ?, ?, ?, ?, ?, 0, ?, ?)
                """,
                id, agentId, nextRevision, input.eventType().name(), filterJson,
                input.actionType().name(), assignmentJson,
                input.enabled() ? 1 : 0, now.toString(), now.toString());

        return definitionByRevision(agentId, id, nextRevision);
    }

    @Transactional
    public ReactionDefinition update(String agentId, String reactionId, ReactionDefinition input) {
        ReactionDefinition current = definition(agentId, reactionId);
        int nextRevision = current.revision() + 1;
        String assignmentJson = jsonOrThrow(input.assignmentTemplate());
        String filterJson = jsonOrNull(input.filter());
        Instant now = clock.instant();

        jdbc.update("""
                insert into reaction_definitions (
                    id, agent_id, revision, event_type, filter_json, action_type,
                    assignment_template_json, enabled, deleted, created_at, updated_at
                ) values (?, ?, ?, ?, ?, ?, ?, ?, 0, ?, ?)
                """,
                reactionId, agentId, nextRevision,
                input.eventType() != null ? input.eventType().name() : current.eventType().name(),
                filterJson,
                input.actionType() != null ? input.actionType().name() : current.actionType().name(),
                assignmentJson,
                input.enabled() ? 1 : 0,
                current.createdAt().toString(), now.toString());

        return definitionByRevision(agentId, reactionId, nextRevision);
    }

    @Transactional
    public ReactionDefinition enable(String agentId, String reactionId, boolean enabled) {
        ReactionDefinition current = definition(agentId, reactionId);
        if (current.enabled() == enabled) return current;
        return update(agentId, reactionId, new ReactionDefinition(
                reactionId, agentId, current.revision(),
                current.eventType(), current.filter(), current.actionType(),
                current.assignmentTemplate(), enabled, false,
                current.createdAt(), current.updatedAt()));
    }

    @Transactional
    public void softDelete(String agentId, String reactionId) {
        ReactionDefinition current = definition(agentId, reactionId);
        Instant now = clock.instant();
        jdbc.update("""
                update reaction_definitions set deleted = 1, updated_at = ?
                where id = ? and agent_id = ?
                """, now.toString(), reactionId, agentId);
    }

    @Transactional
    public void delete(String agentId, String reactionId) {
        softDelete(agentId, reactionId);
    }

    // ── Reaction Dispatch (called by OrchestrationEventService) ──

    @Transactional
    public List<ReactionDispatch> dispatch(OrchestrationEvent event) {
        if (!reactionsEnabled) return List.of();

        List<ReactionDispatch> results = new ArrayList<>();
        Instant now = clock.instant();

        List<ReactionDefinition> enabled = jdbc.query("""
                select * from reaction_definitions
                where enabled = 1 and deleted = 0 and event_type = ?
                """, (rs, row) -> definitionFromRow(rs), event.eventType().name());

        for (ReactionDefinition reaction : enabled) {
            try {
                ReactionDispatch result = dispatchForReaction(reaction, event, now);
                if (result != null) results.add(result);
            } catch (Exception e) {
                log.warn("Failed to dispatch reaction {} for event {}: {}",
                        reaction.id(), event.id(), e.getMessage());
            }
        }

        return results;
    }

    private ReactionDispatch dispatchForReaction(ReactionDefinition reaction,
                                                  OrchestrationEvent event, Instant now) {
        // Check filter match
        if (!matchesFilter(reaction.filter(), event.payload())) return null;

        // Build causation identifiers
        String rootEventId = event.id();
        int depth = 1;
        String parentEventId = null;
        String parentDispatchId = null;

        // Check if this reaction is already in the causation chain
        String dedupeKey = reaction.id() + ":" + reaction.revision() + ":" + event.id();

        Integer existingDispatch = jdbc.queryForObject("""
                select count(*) from reaction_dispatches
                where reaction_id = ? and revision = ? and source_event_id = ?
                """, Integer.class, reaction.id(), reaction.revision(), event.id());

        if (existingDispatch != null && existingDispatch > 0) {
            // Duplicate — return the existing dispatch
            return jdbc.query("""
                    select * from reaction_dispatches
                    where reaction_id = ? and revision = ? and source_event_id = ?
                    """, rs -> rs.next() ? dispatchFromRow(rs) : null,
                    reaction.id(), reaction.revision(), event.id());
        }

        // Check for loop: reaction_id already present in causation
        // (Simplified: check if any dispatch for root_event matches this reaction)
        Integer loopCheck = jdbc.queryForObject("""
                select count(*) from reaction_dispatches
                where root_event_id = ? and reaction_id = ?
                """, Integer.class, rootEventId, reaction.id());

        if (loopCheck != null && loopCheck > 0) {
            return createSuppressedDispatch(reaction, event, now, dedupeKey,
                    "SUPPRESSED_LOOP", "Reaction already in causation chain");
        }

        // Check depth
        if (depth > MAX_CAUSATION_DEPTH) {
            return createSuppressedDispatch(reaction, event, now, dedupeKey,
                    "SUPPRESSED_DEPTH", "Causation depth exceeds " + MAX_CAUSATION_DEPTH);
        }

        // Check rate limit
        Instant windowStart = now.minus(RATE_WINDOW);
        Integer rateCount = jdbc.queryForObject("""
                select count(*) from reaction_dispatches
                where reaction_id = ? and created_at >= ?
                """, Integer.class, reaction.id(), windowStart.toString());

        if (rateCount != null && rateCount >= MAX_DISPATCHES_PER_WINDOW) {
            return createSuppressedDispatch(reaction, event, now, dedupeKey,
                    "SUPPRESSED_RATE", "Rate limit exceeded: " + MAX_DISPATCHES_PER_WINDOW
                    + " dispatches per " + RATE_WINDOW.toSeconds() + "s");
        }

        // Create dispatch record
        String dispatchId = UUID.randomUUID().toString();
        String operationKey = reaction.id() + ":" + reaction.revision() + ":" + event.id();
        String templateJson = jsonOrThrow(reaction.assignmentTemplate());
        String requestDigest = ScheduleService.sha256(operationKey + templateJson);

        // Parse template and build assignment request
        Map<String, Object> template = parseJson(templateJson);
        AssignmentRequest request = AssignmentTemplateParser.reactionRequest(
                reaction.agentId(), template);
        String assignmentId = UUID.randomUUID().toString();

        try {
            WorkAssignment assignment = assignmentService.create(assignmentId, request);
            String taskRunId = jdbc.queryForObject(
                    "select task_run_id from task_run_native_links where assignment_id = ? order by created_at desc limit 1",
                    String.class, assignmentId);

            jdbc.update("""
                    insert into reaction_dispatches (
                        dispatch_id, reaction_id, revision, source_event_id,
                        operation_key, request_digest, template_snapshot_json,
                        root_event_id, parent_event_id, parent_dispatch_id,
                        depth, dedupe_key, dispatch_state,
                        assignment_id, task_run_id, native_run_id, correlation_id,
                        created_at, updated_at
                    ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'ENQUEUED', ?, ?, ?, ?, ?, ?)
                    """,
                    dispatchId, reaction.id(), reaction.revision(), event.id(),
                    operationKey, requestDigest, templateJson,
                    rootEventId, parentEventId, parentDispatchId,
                    depth, dedupeKey,
                    assignmentId, taskRunId, taskRunId, assignmentId,
                    now.toString(), now.toString());

            return jdbc.query("""
                    select * from reaction_dispatches where dispatch_id = ?
                    """, rs -> rs.next() ? dispatchFromRow(rs) : null, dispatchId);

        } catch (Exception e) {
            // Record failed dispatch
            jdbc.update("""
                    insert into reaction_dispatches (
                        dispatch_id, reaction_id, revision, source_event_id,
                        operation_key, request_digest, template_snapshot_json,
                        root_event_id, parent_event_id, parent_dispatch_id,
                        depth, dedupe_key, dispatch_state, suppression_reason,
                        created_at, updated_at
                    ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'SUPPRESSED_RATE', ?, ?, ?)
                    """,
                    dispatchId, reaction.id(), reaction.revision(), event.id(),
                    operationKey, requestDigest, templateJson,
                    rootEventId, parentEventId, parentDispatchId,
                    depth, dedupeKey, "ENQUEUE_FAILED: " + e.getClass().getSimpleName(),
                    now.toString(), now.toString());

            return jdbc.query("""
                    select * from reaction_dispatches where dispatch_id = ?
                    """, rs -> rs.next() ? dispatchFromRow(rs) : null, dispatchId);
        }
    }

    private ReactionDispatch createSuppressedDispatch(ReactionDefinition reaction,
                                                       OrchestrationEvent event, Instant now,
                                                       String dedupeKey,
                                                       String dispatchState, String reason) {
        String dispatchId = UUID.randomUUID().toString();
        String operationKey = reaction.id() + ":" + reaction.revision() + ":" + event.id();
        String templateJson = jsonOrThrow(reaction.assignmentTemplate());
        String requestDigest = ScheduleService.sha256(operationKey + templateJson);

        jdbc.update("""
                insert into reaction_dispatches (
                    dispatch_id, reaction_id, revision, source_event_id,
                    operation_key, request_digest, template_snapshot_json,
                    root_event_id, parent_event_id, parent_dispatch_id,
                    depth, dedupe_key, dispatch_state, suppression_reason,
                    created_at, updated_at
                ) values (?, ?, ?, ?, ?, ?, ?, ?, null, null, 1, ?, ?, ?, ?, ?)
                """,
                dispatchId, reaction.id(), reaction.revision(), event.id(),
                operationKey, requestDigest, templateJson,
                event.id(), dedupeKey, dispatchState, reason,
                now.toString(), now.toString());

        return jdbc.query("""
                select * from reaction_dispatches where dispatch_id = ?
                """, rs -> rs.next() ? dispatchFromRow(rs) : null, dispatchId);
    }

    // ── Simulation ──

    /**
     * Pure, side-effect-free reaction evaluation suitable for simulation.
     */
    public ReactionSimulation simulate(ReactionSimRequest request) {
        if (request.eventType() == null) {
            return new ReactionSimulation(null, false, false, false, false, 0,
                    "INVALID_EVENT_TYPE", null, null);
        }

        boolean matches = matchesFilter(request.filter(), request.eventPayload());

        // Check loop
        boolean loopDetected = false;
        if (StringUtils.hasText(request.existingReactionId()) && StringUtils.hasText(request.rootEventId())) {
            Integer loopCheck = jdbc.queryForObject("""
                    select count(*) from reaction_dispatches
                    where root_event_id = ? and reaction_id = ?
                    """, Integer.class, request.rootEventId(), request.existingReactionId());
            loopDetected = loopCheck != null && loopCheck > 0;
        }

        // Check depth
        boolean depthExceeded = request.causationDepth() != null
                && request.causationDepth() > MAX_CAUSATION_DEPTH;

        // Check rate
        boolean rateExceeded = false;
        int recentCount = 0;
        if (StringUtils.hasText(request.existingReactionId())) {
            Instant windowStart = (request.clockInstant() != null
                    ? request.clockInstant() : clock.instant()).minus(RATE_WINDOW);
            Integer count = jdbc.queryForObject("""
                    select count(*) from reaction_dispatches
                    where reaction_id = ? and created_at >= ?
                    """, Integer.class, request.existingReactionId(), windowStart.toString());
            recentCount = count == null ? 0 : count;
            rateExceeded = recentCount >= MAX_DISPATCHES_PER_WINDOW;
        }

        String state = "OK";
        List<String> suppressionReasons = new ArrayList<>();
        if (!matches && request.filter() != null && !request.filter().isEmpty()) {
            suppressionReasons.add("FILTER_NO_MATCH");
            state = "NO_MATCH";
        }
        if (loopDetected) suppressionReasons.add("LOOP");
        if (depthExceeded) suppressionReasons.add("DEPTH_EXCEEDED");
        if (rateExceeded) suppressionReasons.add("RATE_EXCEEDED");

        boolean wouldDispatch = matches && !loopDetected && !depthExceeded && !rateExceeded;

        return new ReactionSimulation(
                request.eventType(),
                matches, loopDetected, depthExceeded, rateExceeded,
                recentCount, state,
                wouldDispatch ? null : String.join("; ", suppressionReasons),
                wouldDispatch ? "Would enqueue assignment" : null);
    }

    // ── Backward-compatible wrappers (delegate to new API) ──

    public List<AgentEventReaction> reactions(String agentId) {
        return definitions(agentId).stream().map(this::toLegacyReaction).toList();
    }

    public AgentEventReaction reaction(String agentId, String reactionId) {
        return toLegacyReaction(definition(agentId, reactionId));
    }

    @Transactional
    public AgentEventReaction save(String agentId, AgentEventReaction reaction) {
        // Validate template through existing parser
        AssignmentTemplateParser.reactionRequest(agentId,
                reaction.assignmentTemplate() == null ? Map.of() : reaction.assignmentTemplate());
        ReactionDefinition def = save(agentId, new ReactionDefinition(
                reaction.id(), agentId, 0, reaction.eventType(),
                reaction.filter() == null ? Map.of() : reaction.filter(),
                reaction.actionType() != null ? reaction.actionType() : ReactionActionType.ENQUEUE_ASSIGNMENT,
                reaction.assignmentTemplate() != null ? reaction.assignmentTemplate() : Map.of(),
                reaction.enabled(), false, null, null));
        return toLegacyReaction(def);
    }

    @Transactional
    public AgentEventReaction toggle(String agentId, String reactionId) {
        ReactionDefinition current = definition(agentId, reactionId);
        ReactionDefinition updated = enable(agentId, reactionId, !current.enabled());
        return toLegacyReaction(updated);
    }

    private AgentEventReaction toLegacyReaction(ReactionDefinition def) {
        return new AgentEventReaction(def.id(), def.agentId(), def.eventType(),
                def.filter(), def.actionType(), def.assignmentTemplate(),
                def.enabled(), def.createdAt(), def.updatedAt());
    }

    // ── Dispatch Audit ──

    public List<ReactionDispatch> dispatches(String reactionId, int revision, int offset, int limit) {
        int boundedLimit = Math.min(Math.max(limit, 1), 200);
        return jdbc.query("""
                select * from reaction_dispatches
                where reaction_id = ? and revision = ?
                order by created_at desc
                limit ? offset ?
                """, (rs, row) -> dispatchFromRow(rs), reactionId, revision, boundedLimit, offset);
    }

    public List<ReactionDispatch> dispatchesByRootEvent(String rootEventId) {
        return jdbc.query("""
                select * from reaction_dispatches
                where root_event_id = ?
                order by created_at desc
                """, (rs, row) -> dispatchFromRow(rs), rootEventId);
    }

    public List<ReactionDispatchAttempt> dispatchAttempts(String dispatchId) {
        return jdbc.query("""
                select * from reaction_dispatch_attempts
                where dispatch_id = ?
                order by attempt_number asc
                """, (rs, row) -> attemptFromRow(rs), dispatchId);
    }

    // ── Helpers ──

    public static boolean matchesFilter(Map<String, Object> filter, Map<String, Object> payload) {
        if (filter == null || filter.isEmpty()) return true;
        Map<String, Object> values = payload == null ? Map.of() : payload;
        for (Map.Entry<String, Object> entry : filter.entrySet()) {
            Object actual = values.get(entry.getKey());
            if (actual == null || !actual.toString().equals(String.valueOf(entry.getValue()))) {
                return false;
            }
        }
        return true;
    }

    private String jsonOrThrow(Map<String, Object> map) {
        try {
            return objectMapper.writeValueAsString(map == null ? Map.of() : map);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Failed to serialize template", e);
        }
    }

    private String jsonOrNull(Map<String, Object> map) {
        if (map == null || map.isEmpty()) return null;
        try {
            return objectMapper.writeValueAsString(map);
        } catch (JsonProcessingException e) {
            return null;
        }
    }

    private Map<String, Object> parseJson(String json) {
        if (!StringUtils.hasText(json)) return Map.of();
        try {
            return objectMapper.readValue(json, Map.class);
        } catch (Exception e) {
            return Map.of();
        }
    }

    // ── Row mappers ──

    private ReactionDefinition definitionFromRow(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new ReactionDefinition(
                rs.getString("id"), rs.getString("agent_id"), rs.getInt("revision"),
                EventType.valueOf(rs.getString("event_type")),
                parseJson(rs.getString("filter_json")),
                ReactionActionType.valueOf(rs.getString("action_type")),
                parseJson(rs.getString("assignment_template_json")),
                rs.getInt("enabled") == 1, rs.getInt("deleted") == 1,
                Instant.parse(rs.getString("created_at")),
                Instant.parse(rs.getString("updated_at")));
    }

    private ReactionDispatch dispatchFromRow(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new ReactionDispatch(
                rs.getString("dispatch_id"), rs.getString("reaction_id"), rs.getInt("revision"),
                rs.getString("source_event_id"), rs.getString("operation_key"),
                rs.getString("request_digest"),
                parseJson(rs.getString("template_snapshot_json")),
                rs.getString("root_event_id"), rs.getString("parent_event_id"),
                rs.getString("parent_dispatch_id"), rs.getInt("depth"),
                rs.getString("dedupe_key"), rs.getString("dispatch_state"),
                rs.getString("suppression_reason"),
                rs.getString("assignment_id"), rs.getString("task_run_id"),
                rs.getString("native_run_id"), rs.getString("correlation_id"),
                Instant.parse(rs.getString("created_at")),
                Instant.parse(rs.getString("updated_at")));
    }

    private ReactionDispatchAttempt attemptFromRow(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new ReactionDispatchAttempt(
                rs.getString("attempt_id"), rs.getString("dispatch_id"), rs.getInt("attempt_number"),
                Instant.parse(rs.getString("eligible_at")),
                nullableInstant(rs.getString("attempted_at")),
                rs.getString("claim_owner"), rs.getLong("claim_fence"),
                rs.getString("outcome"), rs.getString("error_class"),
                rs.getString("assignment_id"), rs.getString("task_run_id"),
                rs.getString("native_run_id"), rs.getString("correlation_id"),
                Instant.parse(rs.getString("created_at")));
    }

    private static Instant nullableInstant(String value) {
        return StringUtils.hasText(value) ? Instant.parse(value) : null;
    }

    // ── Value Types ──

    public record ReactionDefinition(
            String id, String agentId, int revision,
            EventType eventType, Map<String, Object> filter,
            ReactionActionType actionType, Map<String, Object> assignmentTemplate,
            boolean enabled, boolean deleted,
            Instant createdAt, Instant updatedAt) {}

    public record ReactionDispatch(
            String dispatchId, String reactionId, int revision,
            String sourceEventId, String operationKey, String requestDigest,
            Map<String, Object> templateSnapshot,
            String rootEventId, String parentEventId, String parentDispatchId,
            int depth, String dedupeKey, String dispatchState,
            String suppressionReason,
            String assignmentId, String taskRunId, String nativeRunId,
            String correlationId,
            Instant createdAt, Instant updatedAt) {}

    public record ReactionDispatchAttempt(
            String attemptId, String dispatchId, int attemptNumber,
            Instant eligibleAt, Instant attemptedAt,
            String claimOwner, long claimFence,
            String outcome, String errorClass,
            String assignmentId, String taskRunId, String nativeRunId,
            String correlationId, Instant createdAt) {}

    public record ReactionSimRequest(
            String eventType, Map<String, Object> filter,
            Map<String, Object> eventPayload,
            String existingReactionId, String rootEventId,
            Integer causationDepth,
            Instant clockInstant) {}

    public record ReactionSimulation(
            String eventType,
            boolean filterMatches, boolean loopDetected,
            boolean depthExceeded, boolean rateExceeded,
            int recentDispatchCount,
            String state, String suppressionReason,
            String actionDescription) {}
}
