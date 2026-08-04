package io.mindspice.magenta2.ai.orchestration.runtime;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Fenced scheduler with revision-qualified definitions, owner+fence claims,
 * append-only firing audit, operation-key idempotency, retry, recovery,
 * project-scoped attribution, CRON/ONE_TIME exclusive modes, and a
 * side-effect-free simulation evaluator.
 *
 * <p>Revision heads: {@code current_revision = 1} identifies the sole active
 * revision. Updates advance the head pointer atomically (old {@code -> 0},
 * new {@code -> 1}). Historical revisions are immutable.</p>
 */
@Service
public class ScheduleService {
    private static final Logger log = LoggerFactory.getLogger(ScheduleService.class);
    private static final String DIALECT_VERSION = "spring-6-field-v1";
    private static final String POLICY_VERSION = "v1-gap-skip-overlap-fire-once";
    private static final int MAX_ENQUEUE_ATTEMPTS = 3;
    private static final long HEARTBEAT_INTERVAL_SECONDS = 30;
    private static final long CLAIM_EXPIRY_SECONDS = 60;
    private static final Duration CLAIM_TTL = Duration.ofSeconds(CLAIM_EXPIRY_SECONDS);

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final AssignmentService assignmentService;
    private final OrchestrationEventService eventService;
    private final ProjectService projectService;
    private final Clock clock;
    private final boolean schedulesEnabled;

    public ScheduleService(JdbcTemplate jdbc, ObjectMapper objectMapper,
                           AssignmentService assignmentService,
                           OrchestrationEventService eventService,
                           ProjectService projectService,
                           Clock clock,
                           @org.springframework.beans.factory.annotation.Value("${magenta.features.schedules-enabled:false}") boolean schedulesEnabled) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper.findAndRegisterModules();
        this.assignmentService = assignmentService;
        this.eventService = eventService;
        this.projectService = projectService;
        this.clock = clock;
        this.schedulesEnabled = schedulesEnabled;
    }

    // ── Definition CRUD ──

    public List<ScheduleDefinition> definitions(String agentId) {
        return jdbc.query("""
                select * from schedule_definitions
                where agent_id = ? and deleted = 0 and current_revision = 1
                order by created_at desc
                """, (rs, row) -> definitionFromRow(rs), agentId);
    }

    public List<ScheduleDefinition> definitionsByProject(String projectId) {
        return jdbc.query("""
                select * from schedule_definitions
                where project_id = ? and deleted = 0 and current_revision = 1
                order by created_at desc
                """, (rs, row) -> definitionFromRow(rs), projectId);
    }

    public ScheduleDefinition definition(String agentId, String scheduleId) {
        ScheduleDefinition def = jdbc.query("""
                select * from schedule_definitions
                where id = ? and deleted = 0 and current_revision = 1
                """, rs -> rs.next() ? definitionFromRow(rs) : null, scheduleId);
        if (def == null || !agentId.equals(def.agentId())) {
            throw new IllegalStateException("schedule not found");
        }
        return def;
    }

    public ScheduleDefinition definitionByRevision(String agentId, String scheduleId, int revision) {
        ScheduleDefinition def = jdbc.query("""
                select * from schedule_definitions
                where id = ? and revision = ?
                """, rs -> rs.next() ? definitionFromRow(rs) : null, scheduleId, revision);
        if (def == null || !agentId.equals(def.agentId())) {
            throw new IllegalStateException("schedule not found");
        }
        return def;
    }

    @Transactional
    public ScheduleDefinition save(String agentId, ScheduleDefinition input) {
        if (StringUtils.hasText(input.projectId())) {
            throw new IllegalArgumentException("projectId is server-derived; use the project-scoped operation");
        }
        String id = StringUtils.hasText(input.id()) ? input.id() : UUID.randomUUID().toString();
        int nextRevision;
        Integer currentRev = jdbc.queryForObject(
                "select max(revision) from schedule_definitions where id = ?", Integer.class, id);
        nextRevision = currentRev == null || currentRev == 0 ? 1 : currentRev + 1;

        validateMode(input);

        String assignmentJson = jsonOrThrow(input.assignmentTemplate());
        Instant now = clock.instant();

        // Advance revision head: clear current_revision on prior revisions
        if (nextRevision > 1) {
            jdbc.update("""
                    update schedule_definitions set current_revision = 0
                    where id = ? and current_revision = 1
                    """, id);
        }

        jdbc.update("""
                insert into schedule_definitions (
                    id, agent_id, revision, current_revision, mode,
                    cron_expression, timezone, fire_at,
                    dialect_version, policy_version,
                    enabled, deleted, project_id, job_id,
                    assignment_template_json, created_at, updated_at
                ) values (?, ?, ?, 1, ?, ?, ?, ?, ?, ?, ?, 0, ?, ?, ?, ?, ?)
                """,
                id, agentId, nextRevision,
                input.mode() != null ? input.mode().name() : ScheduleMode.CRON.name(),
                input.cronExpression(),
                input.timezone(),
                input.fireAt() != null ? input.fireAt().toString() : null,
                DIALECT_VERSION, POLICY_VERSION,
                input.enabled() ? 1 : 0,
                input.projectId(),
                input.jobId(),
                assignmentJson,
                now.toString(), now.toString());

        return definitionByRevision(agentId, id, nextRevision);
    }

    @Transactional
    public ScheduleDefinition update(String agentId, String scheduleId, ScheduleDefinition input) {
        ScheduleDefinition current = definition(agentId, scheduleId);
        if (input.projectId() != null && !input.projectId().equals(current.projectId())) {
            throw new IllegalArgumentException("projectId is immutable");
        }
        return appendRevision(agentId, current, input, current.projectId(), false);
    }

    @Transactional
    public ScheduleDefinition enable(String agentId, String scheduleId, boolean enabled) {
        ScheduleDefinition current = definition(agentId, scheduleId);
        if (current.enabled() == enabled) return current;
        return update(agentId, scheduleId, new ScheduleDefinition(
                scheduleId, agentId, current.revision(),
                current.mode(), current.cronExpression(), current.timezone(),
                current.fireAt(), enabled, false, current.projectId(),
                current.jobId(), current.assignmentTemplate(),
                current.dialectVersion(), current.policyVersion(),
                current.createdAt(), current.updatedAt()));
    }

    @Transactional
    public void softDelete(String agentId, String scheduleId) {
        ScheduleDefinition current = definition(agentId, scheduleId);
        appendRevision(agentId, current, current, current.projectId(), true);
    }

    @Transactional
    public void delete(String agentId, String scheduleId) {
        softDelete(agentId, scheduleId);
    }

    private ScheduleDefinition appendRevision(String agentId, ScheduleDefinition current,
                                              ScheduleDefinition input, String projectId,
                                              boolean tombstone) {
        ScheduleMode mode = input.mode() != null ? input.mode() : current.mode();
        String cron = mode == ScheduleMode.CRON
                ? (input.cronExpression() != null ? input.cronExpression() : current.cronExpression()) : null;
        String timezone = mode == ScheduleMode.CRON
                ? (input.timezone() != null ? input.timezone() : current.timezone()) : null;
        Instant fireAt = mode == ScheduleMode.ONE_TIME
                ? (input.fireAt() != null ? input.fireAt() : current.fireAt()) : null;
        ScheduleDefinition effective = new ScheduleDefinition(current.id(), agentId, current.revision() + 1,
                mode, cron, timezone, fireAt, tombstone ? false : input.enabled(), tombstone,
                projectId, input.jobId() != null ? input.jobId() : current.jobId(),
                input.assignmentTemplate() != null ? input.assignmentTemplate() : current.assignmentTemplate(),
                current.dialectVersion(), current.policyVersion(), current.createdAt(), clock.instant());
        if (!tombstone) {
            validateMode(effective);
        }
        Instant now = effective.updatedAt();
        int cleared = jdbc.update("""
                update schedule_definitions set current_revision = 0
                where id = ? and revision = ? and current_revision = 1
                """, current.id(), current.revision());
        if (cleared != 1) {
            throw new IllegalStateException("schedule revision conflict");
        }
        jdbc.update("""
                insert into schedule_definitions (
                    id, agent_id, revision, current_revision, mode,
                    cron_expression, timezone, fire_at, dialect_version, policy_version,
                    enabled, deleted, project_id, job_id, assignment_template_json, created_at, updated_at
                ) values (?, ?, ?, 1, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, effective.id(), effective.agentId(), effective.revision(), effective.mode().name(),
                effective.cronExpression(), effective.timezone(),
                effective.fireAt() == null ? null : effective.fireAt().toString(),
                effective.dialectVersion(), effective.policyVersion(), effective.enabled() ? 1 : 0,
                effective.deleted() ? 1 : 0, effective.projectId(), effective.jobId(),
                jsonOrThrow(effective.assignmentTemplate()), effective.createdAt().toString(), now.toString());
        return effective;
    }

    // ── Project-scoped save ──

    @Transactional
    public ScheduleDefinition saveForProject(String projectId, String executingAgentId,
                                              ScheduleDefinition input) {
        requireActiveProject(projectId);
        requireProjectMember(projectId, executingAgentId);
        if (StringUtils.hasText(input.id())) {
            throw new IllegalArgumentException("schedule id is server-generated on create");
        }

        String id = UUID.randomUUID().toString();
        int nextRevision;
        Integer currentRev = jdbc.queryForObject(
                "select max(revision) from schedule_definitions where id = ?", Integer.class, id);
        nextRevision = currentRev == null || currentRev == 0 ? 1 : currentRev + 1;

        validateMode(input);

        String assignmentJson = jsonOrThrow(input.assignmentTemplate());
        Instant now = clock.instant();

        if (nextRevision > 1) {
            jdbc.update("""
                    update schedule_definitions set current_revision = 0
                    where id = ? and current_revision = 1
                    """, id);
        }

        jdbc.update("""
                insert into schedule_definitions (
                    id, agent_id, revision, current_revision, mode,
                    cron_expression, timezone, fire_at,
                    dialect_version, policy_version,
                    enabled, deleted, project_id, job_id,
                    assignment_template_json, created_at, updated_at
                ) values (?, ?, ?, 1, ?, ?, ?, ?, ?, ?, ?, 0, ?, ?, ?, ?, ?)
                """,
                id, executingAgentId, nextRevision,
                input.mode() != null ? input.mode().name() : ScheduleMode.CRON.name(),
                input.cronExpression(), input.timezone(),
                input.fireAt() != null ? input.fireAt().toString() : null,
                DIALECT_VERSION, POLICY_VERSION,
                input.enabled() ? 1 : 0,
                projectId,
                input.jobId(),
                assignmentJson,
                now.toString(), now.toString());

        // Publish project-scoped event
        eventService.publishProject(EventType.SCHEDULE_CREATED, "SCHEDULE", id, projectId, Map.of(
                "scheduleId", id,
                "agentId", executingAgentId,
                "projectId", projectId,
                "revision", String.valueOf(nextRevision)
        ));

        return definitionByRevision(executingAgentId, id, nextRevision);
    }

    @Transactional
    public ScheduleDefinition updateForProject(String projectId, String executingAgentId,
                                                String scheduleId, ScheduleDefinition input) {
        requireActiveProject(projectId);
        requireProjectMember(projectId, executingAgentId);
        ScheduleDefinition current = definition(executingAgentId, scheduleId);
        if (!projectId.equals(current.projectId())) {
            throw new IllegalStateException("schedule is not in this project");
        }
        return appendRevision(executingAgentId, current, input, projectId, false);
    }

    @Transactional
    public ScheduleDefinition enableForProject(String projectId, String executingAgentId,
                                                String scheduleId, boolean enabled) {
        requireActiveProject(projectId);
        requireProjectMember(projectId, executingAgentId);

        ScheduleDefinition current = definition(executingAgentId, scheduleId);
        if (!projectId.equals(current.projectId())) {
            throw new IllegalStateException("schedule is not in this project");
        }
        if (current.enabled() == enabled) return current;
        return updateForProject(projectId, executingAgentId, scheduleId, new ScheduleDefinition(
                scheduleId, executingAgentId, current.revision(),
                current.mode(), current.cronExpression(), current.timezone(),
                current.fireAt(), enabled, false, projectId,
                current.jobId(), current.assignmentTemplate(),
                current.dialectVersion(), current.policyVersion(),
                current.createdAt(), current.updatedAt()));
    }

    @Transactional
    public void deleteForProject(String projectId, String executingAgentId, String scheduleId) {
        requireActiveProject(projectId);
        requireProjectMember(projectId, executingAgentId);
        ScheduleDefinition current = definition(executingAgentId, scheduleId);
        if (!projectId.equals(current.projectId())) {
            throw new IllegalStateException("schedule is not in this project");
        }
        softDelete(executingAgentId, scheduleId);
    }

    // ── Mode validation ──

    private void validateMode(ScheduleDefinition input) {
        ScheduleMode mode = input.mode() != null ? input.mode() : ScheduleMode.CRON;
        switch (mode) {
            case CRON -> {
                if (input.fireAt() != null) {
                    throw new IllegalArgumentException("fireAt is only valid for ONE_TIME mode");
                }
                if (!StringUtils.hasText(input.cronExpression())) {
                    throw new IllegalArgumentException("cronExpression is required for CRON mode");
                }
                if (!CronExpression.isValidExpression(input.cronExpression().trim())) {
                    throw new IllegalArgumentException("invalid cronExpression: " + input.cronExpression());
                }
                if (!StringUtils.hasText(input.timezone())) {
                    throw new IllegalArgumentException("timezone is required for CRON mode");
                }
                ZoneId.of(input.timezone().trim()); // validates
            }
            case ONE_TIME -> {
                if (StringUtils.hasText(input.cronExpression()) || StringUtils.hasText(input.timezone())) {
                    throw new IllegalArgumentException("cronExpression and timezone are only valid for CRON mode");
                }
                if (input.fireAt() == null) {
                    throw new IllegalArgumentException("fireAt is required for ONE_TIME mode");
                }
                if (!input.fireAt().isAfter(clock.instant())) {
                    throw new IllegalArgumentException("fireAt must be in the future for ONE_TIME mode");
                }
            }
        }
    }

    // ── Claim Management ──

    public String claimOwner() {
        try {
            String hostname = java.net.InetAddress.getLocalHost().getHostName();
            String pid = String.valueOf(ProcessHandle.current().pid());
            return hostname + ":" + pid;
        } catch (Exception e) {
            return "magenta:" + UUID.randomUUID();
        }
    }

    @Transactional
    public SchedulerClaim acquireOrRenewClaim(String ownerId) {
        Instant now = clock.instant();
        Instant heartbeatAt = now.plusSeconds(HEARTBEAT_INTERVAL_SECONDS);
        Instant expiresAt = now.plusSeconds(CLAIM_EXPIRY_SECONDS);

        SchedulerClaim existing = jdbc.query("""
                select * from scheduler_claims limit 1
                """, rs -> rs.next() ? claimFromRow(rs) : null);

        if (existing == null) {
            String id = UUID.randomUUID().toString();
            jdbc.update("""
                    insert into scheduler_claims (id, owner_id, fence, heartbeat_at, expires_at, claimed_at, created_at, updated_at)
                    values (?, ?, 0, ?, ?, ?, ?, ?)
                    """, id, ownerId, heartbeatAt.toString(), expiresAt.toString(), now.toString(), now.toString(), now.toString());
            return jdbc.query("select * from scheduler_claims where id = ?",
                    rs -> rs.next() ? claimFromRow(rs) : null, id);
        }

        if (existing.expiresAt().isAfter(now) && !existing.ownerId().equals(ownerId)) {
            return existing;
        }

        int updated = jdbc.update("""
                update scheduler_claims
                set owner_id = ?, fence = fence + 1, heartbeat_at = ?, expires_at = ?, updated_at = ?
                where id = ? and (expires_at <= ? or owner_id = ?)
                """, ownerId, heartbeatAt.toString(), expiresAt.toString(), now.toString(),
                existing.id(), now.toString(), ownerId);

        if (updated == 0) return existing;

        return jdbc.query("select * from scheduler_claims where id = ?",
                rs -> rs.next() ? claimFromRow(rs) : null, existing.id());
    }

    @Transactional
    public boolean heartbeat(String claimId, String ownerId, long expectedFence) {
        Instant now = clock.instant();
        Instant heartbeatAt = now.plusSeconds(HEARTBEAT_INTERVAL_SECONDS);
        Instant expiresAt = now.plusSeconds(CLAIM_EXPIRY_SECONDS);
        int updated = jdbc.update("""
                update scheduler_claims
                set heartbeat_at = ?, expires_at = ?, updated_at = ?
                where id = ? and owner_id = ? and fence = ?
                """, heartbeatAt.toString(), expiresAt.toString(), now.toString(),
                claimId, ownerId, expectedFence);
        return updated == 1;
    }

    public SchedulerClaim currentClaim() {
        return jdbc.query("select * from scheduler_claims order by updated_at desc limit 1",
                rs -> rs.next() ? claimFromRow(rs) : null);
    }

    // ── Scheduler Poll ──

    @Scheduled(fixedDelayString = "${magenta.orchestration.scheduler-delay-ms:10000}")
    @Transactional
    public void pollDueSchedules() {
        if (!schedulesEnabled) return;
        SchedulerClaim claim = currentClaim();
        if (claim == null) {
            claim = acquireOrRenewClaim(claimOwner());
            if (claim == null) return;
        } else if (claim.expiresAt().isBefore(clock.instant())) {
            claim = acquireOrRenewClaim(claimOwner());
        } else if (claim.ownerId().equals(claimOwner())) {
            heartbeat(claim.id(), claim.ownerId(), claim.fence());
        } else {
            return;
        }

        Instant now = clock.instant();

        // Select enabled, non-deleted, current-revision-only schedules
        List<ScheduleDefinition> definitions = jdbc.query("""
                select * from schedule_definitions
                where enabled = 1 and deleted = 0 and current_revision = 1
                """, (rs, row) -> definitionFromRow(rs));

        for (ScheduleDefinition def : definitions) {
            try {
                processDueSchedule(def, claim, now);
            } catch (Exception e) {
                log.warn("Failed to process schedule {}: {}", def.id(), e.getMessage());
            }
        }
    }

    private void processDueSchedule(ScheduleDefinition def, SchedulerClaim claim, Instant now) {
        if (StringUtils.hasText(def.projectId())) {
            try {
                requireActiveProject(def.projectId());
                requireProjectMember(def.projectId(), def.agentId());
            } catch (RuntimeException ineligible) {
                log.info("Skipping ineligible project schedule {}: {}", def.id(), ineligible.getMessage());
                return;
            }
        }
        ScheduleMode mode = def.mode() != null ? def.mode() : ScheduleMode.CRON;

        switch (mode) {
            case CRON -> processCronSchedule(def, claim, now);
            case ONE_TIME -> processOneTimeSchedule(def, claim, now);
        }
    }

    private void processCronSchedule(ScheduleDefinition def, SchedulerClaim claim, Instant now) {
        CronExpression cron = validateCron(def.cronExpression());
        ZoneId zoneId = validateTimezone(def.timezone());

        ZonedDateTime zoneNow = ZonedDateTime.ofInstant(now, zoneId);
        ZonedDateTime previousSlot = previousSlot(cron, zoneNow);

        if (previousSlot == null) return;

        Instant slotInstant = previousSlot.toInstant();
        String slotStr = slotInstant.toString();

        // Check if already fired for this slot+revision
        Integer existing = jdbc.queryForObject("""
                select count(*) from schedule_firings
                where schedule_id = ? and revision = ? and slot_instant = ? and timezone = ?
                """, Integer.class, def.id(), def.revision(), slotStr, def.timezone());
        if (existing != null && existing > 0) return;

        if (hasActiveLinkedWork(def)) return;

        createAndEnqueueFiring(def, claim, now, slotStr, def.timezone());
    }

    private void processOneTimeSchedule(ScheduleDefinition def, SchedulerClaim claim, Instant now) {
        if (def.fireAt() == null) return;

        // Check if already past the fire time (within a reasonable window)
        if (def.fireAt().isAfter(now)) return;

        // Check if already fired for this revision
        String slotStr = def.fireAt().toString();
        Integer existing = jdbc.queryForObject("""
                select count(*) from schedule_firings
                where schedule_id = ? and slot_instant = ?
                  and firing_state in ('ENQUEUED', 'EXHAUSTED', 'RECOVERED', 'CONFLICT')
                """, Integer.class, def.id(), slotStr);
        if (existing != null && existing > 0) return;

        if (hasActiveLinkedWork(def)) return;

        createAndEnqueueFiring(def, claim, now, slotStr, "UTC");
    }

    private void createAndEnqueueFiring(ScheduleDefinition def, SchedulerClaim claim,
                                         Instant now, String slotStr, String timezone) {
        String firingId = UUID.randomUUID().toString();
        String operationKey = def.id() + ":" + def.revision() + ":" + slotStr + ":" + timezone;
        String templateJson = jsonOrThrow(def.assignmentTemplate());
        String requestDigest = sha256(operationKey + templateJson);

        int inserted = jdbc.update("""
                insert or ignore into schedule_firings (
                    firing_id, schedule_id, revision, slot_instant, timezone,
                    offset_policy, firing_state, operation_key, request_digest,
                    template_snapshot_json, created_at, updated_at
                ) values (?, ?, ?, ?, ?, ?, 'CLAIMED', ?, ?, ?, ?, ?)
                """,
                firingId, def.id(), def.revision(), slotStr, timezone,
                "GAP_SKIP", operationKey, requestDigest, templateJson,
                now.toString(), now.toString());

        if (inserted == 0) return;

        enqueueFiring(firingId, operationKey, requestDigest, templateJson, def, claim, now);

        // For ONE_TIME, disable after firing to prevent re-processing
        if (def.mode() == ScheduleMode.ONE_TIME) {
            jdbc.update("""
                    update schedule_definitions set enabled = 0, updated_at = ?
                    where id = ? and revision = ? and current_revision = 1
                    """, now.toString(), def.id(), def.revision());
        }
    }

    private void enqueueFiring(String firingId, String operationKey, String requestDigest,
                                String templateJson, ScheduleDefinition def,
                                SchedulerClaim claim, Instant now) {
        int attemptNum = 1;
        long delayMs = 0;

        while (attemptNum <= MAX_ENQUEUE_ATTEMPTS) {
            delayMs = Math.min(60_000, (long) Math.pow(2, attemptNum) * 1000);
            Instant eligibleAt = now.plusMillis(delayMs);

            String attemptId = UUID.randomUUID().toString();
            jdbc.update("""
                    insert into schedule_firing_attempts (
                        attempt_id, firing_id, attempt_number, eligible_at, claim_owner,
                        claim_fence, outcome, created_at
                    ) values (?, ?, ?, ?, ?, ?, 'ENQUEUED', ?)
                    """, attemptId, firingId, attemptNum, eligibleAt.toString(),
                    claim.ownerId(), claim.fence(), now.toString());

            try {
                SchedulerClaim current = currentClaim();
                if (current == null || current.fence() != claim.fence()
                        || !current.ownerId().equals(claim.ownerId())) {
                    jdbc.update("""
                            update schedule_firing_attempts
                            set outcome = 'FAILED', error_class = 'STALE_CLAIM', attempted_at = ?
                            where attempt_id = ?
                            """, now.toString(), attemptId);
                    break;
                }

                Map<String, Object> template = parseJson(templateJson);
                AssignmentRequest request = AssignmentTemplateParser.automationRequest(
                        def.agentId(), def.jobId(), def.projectId(), operationKey, template,
                        AssignmentType.JOB_RUN);
                String assignmentId = UUID.randomUUID().toString();

                WorkAssignment assignment = assignmentService.create(assignmentId, request);
                String taskRunId = jdbc.queryForObject(
                        "select task_run_id from task_run_native_links where assignment_id = ? order by created_at desc limit 1",
                        String.class, assignmentId);

                jdbc.update("""
                        update schedule_firings
                        set firing_state = 'ENQUEUED', assignment_id = ?,
                            task_run_id = ?, native_run_id = ?, correlation_id = ?,
                            updated_at = ?
                        where firing_id = ? and firing_state = 'CLAIMED'
                        """, assignmentId, taskRunId, taskRunId, assignmentId,
                        now.toString(), firingId);

                jdbc.update("""
                        update schedule_firing_attempts
                        set outcome = 'ENQUEUED', attempted_at = ?,
                            assignment_id = ?, task_run_id = ?, native_run_id = ?,
                            correlation_id = ?
                        where attempt_id = ?
                        """, now.toString(), assignmentId, taskRunId, taskRunId,
                        assignmentId, attemptId);

                eventService.publishProject(EventType.SCHEDULE_DUE, "SCHEDULE", def.id(), def.projectId(), Map.of(
                        "scheduleId", def.id(),
                        "agentId", def.agentId(),
                        "firingId", firingId,
                        "assignmentId", assignmentId
                ));
                return;

            } catch (Exception e) {
                String errorClass = e.getClass().getSimpleName();
                jdbc.update("""
                        update schedule_firing_attempts
                        set outcome = 'FAILED', error_class = ?, attempted_at = ?
                        where attempt_id = ?
                        """, errorClass, now.toString(), attemptId);
                attemptNum++;
            }
        }

        jdbc.update("""
                update schedule_firings
                set firing_state = 'EXHAUSTED', updated_at = ?
                where firing_id = ?
                """, now.toString(), firingId);
    }

    private boolean hasActiveLinkedWork(ScheduleDefinition def) {
        Integer count = jdbc.queryForObject("""
                select count(*) from schedule_firings sf
                join work_assignments wa on wa.id = sf.assignment_id
                where sf.schedule_id = ? and sf.revision = ?
                  and wa.status not in ('CANCELLED', 'FAILED', 'COMPLETED', 'NEEDS_REVIEW')
                """, Integer.class, def.id(), def.revision());
        return count != null && count > 0;
    }

    // ── Recovery ──

    @Transactional
    public int recoverStaleFirings() {
        SchedulerClaim claim = currentClaim();
        if (claim == null) return 0;
        Instant now = clock.instant();

        List<String> staleFirings = jdbc.queryForList("""
                select sf.firing_id from schedule_firings sf
                where sf.firing_state = 'CLAIMED'
                  and sf.updated_at < ?
                """, String.class, now.minus(CLAIM_TTL).toString());

        int recovered = 0;
        for (String firingId : staleFirings) {
            try {
                recoverFiring(firingId, claim, now);
                recovered++;
            } catch (Exception e) {
                log.warn("Failed to recover firing {}: {}", firingId, e.getMessage());
            }
        }
        return recovered;
    }

    private void recoverFiring(String firingId, SchedulerClaim claim, Instant now) {
        Map<String, Object> firing = jdbc.queryForMap(
                "select * from schedule_firings where firing_id = ?", firingId);
        String operationKey = (String) firing.get("operation_key");
        String templateJson = (String) firing.get("template_snapshot_json");
        String scheduleId = (String) firing.get("schedule_id");
        int revision = ((Number) firing.get("revision")).intValue();

        Integer existingCount = jdbc.queryForObject("""
                select count(*) from task_run_idempotent_ops
                where operation_key = ? and operation_state = 'completed'
                """, Integer.class, operationKey);

        if (existingCount != null && existingCount > 0) {
            jdbc.update("""
                    update schedule_firings
                    set firing_state = 'RECOVERED', updated_at = ?
                    where firing_id = ?
                    """, now.toString(), firingId);
            return;
        }

        ScheduleDefinition def = jdbc.query("""
                select * from schedule_definitions where id = ? and revision = ?
                """, rs -> rs.next() ? definitionFromRow(rs) : null, scheduleId, revision);

        if (def == null || def.deleted() || !def.enabled()) {
            jdbc.update("""
                    update schedule_firings
                    set firing_state = 'RECOVERED', updated_at = ?
                    where firing_id = ?
                    """, now.toString(), firingId);
            return;
        }

        String requestDigest = sha256(operationKey + templateJson);
        enqueueFiring(firingId, operationKey, requestDigest, templateJson, def, claim, now);
    }

    // ── Simulation ──

    /**
     * Pure, side-effect-free schedule evaluation suitable for simulation.
     */
    public ScheduleSimulation simulate(ScheduleSimRequest request) {
        ScheduleMode mode = request.mode() != null ? request.mode() : ScheduleMode.CRON;

        switch (mode) {
            case CRON -> {
                return simulateCron(request);
            }
            case ONE_TIME -> {
                return simulateOneTime(request);
            }
            default -> {
                return new ScheduleSimulation(null, null, null,
                        false, false, false, List.of(),
                        "INVALID_MODE", false, 0, "Unknown mode");
            }
        }
    }

    private ScheduleSimulation simulateCron(ScheduleSimRequest request) {
        CronExpression cron;
        try {
            cron = validateCron(request.cronExpression());
        } catch (IllegalArgumentException e) {
            return new ScheduleSimulation(null, null, null, false, false, false,
                    List.of(), "INVALID_CRON", false, 0, e.getMessage());
        }

        ZoneId zoneId;
        try {
            zoneId = validateTimezone(request.timezone());
        } catch (IllegalArgumentException e) {
            return new ScheduleSimulation(null, null, null,
                    false, false, false, List.of(),
                    "INVALID_TIMEZONE", false, 0, e.getMessage());
        }

        Instant now = request.clockInstant() != null ? request.clockInstant() : clock.instant();
        ZonedDateTime zoneNow = ZonedDateTime.ofInstant(now, zoneId);

        List<String> nextSlots = new ArrayList<>();
        ZonedDateTime cursor = zoneNow;
        for (int i = 0; i < (request.slotCount() > 0 ? Math.min(request.slotCount(), 12) : 5); i++) {
            ZonedDateTime next = cron.next(cursor);
            if (next == null) break;
            nextSlots.add(next.toInstant().toString());
            cursor = next;
        }

        boolean dstGap = false;
        boolean dstOverlap = false;
        if (!nextSlots.isEmpty()) {
            Instant nextInst = Instant.parse(nextSlots.get(0));
            ZonedDateTime nextZoned = ZonedDateTime.ofInstant(nextInst, zoneId);
            dstGap = hasDstGap(zoneNow, nextZoned, zoneId);
            dstOverlap = hasDstOverlap(zoneNow, nextZoned, zoneId);
        }

        boolean misfireDetected = false;
        int missedSlots = 0;
        if (!nextSlots.isEmpty()) {
            ZonedDateTime previousSlot = previousSlot(cron, zoneNow);
            if (previousSlot != null) {
                ZonedDateTime nextSlot = ZonedDateTime.ofInstant(Instant.parse(nextSlots.get(0)), zoneId);
                long slotDiff = Duration.between(previousSlot, nextSlot).toMinutes();
                long expectedInterval = estimatedCronIntervalMinutes(cron);
                if (expectedInterval > 0 && slotDiff > expectedInterval * 1.5) {
                    misfireDetected = true;
                    missedSlots = (int) (slotDiff / expectedInterval) - 1;
                }
            }
        }

        return new ScheduleSimulation(
                DIALECT_VERSION, zoneId.getId(), now.toString(),
                request.enabled() != null ? request.enabled() : true,
                dstGap, dstOverlap,
                nextSlots, "OK", misfireDetected, missedSlots, null);
    }

    private ScheduleSimulation simulateOneTime(ScheduleSimRequest request) {
        if (request.fireAt() == null) {
            return new ScheduleSimulation(null, null, null,
                    false, false, false, List.of(),
                    "INVALID_FIRE_AT", false, 0, "fireAt is required for ONE_TIME mode");
        }

        Instant now = request.clockInstant() != null ? request.clockInstant() : clock.instant();

        boolean past = !request.fireAt().isAfter(now);
        boolean enabled = request.enabled() != null ? request.enabled() : true;

        List<String> nextSlots = new ArrayList<>();
        if (!past) {
            nextSlots.add(request.fireAt().toString());
        }

        String state = "OK";
        String error = null;
        if (past) {
            error = "fireAt is in the past";
            state = "PAST";
        }

        return new ScheduleSimulation(
                "ONE_TIME", "UTC", now.toString(),
                enabled, false, false,
                nextSlots, state, false, 0, error);
    }

    private boolean hasDstGap(ZonedDateTime from, ZonedDateTime to, ZoneId zoneId) {
        try {
            long actualDiff = Duration.between(from.toInstant(), to.toInstant()).toMinutes();
            long wallDiff = Duration.between(from.toLocalDateTime(), to.toLocalDateTime()).toMinutes();
            return wallDiff > actualDiff + 30;
        } catch (Exception e) {
            return false;
        }
    }

    private boolean hasDstOverlap(ZonedDateTime from, ZonedDateTime to, ZoneId zoneId) {
        try {
            long actualDiff = Duration.between(from.toInstant(), to.toInstant()).toMinutes();
            long wallDiff = Duration.between(from.toLocalDateTime(), to.toLocalDateTime()).toMinutes();
            return actualDiff > wallDiff + 30;
        } catch (Exception e) {
            return false;
        }
    }

    private long estimatedCronIntervalMinutes(CronExpression cron) {
        try {
            Instant now = clock.instant();
            ZonedDateTime z1 = ZonedDateTime.ofInstant(now, ZoneId.of("UTC"));
            ZonedDateTime z2 = cron.next(z1);
            if (z2 == null) return 0;
            ZonedDateTime z3 = cron.next(z2);
            if (z3 == null) return 0;
            return Duration.between(z2, z3).toMinutes();
        } catch (Exception e) {
            return 0;
        }
    }

    // ── Firing Audit ──

    public List<ScheduleFiring> firings(String scheduleId, int revision, int offset, int limit) {
        int boundedLimit = Math.min(Math.max(limit, 1), 200);
        return jdbc.query("""
                select * from schedule_firings
                where schedule_id = ? and revision = ?
                order by created_at desc
                limit ? offset ?
                """, (rs, row) -> firingFromRow(rs), scheduleId, revision, boundedLimit, offset);
    }

    public List<ScheduleFiringAttempt> firingAttempts(String firingId) {
        return jdbc.query("""
                select * from schedule_firing_attempts
                where firing_id = ?
                order by attempt_number asc
                """, (rs, row) -> attemptFromRow(rs), firingId);
    }

    // ── Project helper ──

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

    public List<AgentSchedule> schedules(String agentId) {
        return definitions(agentId).stream().map(this::toLegacySchedule).toList();
    }

    public AgentSchedule schedule(String agentId, String scheduleId) {
        return toLegacySchedule(definition(agentId, scheduleId));
    }

    @Transactional
    public AgentSchedule save(String agentId, AgentSchedule schedule) {
        AssignmentTemplateParser.scheduleRequest(new AgentSchedule(
                schedule.id(), agentId,
                normalizeStr(schedule.jobId()),
                schedule.assignmentTemplate() == null ? Map.of() : schedule.assignmentTemplate(),
                schedule.mode() != null ? schedule.mode() : ScheduleMode.CRON,
                schedule.cronExpression(),
                schedule.timezone() != null ? schedule.timezone() : "UTC",
                schedule.fireAt(),
                schedule.enabled(),
                schedule.projectId(),
                schedule.nextRunAt(), schedule.createdAt(), schedule.updatedAt()));
        ScheduleDefinition def = save(agentId, new ScheduleDefinition(
                schedule.id(), agentId, 0,
                schedule.mode() != null ? schedule.mode() : ScheduleMode.CRON,
                schedule.cronExpression(),
                schedule.timezone() != null ? schedule.timezone() : "UTC",
                schedule.fireAt(),
                schedule.enabled(), false,
                schedule.projectId(),
                schedule.jobId(),
                schedule.assignmentTemplate() != null ? schedule.assignmentTemplate() : Map.of(),
                null, null, null, null));
        return toLegacySchedule(def);
    }

    @Transactional
    public AgentSchedule toggle(String agentId, String scheduleId) {
        ScheduleDefinition current = definition(agentId, scheduleId);
        ScheduleDefinition updated = enable(agentId, scheduleId, !current.enabled());
        return toLegacySchedule(updated);
    }

    private AgentSchedule toLegacySchedule(ScheduleDefinition def) {
        return new AgentSchedule(def.id(), def.agentId(), def.jobId(),
                def.assignmentTemplate(),
                def.mode() != null ? def.mode() : ScheduleMode.CRON,
                def.cronExpression(), def.timezone(), def.fireAt(),
                def.enabled(), def.projectId(),
                null, def.createdAt(), def.updatedAt());
    }

    // ── Cancellation ──

    @Transactional
    public void cancelLinkedWork(String agentId, String scheduleId, int revision) {
        List<String> assignmentIds = jdbc.queryForList("""
                select distinct sf.assignment_id from schedule_firings sf
                where sf.schedule_id = ? and sf.revision = ?
                  and sf.firing_state = 'ENQUEUED'
                  and sf.assignment_id is not null
                """, String.class, scheduleId, revision);

        for (String assignmentId : assignmentIds) {
            try {
                assignmentService.cancel(agentId, assignmentId);
            } catch (Exception e) {
                log.warn("Failed to cancel linked assignment {}: {}", assignmentId, e.getMessage());
            }
        }
    }

    // ── Helpers ──

    private static String normalizeStr(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }

    private static String coalesce(String preferred, String fallback) {
        return StringUtils.hasText(preferred) ? preferred : fallback;
    }

    static CronExpression validateCron(String expression) {
        if (!StringUtils.hasText(expression)) {
            throw new IllegalArgumentException("cronExpression is required");
        }
        if (!CronExpression.isValidExpression(expression.trim())) {
            throw new IllegalArgumentException("invalid cronExpression: " + expression);
        }
        return CronExpression.parse(expression.trim());
    }

    static ZoneId validateTimezone(String timezone) {
        if (!StringUtils.hasText(timezone)) {
            throw new IllegalArgumentException("timezone is required");
        }
        return ZoneId.of(timezone.trim());
    }

    static ZonedDateTime previousSlot(CronExpression cron, ZonedDateTime after) {
        ZonedDateTime current = after.minusDays(2);
        ZonedDateTime last = null;
        while (current.isBefore(after) || current.equals(after)) {
            ZonedDateTime next = cron.next(current);
            if (next == null) break;
            if (next.isAfter(after)) break;
            if (next.equals(after)) {
                last = next;
                break;
            }
            last = next;
            current = next;
        }
        return last;
    }

    static String sha256(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(input.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 unavailable", e);
        }
    }

    private String jsonOrThrow(Map<String, Object> map) {
        try {
            return objectMapper.writeValueAsString(map == null ? Map.of() : map);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Failed to serialize template", e);
        }
    }

    private Map<String, Object> parseJson(String json) {
        try {
            return objectMapper.readValue(json, Map.class);
        } catch (Exception e) {
            return Map.of();
        }
    }

    // ── Row mappers ──

    private ScheduleDefinition definitionFromRow(java.sql.ResultSet rs) throws java.sql.SQLException {
        ScheduleMode mode;
        try {
            mode = ScheduleMode.valueOf(rs.getString("mode"));
        } catch (Exception e) {
            mode = ScheduleMode.CRON;
        }
        return new ScheduleDefinition(
                rs.getString("id"), rs.getString("agent_id"), rs.getInt("revision"),
                mode,
                rs.getString("cron_expression"), rs.getString("timezone"),
                nullableInstant(rs.getString("fire_at")),
                rs.getInt("enabled") == 1, rs.getInt("deleted") == 1,
                rs.getString("project_id"),
                rs.getString("job_id"), parseJson(rs.getString("assignment_template_json")),
                rs.getString("dialect_version"), rs.getString("policy_version"),
                Instant.parse(rs.getString("created_at")), Instant.parse(rs.getString("updated_at")));
    }

    private SchedulerClaim claimFromRow(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new SchedulerClaim(rs.getString("id"), rs.getString("owner_id"),
                rs.getLong("fence"), Instant.parse(rs.getString("heartbeat_at")),
                Instant.parse(rs.getString("expires_at")), Instant.parse(rs.getString("claimed_at")),
                Instant.parse(rs.getString("created_at")), Instant.parse(rs.getString("updated_at")));
    }

    private ScheduleFiring firingFromRow(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new ScheduleFiring(
                rs.getString("firing_id"), rs.getString("schedule_id"), rs.getInt("revision"),
                Instant.parse(rs.getString("slot_instant")), rs.getString("timezone"),
                rs.getString("offset_policy"), rs.getString("firing_state"),
                rs.getString("operation_key"), rs.getString("request_digest"),
                parseJson(rs.getString("template_snapshot_json")),
                rs.getString("assignment_id"), rs.getString("task_run_id"),
                rs.getString("native_run_id"), rs.getString("correlation_id"),
                rs.getString("error_class"),
                Instant.parse(rs.getString("created_at")), Instant.parse(rs.getString("updated_at")));
    }

    private ScheduleFiringAttempt attemptFromRow(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new ScheduleFiringAttempt(
                rs.getString("attempt_id"), rs.getString("firing_id"), rs.getInt("attempt_number"),
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

    public record ScheduleDefinition(
            String id, String agentId, int revision,
            ScheduleMode mode,
            String cronExpression, String timezone,
            Instant fireAt,
            boolean enabled, boolean deleted,
            String projectId,
            String jobId, Map<String, Object> assignmentTemplate,
            String dialectVersion, String policyVersion,
            Instant createdAt, Instant updatedAt) {}

    public record SchedulerClaim(
            String id, String ownerId, long fence,
            Instant heartbeatAt, Instant expiresAt,
            Instant claimedAt, Instant createdAt, Instant updatedAt) {}

    public record ScheduleFiring(
            String firingId, String scheduleId, int revision,
            Instant slotInstant, String timezone, String offsetPolicy,
            String firingState, String operationKey, String requestDigest,
            Map<String, Object> templateSnapshot,
            String assignmentId, String taskRunId, String nativeRunId,
            String correlationId, String errorClass,
            Instant createdAt, Instant updatedAt) {}

    public record ScheduleFiringAttempt(
            String attemptId, String firingId, int attemptNumber,
            Instant eligibleAt, Instant attemptedAt,
            String claimOwner, long claimFence,
            String outcome, String errorClass,
            String assignmentId, String taskRunId, String nativeRunId,
            String correlationId, Instant createdAt) {}

    public record ScheduleSimRequest(
            String cronExpression, String timezone,
            ScheduleMode mode,
            Instant fireAt,
            Boolean enabled, Integer slotCount,
            Instant clockInstant) {}

    public record ScheduleSimulation(
            String dialectVersion, String timezone, String clockInstant,
            boolean enabled, boolean dstGap, boolean dstOverlap,
            List<String> nextSlots, String state,
            boolean misfireDetected, int missedSlots,
            String error) {}
}
