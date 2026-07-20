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
 * and a side-effect-free simulation evaluator.
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
    private final Clock clock;
    private final boolean schedulesEnabled;

    public ScheduleService(JdbcTemplate jdbc, ObjectMapper objectMapper,
                           AssignmentService assignmentService,
                           OrchestrationEventService eventService,
                           Clock clock,
                           @org.springframework.beans.factory.annotation.Value("${magenta.features.schedules-enabled:false}") boolean schedulesEnabled) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper.findAndRegisterModules();
        this.assignmentService = assignmentService;
        this.eventService = eventService;
        this.clock = clock;
        this.schedulesEnabled = schedulesEnabled;
    }

    // ── Definition CRUD ──

    public List<ScheduleDefinition> definitions(String agentId) {
        return jdbc.query("""
                select * from schedule_definitions
                where agent_id = ? and deleted = 0
                order by created_at desc
                """, (rs, row) -> definitionFromRow(rs), agentId);
    }

    public ScheduleDefinition definition(String agentId, String scheduleId) {
        ScheduleDefinition def = jdbc.query("""
                select * from schedule_definitions
                where id = ? and deleted = 0
                order by revision desc limit 1
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
        String id = StringUtils.hasText(input.id()) ? input.id() : UUID.randomUUID().toString();
        int nextRevision;
        Integer currentRev = jdbc.queryForObject(
                "select max(revision) from schedule_definitions where id = ?", Integer.class, id);
        nextRevision = currentRev == null || currentRev == 0 ? 1 : currentRev + 1;

        CronExpression cron = validateCron(input.cronExpression());
        ZoneId zoneId = validateTimezone(input.timezone());
        String assignmentJson = jsonOrThrow(input.assignmentTemplate());
        Instant now = clock.instant();

        jdbc.update("""
                insert into schedule_definitions (
                    id, agent_id, revision, cron_expression, timezone, dialect_version,
                    policy_version, enabled, deleted, job_id, assignment_template_json,
                    created_at, updated_at
                ) values (?, ?, ?, ?, ?, ?, ?, ?, 0, ?, ?, ?, ?)
                """,
                id, agentId, nextRevision, input.cronExpression().trim(), zoneId.getId(),
                DIALECT_VERSION, POLICY_VERSION,
                input.enabled() ? 1 : 0, input.jobId(), assignmentJson,
                now.toString(), now.toString());

        return definitionByRevision(agentId, id, nextRevision);
    }

    @Transactional
    public ScheduleDefinition update(String agentId, String scheduleId, ScheduleDefinition input) {
        ScheduleDefinition current = definition(agentId, scheduleId);
        CronExpression cron = validateCron(input.cronExpression());
        ZoneId zoneId = validateTimezone(input.timezone());
        String assignmentJson = jsonOrThrow(input.assignmentTemplate());
        int nextRevision = current.revision() + 1;
        Instant now = clock.instant();

        jdbc.update("""
                insert into schedule_definitions (
                    id, agent_id, revision, cron_expression, timezone, dialect_version,
                    policy_version, enabled, deleted, job_id, assignment_template_json,
                    created_at, updated_at
                ) values (?, ?, ?, ?, ?, ?, ?, ?, 0, ?, ?, ?, ?)
                """,
                scheduleId, agentId, nextRevision, input.cronExpression().trim(), zoneId.getId(),
                DIALECT_VERSION, POLICY_VERSION,
                input.enabled() ? 1 : 0, input.jobId(), assignmentJson,
                current.createdAt().toString(), now.toString());

        return definitionByRevision(agentId, scheduleId, nextRevision);
    }

    @Transactional
    public ScheduleDefinition enable(String agentId, String scheduleId, boolean enabled) {
        ScheduleDefinition current = definition(agentId, scheduleId);
        if (current.enabled() == enabled) return current;
        return update(agentId, scheduleId, new ScheduleDefinition(
                scheduleId, agentId, current.revision(), current.cronExpression(),
                current.timezone(), enabled, false,
                current.jobId(), current.assignmentTemplate(),
                current.dialectVersion(), current.policyVersion(),
                current.createdAt(), current.updatedAt()));
    }

    @Transactional
    public void softDelete(String agentId, String scheduleId) {
        ScheduleDefinition current = definition(agentId, scheduleId);
        Instant now = clock.instant();
        jdbc.update("""
                update schedule_definitions set deleted = 1, updated_at = ?
                where id = ? and agent_id = ?
                """, now.toString(), scheduleId, agentId);
    }

    @Transactional
    public void delete(String agentId, String scheduleId) {
        softDelete(agentId, scheduleId);
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
            // Claim is still valid for another owner
            return existing;
        }

        // Takeover or renew with incremented fence
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
            return; // Another owner holds the claim
        }

        Instant now = clock.instant();

        // Find enabled, non-deleted schedules with due slots
        List<ScheduleDefinition> definitions = jdbc.query("""
                select * from schedule_definitions
                where enabled = 1 and deleted = 0
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
        CronExpression cron = validateCron(def.cronExpression());
        ZoneId zoneId = validateTimezone(def.timezone());

        // Determine the current slot
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

        // Check overlap policy — if linked work is active, skip
        if (hasActiveLinkedWork(def)) return;

        // Create firing
        String firingId = UUID.randomUUID().toString();
        String operationKey = def.id() + ":" + def.revision() + ":" + slotStr + ":" + def.timezone();
        String templateJson = jsonOrThrow(def.assignmentTemplate());
        String requestDigest = sha256(operationKey + templateJson);

        int inserted = jdbc.update("""
                insert or ignore into schedule_firings (
                    firing_id, schedule_id, revision, slot_instant, timezone,
                    offset_policy, firing_state, operation_key, request_digest,
                    template_snapshot_json, created_at, updated_at
                ) values (?, ?, ?, ?, ?, ?, 'CLAIMED', ?, ?, ?, ?, ?)
                """,
                firingId, def.id(), def.revision(), slotStr, def.timezone(),
                "GAP_SKIP", operationKey, requestDigest, templateJson,
                now.toString(), now.toString());

        if (inserted == 0) return; // Race — another poller got it

        // Enqueue via attempt
        enqueueFiring(firingId, operationKey, requestDigest, templateJson, def, claim, now);
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
                // Verify claim is still valid
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

                // Build assignment from template
                Map<String, Object> template = parseJson(templateJson);
                AssignmentRequest request = AssignmentTemplateParser.scheduleRequest(
                        new AgentSchedule(def.id(), def.agentId(), def.jobId(),
                                template, def.cronExpression(), def.timezone(),
                                def.enabled(), null, null, null));
                String assignmentId = UUID.randomUUID().toString();

                // Create the assignment
                WorkAssignment assignment = assignmentService.create(assignmentId, request);
                String taskRunId = jdbc.queryForObject(
                        "select task_run_id from task_run_native_links where assignment_id = ? order by created_at desc limit 1",
                        String.class, assignmentId);

                // Update firing with success
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

                eventService.publish(EventType.SCHEDULE_DUE, "SCHEDULE", def.id(), Map.of(
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

        // Exhausted
        jdbc.update("""
                update schedule_firings
                set firing_state = 'EXHAUSTED', updated_at = ?
                where firing_id = ?
                """, now.toString(), firingId);
    }

    private boolean hasActiveLinkedWork(ScheduleDefinition def) {
        // Check if any firing for this schedule is linked to an active assignment
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

        // Recover CLAIMED firings with expired claims
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

        // Check if existing assignment linked to this idempotent op already succeeded
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

        // Get current schedule definition
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
     * Returns deterministic decisions without creating any persistence records.
     */
    public ScheduleSimulation simulate(ScheduleSimRequest request) {
        CronExpression cron;
        try {
            cron = validateCron(request.cronExpression());
        } catch (IllegalArgumentException e) {
            return new ScheduleSimulation(null, null, null, false, false, false,
                    List.of(), "INVALID_CRON", false, 0, e.getMessage());
        }

        ZoneId zoneId = null;
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

        // Check for DST gap/overlap around next slot
        boolean dstGap = false;
        boolean dstOverlap = false;
        if (!nextSlots.isEmpty()) {
            Instant nextInst = Instant.parse(nextSlots.get(0));
            ZonedDateTime nextZoned = ZonedDateTime.ofInstant(nextInst, zoneId);
            dstGap = hasDstGap(zoneNow, nextZoned, zoneId);
            dstOverlap = hasDstOverlap(zoneNow, nextZoned, zoneId);
        }

        // Check for misfire
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

        String error = null;
        String state = "OK";

        return new ScheduleSimulation(
                DIALECT_VERSION, zoneId.getId(), now.toString(),
                request.enabled() != null ? request.enabled() : true,
                dstGap, dstOverlap,
                nextSlots, state, misfireDetected, missedSlots, error);
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
        // Estimate from the expression — crude approach
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

    // ── Backward-compatible wrappers (delegate to new API) ──

    public List<AgentSchedule> schedules(String agentId) {
        return definitions(agentId).stream().map(this::toLegacySchedule).toList();
    }

    public AgentSchedule schedule(String agentId, String scheduleId) {
        return toLegacySchedule(definition(agentId, scheduleId));
    }

    @Transactional
    public AgentSchedule save(String agentId, AgentSchedule schedule) {
        // Validate template through existing parser
        AssignmentTemplateParser.scheduleRequest(new AgentSchedule(
                schedule.id(), agentId,
                normalizeStr(schedule.jobId()),
                schedule.assignmentTemplate() == null ? Map.of() : schedule.assignmentTemplate(),
                schedule.cronExpression(),
                schedule.timezone() != null ? schedule.timezone() : "UTC",
                schedule.enabled(), schedule.nextRunAt(),
                schedule.createdAt(), schedule.updatedAt()));
        ScheduleDefinition def = save(agentId, new ScheduleDefinition(
                schedule.id(), agentId, 0, schedule.cronExpression(),
                schedule.timezone() != null ? schedule.timezone() : "UTC",
                schedule.enabled(), false, schedule.jobId(),
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
                def.assignmentTemplate(), def.cronExpression(), def.timezone(),
                def.enabled(), null, def.createdAt(), def.updatedAt());
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
        return org.springframework.util.StringUtils.hasText(value) ? value.trim() : null;
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
        // Walk back from `after` to find the most recent slot
        ZonedDateTime current = after.minusDays(2); // reasonable search window
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
        return new ScheduleDefinition(
                rs.getString("id"), rs.getString("agent_id"), rs.getInt("revision"),
                rs.getString("cron_expression"), rs.getString("timezone"),
                rs.getInt("enabled") == 1, rs.getInt("deleted") == 1,
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
            String cronExpression, String timezone,
            boolean enabled, boolean deleted,
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
            Boolean enabled, Integer slotCount,
            Instant clockInstant) {}

    public record ScheduleSimulation(
            String dialectVersion, String timezone, String clockInstant,
            boolean enabled, boolean dstGap, boolean dstOverlap,
            List<String> nextSlots, String state,
            boolean misfireDetected, int missedSlots,
            String error) {}
}
