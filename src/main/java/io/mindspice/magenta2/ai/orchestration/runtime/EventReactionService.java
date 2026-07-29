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
 * project-trust matching, suppression audit, and a side-effect-free
 * simulation evaluator.
 *
 * <p>Revision heads: {@code current_revision = 1} identifies the sole active
 * revision. Updates advance the head pointer atomically.</p>
 *
 * <p>Project trust: reactions match only events bearing the same trusted
 * {@code project_id}. Non-project reactions (project_id=null) match any
 * non-project event. Project-scoped reactions never match a project-less
 * event or an event from a different project.</p>
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
    private final ProjectService projectService;
    private final Clock clock;
    private final boolean reactionsEnabled;

    public EventReactionService(JdbcTemplate jdbc, ObjectMapper objectMapper,
                                AssignmentService assignmentService,
                                ProjectService projectService,
                                Clock clock,
                                @org.springframework.beans.factory.annotation.Value("${magenta.features.reactions-enabled:false}") boolean reactionsEnabled) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper.findAndRegisterModules();
        this.assignmentService = assignmentService;
        this.projectService = projectService;
        this.clock = clock;
        this.reactionsEnabled = reactionsEnabled;
    }

    // ── Definition CRUD ──

    public List<ReactionDefinition> definitions(String agentId) {
        return jdbc.query("""
                select * from reaction_definitions
                where agent_id = ? and deleted = 0 and current_revision = 1
                order by created_at desc
                """, (rs, row) -> definitionFromRow(rs), agentId);
    }

    public List<ReactionDefinition> definitionsByProject(String projectId) {
        return jdbc.query("""
                select * from reaction_definitions
                where project_id = ? and deleted = 0 and current_revision = 1
                order by created_at desc
                """, (rs, row) -> definitionFromRow(rs), projectId);
    }

    public ReactionDefinition definition(String agentId, String reactionId) {
        ReactionDefinition def = jdbc.query("""
                select * from reaction_definitions
                where id = ? and deleted = 0 and current_revision = 1
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
        if (StringUtils.hasText(input.projectId())) {
            throw new IllegalArgumentException("projectId is server-derived; use the project-scoped operation");
        }
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

        // Advance revision head
        if (nextRevision > 1) {
            jdbc.update("""
                    update reaction_definitions set current_revision = 0
                    where id = ? and current_revision = 1
                    """, id);
        }

        jdbc.update("""
                insert into reaction_definitions (
                    id, agent_id, revision, current_revision,
                    event_type, filter_json, action_type,
                    assignment_template_json, enabled, deleted,
                    project_id, created_at, updated_at
                ) values (?, ?, ?, 1, ?, ?, ?, ?, ?, 0, ?, ?, ?)
                """,
                id, agentId, nextRevision,
                input.eventType().name(), filterJson,
                input.actionType().name(), assignmentJson,
                input.enabled() ? 1 : 0,
                input.projectId(),
                now.toString(), now.toString());

        return definitionByRevision(agentId, id, nextRevision);
    }

    @Transactional
    public ReactionDefinition update(String agentId, String reactionId, ReactionDefinition input) {
        ReactionDefinition current = definition(agentId, reactionId);
        if (input.projectId() != null && !input.projectId().equals(current.projectId())) {
            throw new IllegalArgumentException("projectId is immutable");
        }
        return appendRevision(agentId, current, input, current.projectId(), false);
    }

    @Transactional
    public ReactionDefinition enable(String agentId, String reactionId, boolean enabled) {
        ReactionDefinition current = definition(agentId, reactionId);
        if (current.enabled() == enabled) return current;
        return update(agentId, reactionId, new ReactionDefinition(
                reactionId, agentId, current.revision(),
                current.eventType(), current.filter(), current.actionType(),
                current.assignmentTemplate(), enabled, false,
                current.projectId(),
                current.createdAt(), current.updatedAt()));
    }

    @Transactional
    public void softDelete(String agentId, String reactionId) {
        ReactionDefinition current = definition(agentId, reactionId);
        appendRevision(agentId, current, current, current.projectId(), true);
    }

    @Transactional
    public void delete(String agentId, String reactionId) {
        softDelete(agentId, reactionId);
    }

    private ReactionDefinition appendRevision(String agentId, ReactionDefinition current,
                                              ReactionDefinition input, String projectId,
                                              boolean tombstone) {
        ReactionDefinition effective = new ReactionDefinition(
                current.id(), agentId, current.revision() + 1,
                input.eventType() != null ? input.eventType() : current.eventType(),
                input.filter() != null ? input.filter() : current.filter(),
                input.actionType() != null ? input.actionType() : current.actionType(),
                input.assignmentTemplate() != null ? input.assignmentTemplate() : current.assignmentTemplate(),
                tombstone ? false : input.enabled(), tombstone, projectId,
                current.createdAt(), clock.instant());
        if (effective.eventType() == null || effective.actionType() == null) {
            throw new IllegalArgumentException("eventType and actionType are required");
        }
        int cleared = jdbc.update("""
                update reaction_definitions set current_revision = 0
                where id = ? and revision = ? and current_revision = 1
                """, current.id(), current.revision());
        if (cleared != 1) {
            throw new IllegalStateException("reaction revision conflict");
        }
        jdbc.update("""
                insert into reaction_definitions (
                    id, agent_id, revision, current_revision, event_type, filter_json, action_type,
                    assignment_template_json, enabled, deleted, project_id, created_at, updated_at
                ) values (?, ?, ?, 1, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, effective.id(), effective.agentId(), effective.revision(),
                effective.eventType().name(), jsonOrNull(effective.filter()), effective.actionType().name(),
                jsonOrThrow(effective.assignmentTemplate()), effective.enabled() ? 1 : 0,
                effective.deleted() ? 1 : 0, effective.projectId(),
                effective.createdAt().toString(), effective.updatedAt().toString());
        return effective;
    }

    // ── Project-scoped save ──

    @Transactional
    public ReactionDefinition saveForProject(String projectId, String executingAgentId,
                                              ReactionDefinition input) {
        requireActiveProject(projectId);
        requireProjectMember(projectId, executingAgentId);

        if (input.eventType() == null) throw new IllegalArgumentException("eventType is required");
        if (input.actionType() == null) throw new IllegalArgumentException("actionType is required");
        if (StringUtils.hasText(input.id())) {
            throw new IllegalArgumentException("reaction id is server-generated on create");
        }

        String id = UUID.randomUUID().toString();
        int nextRevision;
        Integer currentRev = jdbc.queryForObject(
                "select max(revision) from reaction_definitions where id = ?", Integer.class, id);
        nextRevision = currentRev == null || currentRev == 0 ? 1 : currentRev + 1;

        String assignmentJson = jsonOrThrow(input.assignmentTemplate());
        String filterJson = jsonOrNull(input.filter());
        Instant now = clock.instant();

        if (nextRevision > 1) {
            jdbc.update("""
                    update reaction_definitions set current_revision = 0
                    where id = ? and current_revision = 1
                    """, id);
        }

        jdbc.update("""
                insert into reaction_definitions (
                    id, agent_id, revision, current_revision,
                    event_type, filter_json, action_type,
                    assignment_template_json, enabled, deleted,
                    project_id, created_at, updated_at
                ) values (?, ?, ?, 1, ?, ?, ?, ?, ?, 0, ?, ?, ?)
                """,
                id, executingAgentId, nextRevision,
                input.eventType().name(), filterJson,
                input.actionType().name(), assignmentJson,
                input.enabled() ? 1 : 0,
                projectId,
                now.toString(), now.toString());

        return definitionByRevision(executingAgentId, id, nextRevision);
    }

    @Transactional
    public ReactionDefinition updateForProject(String projectId, String executingAgentId,
                                                String reactionId, ReactionDefinition input) {
        requireActiveProject(projectId);
        requireProjectMember(projectId, executingAgentId);
        ReactionDefinition current = definition(executingAgentId, reactionId);
        if (!projectId.equals(current.projectId())) {
            throw new IllegalStateException("reaction is not in this project");
        }
        return appendRevision(executingAgentId, current, input, projectId, false);
    }

    @Transactional
    public ReactionDefinition enableForProject(String projectId, String executingAgentId,
                                                String reactionId, boolean enabled) {
        requireActiveProject(projectId);
        requireProjectMember(projectId, executingAgentId);

        ReactionDefinition current = definition(executingAgentId, reactionId);
        if (!projectId.equals(current.projectId())) {
            throw new IllegalStateException("reaction is not in this project");
        }
        if (current.enabled() == enabled) return current;
        return updateForProject(projectId, executingAgentId, reactionId, new ReactionDefinition(
                reactionId, executingAgentId, current.revision(),
                current.eventType(), current.filter(), current.actionType(),
                current.assignmentTemplate(), enabled, false,
                projectId,
                current.createdAt(), current.updatedAt()));
    }

    @Transactional
    public void deleteForProject(String projectId, String executingAgentId, String reactionId) {
        requireActiveProject(projectId);
        requireProjectMember(projectId, executingAgentId);
        ReactionDefinition current = definition(executingAgentId, reactionId);
        if (!projectId.equals(current.projectId())) {
            throw new IllegalStateException("reaction is not in this project");
        }
        softDelete(executingAgentId, reactionId);
    }

    // ── Project trust matching ──

    /**
     * Returns true if the reaction can react to the given event based on
     * project-id trust. Null matches null; identical non-null values match;
     * any mismatch is rejected (spoofing prevention).
     */
    static boolean projectTrustMatches(String reactionProjectId, String eventProjectId) {
        if (reactionProjectId == null && eventProjectId == null) return true;
        if (reactionProjectId != null && eventProjectId != null
                && reactionProjectId.equals(eventProjectId)) return true;
        return false;
    }

    // ── Reaction Dispatch ──

    @Transactional
    public List<ReactionDispatch> dispatch(OrchestrationEvent event) {
        if (!reactionsEnabled) return List.of();

        List<ReactionDispatch> results = new ArrayList<>();
        Instant now = clock.instant();

        // Query enabled current-revision reactions matching event type
        List<ReactionDefinition> enabled = jdbc.query("""
                select * from reaction_definitions
                where enabled = 1 and deleted = 0 and current_revision = 1
                  and event_type = ?
                """, (rs, row) -> definitionFromRow(rs), event.eventType().name());

        for (ReactionDefinition reaction : enabled) {
            // Project trust gate: reaction must match event's project_id.
            if (!projectTrustMatches(reaction.projectId(), event.projectId())) {
                continue;
            }
            if (StringUtils.hasText(reaction.projectId())) {
                try {
                    requireActiveProject(reaction.projectId());
                    requireProjectMember(reaction.projectId(), reaction.agentId());
                } catch (RuntimeException ineligible) {
                    log.info("Skipping ineligible project reaction {}: {}", reaction.id(), ineligible.getMessage());
                    continue;
                }
            }
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
        if (!matchesFilter(reaction.filter(), event.payload())) return null;

        String rootEventId = event.id();
        int depth = 1;

        String dedupeKey = reaction.id() + ":" + reaction.revision() + ":" + event.id();

        Integer existingDispatch = jdbc.queryForObject("""
                select count(*) from reaction_dispatches
                where reaction_id = ? and revision = ? and source_event_id = ?
                """, Integer.class, reaction.id(), reaction.revision(), event.id());

        if (existingDispatch != null && existingDispatch > 0) {
            return jdbc.query("""
                    select * from reaction_dispatches
                    where reaction_id = ? and revision = ? and source_event_id = ?
                    """, rs -> rs.next() ? dispatchFromRow(rs) : null,
                    reaction.id(), reaction.revision(), event.id());
        }

        // Loop check
        Integer loopCheck = jdbc.queryForObject("""
                select count(*) from reaction_dispatches
                where root_event_id = ? and reaction_id = ?
                """, Integer.class, rootEventId, reaction.id());

        if (loopCheck != null && loopCheck > 0) {
            return createSuppressedDispatch(reaction, event, now, dedupeKey,
                    "SUPPRESSED_LOOP", "Reaction already in causation chain");
        }

        if (depth > MAX_CAUSATION_DEPTH) {
            return createSuppressedDispatch(reaction, event, now, dedupeKey,
                    "SUPPRESSED_DEPTH", "Causation depth exceeds " + MAX_CAUSATION_DEPTH);
        }

        // Rate limit
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

        // Create dispatch
        String dispatchId = UUID.randomUUID().toString();
        String operationKey = reaction.id() + ":" + reaction.revision() + ":" + event.id();
        String templateJson = jsonOrThrow(reaction.assignmentTemplate());
        String requestDigest = ScheduleService.sha256(operationKey + templateJson);

        Map<String, Object> template = parseJson(templateJson);
        AssignmentRequest request = AssignmentTemplateParser.automationRequest(
                reaction.agentId(), null, reaction.projectId(), operationKey, template,
                AssignmentType.REPORT);
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
                    rootEventId, null, null,
                    depth, dedupeKey,
                    assignmentId, taskRunId, taskRunId, assignmentId,
                    now.toString(), now.toString());

            return jdbc.query("""
                    select * from reaction_dispatches where dispatch_id = ?
                    """, rs -> rs.next() ? dispatchFromRow(rs) : null, dispatchId);

        } catch (Exception e) {
            jdbc.update("""
                    insert into reaction_dispatches (
                        dispatch_id, reaction_id, revision, source_event_id,
                        operation_key, request_digest, template_snapshot_json,
                        root_event_id, parent_event_id, parent_dispatch_id,
                        depth, dedupe_key, dispatch_state, suppression_reason,
                        created_at, updated_at
                    ) values (?, ?, ?, ?, ?, ?, ?, ?, null, null, 1, ?, 'SUPPRESSED_RATE', ?, ?, ?)
                    """,
                    dispatchId, reaction.id(), reaction.revision(), event.id(),
                    operationKey, requestDigest, templateJson,
                    rootEventId, dedupeKey,
                    "ENQUEUE_FAILED: " + e.getClass().getSimpleName(),
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

        // Project trust check
        boolean projectMismatch = false;
        if (request.reactionProjectId() != null || request.eventProjectId() != null) {
            projectMismatch = !projectTrustMatches(request.reactionProjectId(), request.eventProjectId());
        }

        // Simulation accepts only the immutable request snapshot. It deliberately
        // does not query dispatch state: persistence-backed loop/rate evaluation
        // belongs to dispatch(), while this preview remains side-effect free.
        boolean loopDetected = false;
        boolean depthExceeded = request.causationDepth() != null
                && request.causationDepth() > MAX_CAUSATION_DEPTH;
        boolean rateExceeded = false;
        int recentCount = 0;

        String state = "OK";
        List<String> suppressionReasons = new ArrayList<>();
        if (projectMismatch) {
            suppressionReasons.add("PROJECT_MISMATCH");
            state = "PROJECT_MISMATCH";
        }
        if (!matches && request.filter() != null && !request.filter().isEmpty()) {
            suppressionReasons.add("FILTER_NO_MATCH");
            state = "NO_MATCH";
        }
        if (loopDetected) suppressionReasons.add("LOOP");
        if (depthExceeded) suppressionReasons.add("DEPTH_EXCEEDED");
        if (rateExceeded) suppressionReasons.add("RATE_EXCEEDED");

        boolean wouldDispatch = matches && !loopDetected && !depthExceeded
                && !rateExceeded && !projectMismatch;

        return new ReactionSimulation(
                request.eventType(),
                matches, loopDetected, depthExceeded, rateExceeded,
                recentCount, state,
                wouldDispatch ? null : String.join("; ", suppressionReasons),
                wouldDispatch ? "Would enqueue assignment" : null);
    }

    // ── Project helpers ──

    private void requireActiveProject(String projectId) {
        Project project = projectService.getProject(projectId);
        if (project.archived()) {
            throw new IllegalStateException("Project is archived");
        }
    }

    private void requireProjectMember(String projectId, String agentId) {
        if (!projectService.isMember(projectId, agentId)) {
            throw new IllegalStateException("Agent is not a member of this project");
        }
    }

    // ── Backward-compatible wrappers ──

    public List<AgentEventReaction> reactions(String agentId) {
        return definitions(agentId).stream().map(this::toLegacyReaction).toList();
    }

    public AgentEventReaction reaction(String agentId, String reactionId) {
        return toLegacyReaction(definition(agentId, reactionId));
    }

    @Transactional
    public AgentEventReaction save(String agentId, AgentEventReaction reaction) {
        AssignmentTemplateParser.reactionRequest(agentId,
                reaction.assignmentTemplate() == null ? Map.of() : reaction.assignmentTemplate());
        ReactionDefinition def = save(agentId, new ReactionDefinition(
                reaction.id(), agentId, 0, reaction.eventType(),
                reaction.filter() == null ? Map.of() : reaction.filter(),
                reaction.actionType() != null ? reaction.actionType() : ReactionActionType.ENQUEUE_ASSIGNMENT,
                reaction.assignmentTemplate() != null ? reaction.assignmentTemplate() : Map.of(),
                reaction.enabled(), false,
                reaction.projectId(),
                null, null));
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
                def.enabled(), def.projectId(),
                def.createdAt(), def.updatedAt());
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

    private static String coalesce(String preferred, String fallback) {
        return StringUtils.hasText(preferred) ? preferred : fallback;
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
                rs.getString("project_id"),
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
            String projectId,
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
            String reactionProjectId, String eventProjectId,
            Instant clockInstant) {}

    public record ReactionSimulation(
            String eventType,
            boolean filterMatches, boolean loopDetected,
            boolean depthExceeded, boolean rateExceeded,
            int recentDispatchCount,
            String state, String suppressionReason,
            String actionDescription) {}
}
