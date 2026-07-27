package io.mindspice.magenta2.core.persistence;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.sql.DataSource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.sql.init.dependency.DependsOnDatabaseInitialization;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.EncodedResource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.core.io.support.ResourcePatternResolver;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.stereotype.Component;

/** Sole checksum-protected owner of ordered SQL migrations. */
@Component("schemaMigrationRunner")
@DependsOnDatabaseInitialization
public final class SchemaMigrationRunner {
    private static final Logger log = LoggerFactory.getLogger(SchemaMigrationRunner.class);
    private static final String MIGRATION_PATH = "classpath*:db/migrations/*.sql";
    private static final String FRESH_SCHEMA_PATH = "classpath:schema.sql";
    private static final Pattern MIGRATION_NAME = Pattern.compile("^(\\d{3})-([a-z0-9]+(?:-[a-z0-9]+)*)\\.sql$");
    private static final List<String> FOUNDATION_TABLES_V1 = List.of(
            "task_run_locations", "task_run_attempts", "task_run_idempotent_ops",
            "task_run_native_links", "task_run_writer_claims");
    private static final List<String> FOUNDATION_TABLES_V2 = List.of(
            "task_run_locations", "task_run_attempts", "task_run_idempotent_ops",
            "task_run_native_links", "assignment_fence_generations", "writer_claim_generations",
            "task_run_links");
    private static final List<String> FOUNDATION_TABLES_V3 = List.of(
            "task_run_locations", "task_run_attempts", "task_run_idempotent_ops",
            "task_run_native_links", "assignment_fence_generations", "writer_claim_generations",
            "task_run_links", "output_publications");
    private static final List<String> FOUNDATION_TABLES_V4 = List.of(
            "task_run_locations", "task_run_attempts", "task_run_idempotent_ops",
            "task_run_native_links", "assignment_fence_generations", "writer_claim_generations",
            "task_run_links", "output_publications", "skill_candidate_versions",
            "skill_candidate_observations", "skill_exact_assignments",
            "skill_legacy_conversion_diagnostics");
    private static final List<String> FOUNDATION_TABLES_V5 = List.of(
            "task_run_locations", "task_run_attempts", "task_run_idempotent_ops",
            "task_run_native_links", "assignment_fence_generations", "writer_claim_generations",
            "task_run_links", "output_publications", "skill_candidate_versions",
            "skill_candidate_observations", "skill_exact_assignments",
            "skill_legacy_conversion_diagnostics",
            "artifact_extraction_documents", "artifact_lineage_edges",
            "task_run_retention_policies", "artifact_retention_pins",
            "active_artifact_sessions");
    private static final List<String> FOUNDATION_TABLES_V6 = List.of(
            "task_run_locations", "task_run_attempts", "task_run_idempotent_ops",
            "task_run_native_links", "assignment_fence_generations", "writer_claim_generations",
            "task_run_links", "output_publications", "skill_candidate_versions",
            "skill_candidate_observations", "skill_exact_assignments",
            "skill_legacy_conversion_diagnostics",
            "artifact_extraction_documents", "artifact_lineage_edges",
            "task_run_retention_policies", "artifact_retention_pins",
            "active_artifact_sessions", "skill_snapshots",
            "skill_trust_decisions", "skill_snapshot_resources",
            "skill_activations", "skill_executable_authorizations",
            "skill_package_operations", "skill_resource_executions");
    private static final List<String> FOUNDATION_TABLES_V7 = List.of(
            "task_run_locations", "task_run_attempts", "task_run_idempotent_ops",
            "task_run_native_links", "assignment_fence_generations", "writer_claim_generations",
            "task_run_links", "output_publications", "skill_candidate_versions",
            "skill_candidate_observations", "skill_exact_assignments",
            "skill_legacy_conversion_diagnostics",
            "artifact_extraction_documents", "artifact_lineage_edges",
            "task_run_retention_policies", "artifact_retention_pins",
            "active_artifact_sessions", "skill_snapshots",
            "skill_trust_decisions", "skill_snapshot_resources",
            "skill_activations", "skill_executable_authorizations",
            "skill_package_operations", "skill_resource_executions",
            "run_summaries", "run_measurements");
    private static final List<String> FOUNDATION_TABLES_V8 = List.of(
            "task_run_locations", "task_run_attempts", "task_run_idempotent_ops",
            "task_run_native_links", "assignment_fence_generations", "writer_claim_generations",
            "task_run_links", "output_publications", "skill_candidate_versions",
            "skill_candidate_observations", "skill_exact_assignments",
            "skill_legacy_conversion_diagnostics",
            "artifact_extraction_documents", "artifact_lineage_edges",
            "task_run_retention_policies", "artifact_retention_pins",
            "active_artifact_sessions", "skill_snapshots",
            "skill_trust_decisions", "skill_snapshot_resources",
            "skill_activations", "skill_executable_authorizations",
            "skill_package_operations", "skill_resource_executions",
            "run_summaries", "run_measurements",
            "schedule_definitions", "scheduler_claims", "schedule_firings",
            "schedule_firing_attempts", "reaction_definitions",
            "reaction_dispatches", "reaction_dispatch_attempts");
    private static final Set<String> FOUNDATION_INDEXES_V1 = Set.of(
            "idx_task_run_locations_workspace", "idx_task_run_locations_source",
            "idx_task_run_attempts_status", "idx_task_run_native_links_assignment",
            "idx_task_run_native_links_workflow_child", "idx_task_run_native_links_job_work_item");
    private static final Set<String> FOUNDATION_INDEXES_V2 = Set.of(
            "idx_writer_claim_generations_current", "idx_task_run_links_child");
    private static final Set<String> FOUNDATION_INDEXES_V3 = Set.of(
            "idx_output_publications_workspace", "idx_output_publications_task_run",
            "idx_output_publications_state", "idx_output_publications_reconciliation");
    private static final Set<String> FOUNDATION_INDEXES_V4 = Set.of(
            "idx_skill_candidate_versions_lookup", "idx_skill_candidate_observations_candidate",
            "idx_skill_candidate_observations_scan", "idx_skill_exact_assignments_target",
            "idx_skill_exact_assignments_operation_key");
    private static final Set<String> FOUNDATION_INDEXES_V5 = Set.of(
            "idx_skill_candidate_versions_lookup", "idx_skill_candidate_observations_candidate",
            "idx_skill_candidate_observations_scan", "idx_skill_exact_assignments_target",
            "idx_skill_exact_assignments_operation_key",
            "idx_lineage_edges_parent", "idx_lineage_edges_child",
            "idx_lineage_edges_task_run", "idx_retention_policies_workspace",
            "idx_retention_pins_artifact", "idx_retention_pins_task_run",
            "idx_retention_pins_kind", "idx_active_sessions_artifact",
            "idx_active_sessions_token");
    private static final Set<String> FOUNDATION_INDEXES_V6 = Set.of(
            "idx_skill_candidate_versions_lookup", "idx_skill_candidate_observations_candidate",
            "idx_skill_candidate_observations_scan", "idx_skill_exact_assignments_target",
            "idx_skill_exact_assignments_operation_key",
            "idx_lineage_edges_parent", "idx_lineage_edges_child",
            "idx_lineage_edges_task_run", "idx_retention_policies_workspace",
            "idx_retention_pins_artifact", "idx_retention_pins_task_run",
            "idx_retention_pins_kind", "idx_active_sessions_artifact",
            "idx_active_sessions_token", "idx_skill_snapshots_logical_key",
            "idx_skill_trust_decisions_candidate", "idx_skill_trust_decisions_logical_key",
            "idx_skill_snapshot_resources_snapshot", "idx_skill_activations_task_run",
            "idx_skill_activations_agent", "idx_skill_executable_authorizations_resource",
            "idx_skill_package_operations_state", "idx_skill_resource_executions_task_run",
            "idx_skill_resource_executions_resource");
    private static final Set<String> FOUNDATION_INDEXES_V7 = Set.of(
            "idx_skill_candidate_versions_lookup", "idx_skill_candidate_observations_candidate",
            "idx_skill_candidate_observations_scan", "idx_skill_exact_assignments_target",
            "idx_skill_exact_assignments_operation_key",
            "idx_lineage_edges_parent", "idx_lineage_edges_child",
            "idx_lineage_edges_task_run", "idx_retention_policies_workspace",
            "idx_retention_pins_artifact", "idx_retention_pins_task_run",
            "idx_retention_pins_kind", "idx_active_sessions_artifact",
            "idx_active_sessions_token", "idx_skill_snapshots_logical_key",
            "idx_skill_trust_decisions_candidate", "idx_skill_trust_decisions_logical_key",
            "idx_skill_snapshot_resources_snapshot", "idx_skill_activations_task_run",
            "idx_skill_activations_agent", "idx_skill_executable_authorizations_resource",
            "idx_skill_package_operations_state", "idx_skill_resource_executions_task_run",
            "idx_skill_resource_executions_resource",
            "idx_run_summaries_workspace", "idx_run_summaries_task_run",
            "idx_run_measurements_task_run", "idx_run_measurements_call_id",
            "idx_run_measurements_correlation");
    private static final Set<String> FOUNDATION_INDEXES_V8 = Set.of(
            "idx_skill_candidate_versions_lookup", "idx_skill_candidate_observations_candidate",
            "idx_skill_candidate_observations_scan", "idx_skill_exact_assignments_target",
            "idx_skill_exact_assignments_operation_key",
            "idx_lineage_edges_parent", "idx_lineage_edges_child",
            "idx_lineage_edges_task_run", "idx_retention_policies_workspace",
            "idx_retention_pins_artifact", "idx_retention_pins_task_run",
            "idx_retention_pins_kind", "idx_active_sessions_artifact",
            "idx_active_sessions_token", "idx_skill_snapshots_logical_key",
            "idx_skill_trust_decisions_candidate", "idx_skill_trust_decisions_logical_key",
            "idx_skill_snapshot_resources_snapshot", "idx_skill_activations_task_run",
            "idx_skill_activations_agent", "idx_skill_executable_authorizations_resource",
            "idx_skill_package_operations_state", "idx_skill_resource_executions_task_run",
            "idx_skill_resource_executions_resource",
            "idx_run_summaries_workspace", "idx_run_summaries_task_run",
            "idx_run_measurements_task_run", "idx_run_measurements_call_id",
            "idx_run_measurements_correlation",
            "idx_schedule_defs_agent", "idx_schedule_defs_enabled",
            "idx_schedule_firings_schedule", "idx_schedule_firings_op_key",
            "idx_schedule_firings_assignment",
            "idx_schedule_firing_attempts_firing", "idx_schedule_firing_attempts_assignment",
            "idx_reaction_defs_agent", "idx_reaction_defs_enabled_type",
            "idx_reaction_dispatches_reaction", "idx_reaction_dispatches_root_event",
            "idx_reaction_dispatches_op_key", "idx_reaction_dispatches_dedupe",
            "idx_reaction_dispatches_assignment",
            "idx_reaction_dispatch_attempts_dispatch", "idx_reaction_dispatch_attempts_assignment");
    // Migration 009 is a no-op compatibility marker; owned tables/indexes unchanged from v8.
    private static final List<String> FOUNDATION_TABLES_V9 = FOUNDATION_TABLES_V8;
    private static final Set<String> FOUNDATION_INDEXES_V9 = FOUNDATION_INDEXES_V8;
    private static final List<String> FOUNDATION_TABLES_V10 = List.of(
            "task_run_locations", "task_run_attempts", "task_run_idempotent_ops",
            "task_run_native_links", "assignment_fence_generations", "writer_claim_generations",
            "task_run_links", "output_publications", "skill_candidate_versions",
            "skill_candidate_observations", "skill_exact_assignments",
            "skill_legacy_conversion_diagnostics",
            "artifact_extraction_documents", "artifact_lineage_edges",
            "task_run_retention_policies", "artifact_retention_pins",
            "active_artifact_sessions", "skill_snapshots",
            "skill_trust_decisions", "skill_snapshot_resources",
            "skill_activations", "skill_executable_authorizations",
            "skill_package_operations", "skill_resource_executions",
            "run_summaries", "run_measurements",
            "schedule_definitions", "scheduler_claims", "schedule_firings",
            "schedule_firing_attempts", "reaction_definitions",
            "reaction_dispatches", "reaction_dispatch_attempts",
            "agent_materializations", "context_defaults", "context_bindings",
            "binding_owner_links", "materialization_operation_results");
    private static final List<String> FOUNDATION_TABLES_V11 = List.copyOf(
            java.util.stream.Stream.concat(FOUNDATION_TABLES_V10.stream(),
                    java.util.stream.Stream.of("operation_idempotency")).toList());
    private static final Set<String> FOUNDATION_INDEXES_V10 = Set.of(
            "idx_skill_candidate_versions_lookup", "idx_skill_candidate_observations_candidate",
            "idx_skill_candidate_observations_scan", "idx_skill_exact_assignments_target",
            "idx_skill_exact_assignments_operation_key",
            "idx_lineage_edges_parent", "idx_lineage_edges_child",
            "idx_lineage_edges_task_run", "idx_retention_policies_workspace",
            "idx_retention_pins_artifact", "idx_retention_pins_task_run",
            "idx_retention_pins_kind", "idx_active_sessions_artifact",
            "idx_active_sessions_token", "idx_skill_snapshots_logical_key",
            "idx_skill_trust_decisions_candidate", "idx_skill_trust_decisions_logical_key",
            "idx_skill_snapshot_resources_snapshot", "idx_skill_activations_task_run",
            "idx_skill_activations_agent", "idx_skill_executable_authorizations_resource",
            "idx_skill_package_operations_state", "idx_skill_resource_executions_task_run",
            "idx_skill_resource_executions_resource",
            "idx_run_summaries_workspace", "idx_run_summaries_task_run",
            "idx_run_measurements_task_run", "idx_run_measurements_call_id",
            "idx_run_measurements_correlation",
            "idx_schedule_defs_agent", "idx_schedule_defs_enabled",
            "idx_schedule_firings_schedule", "idx_schedule_firings_op_key",
            "idx_schedule_firings_assignment",
            "idx_schedule_firing_attempts_firing", "idx_schedule_firing_attempts_assignment",
            "idx_reaction_defs_agent", "idx_reaction_defs_enabled_type",
            "idx_reaction_dispatches_reaction", "idx_reaction_dispatches_root_event",
            "idx_reaction_dispatches_op_key", "idx_reaction_dispatches_dedupe",
            "idx_reaction_dispatches_assignment",
            "idx_reaction_dispatch_attempts_dispatch", "idx_reaction_dispatch_attempts_assignment",
            "idx_agent_materializations_agent", "idx_agent_materializations_key",
            "idx_context_bindings_agent", "idx_binding_owner_links_binding");
    private static final Set<String> FOUNDATION_INDEXES_V11 = FOUNDATION_INDEXES_V10;

    private static final Map<String, List<String>> FOUNDATION_COLUMNS = Map.ofEntries(
            Map.entry("task_run_locations", List.of("task_run_id", "source_kind", "source_id", "workspace_type",
                    "workspace_key", "workspace_identity", "task_path_key", "root_relative_path",
                    "lineage_parent_task_run_id", "lineage_mode", "created_at", "updated_at")),
            Map.entry("task_run_attempts", List.of("task_run_id", "attempt_number", "status", "started_at",
                    "completed_at", "error_text", "created_at", "updated_at")),
            Map.entry("task_run_idempotent_ops", List.of("id", "operation_type", "operation_key", "request_digest",
                    "operation_state", "task_run_id", "result_json", "created_at", "updated_at")),
            Map.entry("task_run_native_links", List.of("task_run_id", "native_kind", "native_run_id",
                    "assignment_id", "workflow_child_id", "job_work_item_id", "created_at")),
            Map.entry("task_run_writer_claims", List.of("task_run_id", "attempt_number", "claim_generation",
                    "owner_id", "fence_token", "created_at")),
            Map.entry("assignment_fence_generations", List.of("assignment_id", "generation", "owner_id", "claimed_at")),
            Map.entry("writer_claim_generations", List.of("task_run_id", "attempt_number", "generation", "owner_id",
                    "fence_token", "is_current", "superseded_at", "created_at")),
            Map.entry("task_run_links", List.of("parent_task_run_id", "child_task_run_id", "link_type",
                    "link_key", "created_at")),
            Map.entry("output_publications", List.of("artifact_id", "publication_origin", "workspace_kind",
                    "workspace_key", "workspace_identity", "task_run_id", "task_path_key",
                    "native_run_id", "attempt_number", "root_relative_path", "staging_path",
                    "operation_key", "request_digest", "content_sha256",
                    "publication_state", "reconciliation_state", "failure_detail",
                    "expected_sha256", "verified_sha256", "verified_size", "media_type",
                    "writer_owner_id", "fence_generation", "logical_name", "file_extension",
                    "created_at", "updated_at", "staged_at", "published_at")),
            Map.entry("skill_candidate_versions", List.of("id", "logical_key", "scope_type",
                    "authoritative_root_id", "provenance", "format_version", "content_digest", "created_at")),
            Map.entry("skill_candidate_observations", List.of("id", "candidate_version_id", "is_available",
                    "diagnostics_json", "observed_at", "scan_id")),
            Map.entry("skill_exact_assignments", List.of("id", "candidate_version_id", "logical_key",
                    "target_type", "target_key", "enabled", "operation_key", "actor", "created_at", "updated_at")),
            Map.entry("skill_legacy_conversion_diagnostics", List.of("id", "agent_id", "logical_key", "result",
                    "candidate_version_id", "diagnostics_json", "converted_at")),
            Map.entry("artifact_extraction_documents", List.of("id", "artifact_id", "content_sha256",
                    "extracted_text", "byte_count", "line_count", "language_hint",
                    "extraction_state", "extracted_at")),
            Map.entry("artifact_lineage_edges", List.of("id", "parent_artifact_id", "child_artifact_id",
                    "lineage_kind", "producer_workspace_kind", "producer_workspace_key",
                    "producer_task_run_id", "declaration_digest", "created_at")),
            Map.entry("task_run_retention_policies", List.of("id", "workspace_kind", "workspace_key",
                    "task_path_key", "policy_type", "retain_days", "max_artifact_count",
                    "max_total_bytes", "enabled", "created_at", "updated_at")),
            Map.entry("artifact_retention_pins", List.of("id", "artifact_id", "task_run_id",
                    "pin_kind", "reason", "pinned_by", "pinned_at", "expires_at")),
            Map.entry("active_artifact_sessions", List.of("id", "artifact_id", "session_token",
                    "owner_identity", "bytes_read", "max_bytes", "state",
                    "created_at", "last_activity", "completed_at")),
            Map.entry("run_summaries", List.of("task_run_id", "normalized_status", "attempt_count",
                    "model_call_count", "tool_call_count", "wall_elapsed_ms", "active_elapsed_ms",
                    "completeness", "workspace_type", "workspace_key", "source_kind",
                    "lineage_parent_task_run_id", "lineage_mode", "created_at", "updated_at")),
            Map.entry("run_measurements", List.of("invocation_id", "task_run_id", "attempt_number",
                    "kind", "call_id", "correlation_id", "provider", "model_name", "tool_name",
                    "outcome", "started_at", "completed_at", "elapsed_ms",
                    "input_token_count", "output_token_count", "total_token_count",
                    "timeout_ms", "exit_code", "truncation_kind", "interruption_kind",
                    "rejection_reason", "failure_detail", "completeness", "created_at", "updated_at")),
            Map.entry("schedule_definitions", List.of("id", "agent_id", "revision",
                    "cron_expression", "timezone", "dialect_version", "policy_version",
                    "enabled", "deleted", "job_id", "assignment_template_json",
                    "created_at", "updated_at")),
            Map.entry("scheduler_claims", List.of("id", "owner_id", "fence",
                    "heartbeat_at", "expires_at", "claimed_at", "created_at", "updated_at")),
            Map.entry("schedule_firings", List.of("firing_id", "schedule_id", "revision",
                    "slot_instant", "timezone", "offset_policy", "firing_state",
                    "operation_key", "request_digest", "template_snapshot_json",
                    "assignment_id", "task_run_id", "native_run_id", "correlation_id",
                    "error_class", "created_at", "updated_at")),
            Map.entry("schedule_firing_attempts", List.of("attempt_id", "firing_id", "attempt_number",
                    "eligible_at", "attempted_at", "claim_owner", "claim_fence",
                    "outcome", "error_class", "assignment_id", "task_run_id",
                    "native_run_id", "correlation_id", "created_at")),
            Map.entry("reaction_definitions", List.of("id", "agent_id", "revision",
                    "event_type", "filter_json", "action_type", "assignment_template_json",
                    "enabled", "deleted", "created_at", "updated_at")),
            Map.entry("reaction_dispatches", List.of("dispatch_id", "reaction_id", "revision",
                    "source_event_id", "operation_key", "request_digest", "template_snapshot_json",
                    "root_event_id", "parent_event_id", "parent_dispatch_id",
                    "depth", "dedupe_key", "dispatch_state", "suppression_reason",
                    "assignment_id", "task_run_id", "native_run_id", "correlation_id",
                    "created_at", "updated_at")),
            Map.entry("reaction_dispatch_attempts", List.of("attempt_id", "dispatch_id", "attempt_number",
                    "eligible_at", "attempted_at", "claim_owner", "claim_fence",
                    "outcome", "error_class", "assignment_id", "task_run_id",
                    "native_run_id", "correlation_id", "created_at")),
            Map.entry("agent_materializations", List.of("agent_id", "materialization_key",
                    "materialization_kind", "source_kind", "source_id", "generation", "state",
                    "root_relative_path", "failure_detail", "created_at", "updated_at")),
            Map.entry("context_defaults", List.of("agent_id", "version", "selection_kind",
                    "materialization_key", "materialization_generation",
                    "personal_selection", "reason", "created_at", "updated_at")),
            Map.entry("context_bindings", List.of("id", "agent_id", "binding_key",
                    "selection_kind", "materialization_key", "materialization_generation",
                    "personal_selection", "captured_at")),
            Map.entry("binding_owner_links", List.of("owner_type", "owner_id",
                    "binding_key", "created_at")),
            Map.entry("materialization_operation_results", List.of("operation_type",
                    "operation_key", "request_digest", "result_state",
                    "materialization_key", "result_json", "created_at")),
            Map.entry("operation_idempotency", List.of("operation_type", "operation_key",
                    "request_digest", "result_state", "binding_key", "owner_type",
                    "owner_id", "created_at"))
    );


    private final DataSource dataSource;
    private final JdbcTemplate jdbcTemplate;
    private final ResourcePatternResolver resourceResolver;

    @Autowired
    public SchemaMigrationRunner(DataSource dataSource,
                                 ResourcePatternResolver resourceResolver) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        this.jdbcTemplate = new JdbcTemplate(dataSource);
        this.resourceResolver = resourceResolver == null
                ? new PathMatchingResourcePatternResolver(Thread.currentThread().getContextClassLoader())
                : resourceResolver;
        runMigrations();
    }

    public List<MigrationRecord> appliedMigrations() {
        return jdbcTemplate.query(
                "select version, resource_name, checksum, applied_at from schema_migrations order by version",
                (rs, row) -> new MigrationRecord(rs.getInt(1), rs.getString(2), rs.getString(3),
                        Instant.parse(rs.getString(4))));
    }

    public record MigrationRecord(int version, String resourceName, String checksum, Instant appliedAt) { }

    private void runMigrations() {
        List<MigrationResource> resources = discoverResources();
        validateDiscovery(resources);
        try (Connection connection = dataSource.getConnection()) {
            runLocked(connection, resources);
        } catch (SQLException exception) {
            throw new MigrationException("Failed to run schema migrations", exception);
        }
    }

    private void runLocked(Connection connection, List<MigrationResource> resources) throws SQLException {
        boolean originalAutoCommit = connection.getAutoCommit();
        if (!originalAutoCommit) {
            connection.commit();
            connection.setAutoCommit(true);
        }
        try (Statement statement = connection.createStatement()) {
            statement.execute("pragma busy_timeout = 30000");
            statement.execute("begin immediate");
            try {
                boolean freshInit = initializeFreshSchema(connection);
                ensureMigrationTable(connection);
                normalizeMigrationTable(connection);
                validateMigrationTable(connection);
                normalizeEmptyPreFoundationTables(connection);
                if (freshInit) {
                    // schema.sql is the canonical end-state; mark all migrations as applied
                    for (MigrationResource resource : resources) {
                        insertApplied(connection, resource);
                    }
                }
                Map<Integer, AppliedMigration> applied = readApplied(connection);
                if (!freshInit && applied.isEmpty()) {
                    int adoptedVersion = highestCompatibleOwnedVersion(connection, resources);
                    for (MigrationResource resource : resources) {
                        if (resource.version() > adoptedVersion) break;
                        insertApplied(connection, resource);
                    }
                    if (adoptedVersion > 0) {
                        log.info("Adopted existing migration-owned schema through version {}", adoptedVersion);
                        applied = readApplied(connection);
                    }
                }
                validateBookkeeping(resources, applied);
                for (MigrationResource resource : resources) {
                    if (applied.containsKey(resource.version())) {
                        continue;
                    }
                    log.info("Applying migration {} (version {})", resource.name(), resource.version());
                    ScriptUtils.executeSqlScript(connection, new EncodedResource(
                            new ByteArrayResource(resource.bytes(), resource.name()), StandardCharsets.UTF_8));
                    validateOwnedSchema(connection, resource.version());
                    insertApplied(connection, resource);
                }
                validateOwnedSchema(connection, resources.isEmpty() ? 0 : resources.get(resources.size() - 1).version());
                statement.execute("commit");
            } catch (Throwable failure) {
                try {
                    statement.execute("rollback");
                } catch (SQLException rollbackFailure) {
                    failure.addSuppressed(rollbackFailure);
                }
                if (failure instanceof MigrationException migrationException) {
                    throw migrationException;
                }
                throw new MigrationException("Migration transaction failed", failure);
            }
        } finally {
            if (!originalAutoCommit) {
                connection.setAutoCommit(false);
            }
        }
    }

    private List<MigrationResource> discoverResources() {
        Resource[] discovered;
        try {
            discovered = resourceResolver.getResources(MIGRATION_PATH);
        } catch (IOException exception) {
            throw new MigrationException("Failed to discover migration resources", exception);
        }
        List<MigrationResource> migrations = new ArrayList<>();
        Set<String> names = new HashSet<>();
        for (Resource resource : discovered) {
            String name = resource.getFilename();
            if (name == null || !names.add(name)) {
                throw new MigrationException("Duplicate or unnamed migration resource: " + name);
            }
            Matcher matcher = MIGRATION_NAME.matcher(name);
            if (!matcher.matches()) {
                throw new MigrationException("Invalid migration resource name: " + name);
            }
            byte[] bytes = readBytes(resource, name);
            migrations.add(new MigrationResource(Integer.parseInt(matcher.group(1)), name,
                    bytes, sha256(bytes)));
        }
        migrations.sort(Comparator.comparingInt(MigrationResource::version));
        return List.copyOf(migrations);
    }

    private void validateDiscovery(List<MigrationResource> resources) {
        Set<Integer> versions = new HashSet<>();
        int expected = 1;
        for (MigrationResource resource : resources) {
            if (!versions.add(resource.version())) {
                throw new MigrationException("Duplicate migration version: " + resource.version());
            }
            if (resource.version() != expected) {
                throw new MigrationException("Migration version gap: expected " + expected
                        + " but found " + resource.version());
            }
            expected++;
        }
    }

    private boolean initializeFreshSchema(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery("""
                     select count(*) from sqlite_master
                     where type = 'table' and name not like 'sqlite_%'
                     """)) {
            if (!rows.next() || rows.getInt(1) != 0) {
                return false;
            }
        }
        Resource schema = resourceResolver.getResource(FRESH_SCHEMA_PATH);
        if (schema == null || !schema.exists()) {
            throw new MigrationException("Fresh schema resource is unavailable: " + FRESH_SCHEMA_PATH);
        }
        log.info("Initializing fresh schema from {}", FRESH_SCHEMA_PATH);
        ScriptUtils.executeSqlScript(connection,
                new EncodedResource(schema, StandardCharsets.UTF_8));
        return true;
    }

    private int highestCompatibleOwnedVersion(Connection connection,
                                              List<MigrationResource> resources) throws SQLException {
        for (int index = resources.size() - 1; index >= 0; index--) {
            int version = resources.get(index).version();
            try {
                validateOwnedSchema(connection, version);
                return version;
            } catch (MigrationException incomplete) {
                log.debug("Existing schema is not compatible with migration {}: {}",
                        version, incomplete.getMessage());
            }
        }
        return 0;
    }

    private void ensureMigrationTable(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("""
                    create table if not exists schema_migrations (
                        version integer primary key,
                        resource_name text not null unique,
                        checksum text not null check (length(checksum) = 64),
                        applied_at text not null
                    )
                    """);
        }
    }

    private void normalizeMigrationTable(Connection connection) throws SQLException {
        List<String> columns = tableColumns(connection, "schema_migrations");
        if (!columns.equals(List.of("version", "resource_name", "checksum", "applied_at"))) {
            throw new MigrationException("Incompatible schema_migrations table: " + columns);
        }
        String normalizedSql = normalized(tableSql(connection, "schema_migrations"));
        if (normalizedSql.contains("resource_name text not null unique")
                && normalizedSql.contains("check (length(checksum) = 64)")) {
            return;
        }
        if (tableColumns(connection, "schema_migrations_legacy_upgrade").size() > 0) {
            throw new MigrationException("Cannot normalize schema_migrations: legacy upgrade table already exists");
        }
        log.info("Normalizing legacy schema_migrations constraints");
        try (Statement statement = connection.createStatement()) {
            statement.execute("alter table schema_migrations rename to schema_migrations_legacy_upgrade");
            statement.execute("""
                    create table schema_migrations (
                        version integer primary key,
                        resource_name text not null unique,
                        checksum text not null check (length(checksum) = 64),
                        applied_at text not null
                    )
                    """);
            statement.execute("""
                    insert into schema_migrations(version, resource_name, checksum, applied_at)
                    select version, resource_name, checksum, applied_at
                    from schema_migrations_legacy_upgrade
                    """);
            statement.execute("drop table schema_migrations_legacy_upgrade");
        }
    }

    private void validateMigrationTable(Connection connection) throws SQLException {
        List<String> columns = tableColumns(connection, "schema_migrations");
        if (!columns.equals(List.of("version", "resource_name", "checksum", "applied_at"))) {
            throw new MigrationException("Incompatible schema_migrations table: " + columns);
        }
        String sql = tableSql(connection, "schema_migrations");
        if (!normalized(sql).contains("resource_name text not null unique")
                || !normalized(sql).contains("check (length(checksum) = 64)")) {
            throw new MigrationException("Incompatible schema_migrations constraints");
        }
    }

    private void normalizeEmptyPreFoundationTables(Connection connection) throws SQLException {
        List<String> locationColumns = tableColumns(connection, "task_run_locations");
        if (locationColumns.isEmpty() || locationColumns.contains("source_kind")) return;
        if (rowCount(connection, "schema_migrations") != 0) {
            throw new MigrationException("Pre-foundation task-run tables cannot be replaced after migration bookkeeping exists");
        }

        Map<String, List<String>> legacyTables = new LinkedHashMap<>();
        legacyTables.put("task_run_writer_claims", List.of("task_run_id", "attempt_number",
                "claim_generation", "claim_token", "created_at"));
        legacyTables.put("task_run_native_links", List.of("task_run_id", "plan_run_id",
                "workflow_run_id", "workflow_run_node_index", "job_run_id",
                "job_work_item_index", "assignment_id", "created_at"));
        legacyTables.put("task_run_idempotent_ops", List.of("operation_key", "request_digest",
                "task_run_id", "result_json", "created_at"));
        legacyTables.put("task_run_attempts", List.of("task_run_id", "attempt_number", "status",
                "started_at", "completed_at", "error_text", "created_at", "updated_at"));
        legacyTables.put("task_run_locations", List.of("task_run_id", "workspace_type", "workspace_key",
                "workspace_identity", "task_path_key", "root_relative_path", "created_at", "updated_at"));

        for (Map.Entry<String, List<String>> entry : legacyTables.entrySet()) {
            List<String> actual = tableColumns(connection, entry.getKey());
            if (!actual.equals(entry.getValue())) {
                throw new MigrationException("Incompatible partial pre-foundation table "
                        + entry.getKey() + ": " + actual);
            }
            if (rowCount(connection, entry.getKey()) != 0) {
                throw new MigrationException("Pre-foundation task-run table contains data and requires "
                        + "an explicit data migration: " + entry.getKey());
            }
        }

        log.info("Replacing empty pre-foundation task-run tables with ordered migration 001");
        try (Statement statement = connection.createStatement()) {
            for (String table : legacyTables.keySet()) {
                statement.execute("drop table " + table);
            }
        }
    }

    private int rowCount(Connection connection, String table) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery("select count(*) from " + table)) {
            return rows.next() ? rows.getInt(1) : 0;
        }
    }

    private Map<Integer, AppliedMigration> readApplied(Connection connection) throws SQLException {
        Map<Integer, AppliedMigration> applied = new LinkedHashMap<>();
        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery(
                     "select version, resource_name, checksum from schema_migrations order by version")) {
            while (rows.next()) {
                int version = rows.getInt(1);
                if (applied.put(version, new AppliedMigration(rows.getString(2), rows.getString(3))) != null) {
                    throw new MigrationException("Duplicate applied migration version: " + version);
                }
            }
        }
        return applied;
    }

    private void validateBookkeeping(List<MigrationResource> resources,
                                     Map<Integer, AppliedMigration> applied) {
        Map<Integer, MigrationResource> expected = new HashMap<>();
        resources.forEach(resource -> expected.put(resource.version(), resource));
        int contiguous = 1;
        for (Map.Entry<Integer, AppliedMigration> entry : applied.entrySet()) {
            if (entry.getKey() != contiguous++) {
                throw new MigrationException("Applied migration versions are not a contiguous prefix");
            }
            MigrationResource resource = expected.get(entry.getKey());
            if (resource == null) {
                throw new MigrationException("Unknown applied migration version " + entry.getKey());
            }
            AppliedMigration actual = entry.getValue();
            if (!resource.name().equals(actual.name())) {
                throw new MigrationException("Applied resource name mismatch for version " + entry.getKey());
            }
            if (!resource.checksum().equals(actual.checksum())) {
                throw new MigrationException("Checksum drift for applied migration " + resource.name());
            }
        }
    }

    private void insertApplied(Connection connection, MigrationResource resource) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "insert into schema_migrations(version, resource_name, checksum, applied_at) values (?, ?, ?, ?)")) {
            statement.setInt(1, resource.version());
            statement.setString(2, resource.name());
            statement.setString(3, resource.checksum());
            statement.setString(4, Instant.now().toString());
            statement.executeUpdate();
        }
    }

    private void validateOwnedSchema(Connection connection, int latestVersion) throws SQLException {
        if (latestVersion < 1) {
            return;
        }
        List<String> expectedTables = latestVersion >= 11 ? FOUNDATION_TABLES_V11
                : latestVersion >= 10 ? FOUNDATION_TABLES_V10
                : latestVersion >= 9 ? FOUNDATION_TABLES_V9
                : latestVersion >= 8 ? FOUNDATION_TABLES_V8
                : latestVersion >= 7 ? FOUNDATION_TABLES_V7
                : latestVersion >= 6 ? FOUNDATION_TABLES_V6
                : latestVersion >= 5 ? FOUNDATION_TABLES_V5
                : latestVersion >= 4 ? FOUNDATION_TABLES_V4
                : latestVersion >= 3 ? FOUNDATION_TABLES_V3
                : latestVersion >= 2 ? FOUNDATION_TABLES_V2 : FOUNDATION_TABLES_V1;
        for (String table : expectedTables) {
            List<String> actual = tableColumns(connection, table);
            List<String> expectedCols = FOUNDATION_COLUMNS.get(table);
            if (latestVersion == 1 && "task_run_idempotent_ops".equals(table)) {
                expectedCols = List.of("operation_key", "request_digest", "task_run_id",
                        "result_json", "created_at", "updated_at");
            }
            if (latestVersion == 1 && "task_run_locations".equals(table)) {
                expectedCols = expectedCols.stream()
                        .filter(column -> !column.equals("lineage_parent_task_run_id")
                                && !column.equals("lineage_mode"))
                        .toList();
            }
            if (expectedCols != null && !actual.containsAll(expectedCols)) {
                throw new MigrationException("Incompatible partial table " + table + ": " + actual);
            }
        }
        String locations = normalized(tableSql(connection, "task_run_locations"));
        if (!locations.contains("workspace_type in ('agent', 'user')")
                || !locations.contains("root_relative_path = 'workspace/'")) {
            throw new MigrationException("task_run_locations is missing confinement constraints");
        }
        String attempts = normalized(tableSql(connection, "task_run_attempts"));
        if (!attempts.contains("primary key (task_run_id, attempt_number)")
                || !attempts.contains("references task_run_locations(task_run_id) on delete cascade")) {
            throw new MigrationException("task_run_attempts is missing key or ownership constraints");
        }
        String operations = normalized(tableSql(connection, "task_run_idempotent_ops"));
        boolean operationsValid = latestVersion >= 2
                ? operations.contains("operation_type text check")
                    && operations.contains("unique (operation_type, operation_key)")
                    && operations.contains("operation_state text not null")
                    && operations.contains("'reserved', 'completed'")
                : operations.contains("operation_key text primary key");
        if (!operationsValid
                || !operations.contains("length(request_digest) = 64")
                || !operations.contains("references task_run_locations(task_run_id)")) {
            throw new MigrationException("task_run_idempotent_ops is missing scoped digest or ownership constraints");
        }
        String links = normalized(tableSql(connection, "task_run_native_links"));
        if (!links.contains("unique (native_kind, native_run_id)")
                || !links.contains("references work_assignments(id)")
                || !links.contains("workflow_child_id is null or native_kind = 'workflow'")
                || !links.contains("job_work_item_id is null or native_kind = 'job'")) {
            throw new MigrationException("task_run_native_links is missing explicit linkage constraints");
        }
        if (latestVersion == 1) {
            String claims = normalized(tableSql(connection, "task_run_writer_claims"));
            if (!claims.contains("primary key (task_run_id, attempt_number, claim_generation)")
                    || !claims.contains("claim_generation > 0")
                    || !claims.contains("fence_token text not null unique")
                    || !claims.contains("references task_run_attempts(task_run_id, attempt_number) on delete cascade")) {
                throw new MigrationException("task_run_writer_claims is missing monotonic fence constraints");
            }
        }
        if (latestVersion >= 2) {
            String fenceGens = normalized(tableSql(connection, "assignment_fence_generations"));
            if (!fenceGens.contains("primary key (assignment_id, generation)")
                    || !fenceGens.contains("generation > 0")
                    || !fenceGens.contains("references work_assignments(id)")) {
                throw new MigrationException("assignment_fence_generations is missing required constraints");
            }
            String claimGens = normalized(tableSql(connection, "writer_claim_generations"));
            if (!claimGens.contains("primary key (task_run_id, attempt_number, generation)")
                    || !claimGens.contains("unique (task_run_id, generation)")
                    || !claimGens.contains("is_current in (0, 1)")
                    || !claimGens.contains("references task_run_attempts(task_run_id, attempt_number) on delete cascade")) {
                throw new MigrationException("writer_claim_generations is missing required constraints");
            }
        }
        Set<String> allIndexes = objectNames(connection, "index", "idx_%");
        if (!allIndexes.containsAll(FOUNDATION_INDEXES_V1)) {
            throw new MigrationException("Missing migration-owned v1 indexes: "
                    + FOUNDATION_INDEXES_V1.stream().filter(index -> !allIndexes.contains(index)).toList());
        }
        if (latestVersion >= 2 && !allIndexes.containsAll(FOUNDATION_INDEXES_V2)) {
            throw new MigrationException("Missing migration-owned v2 indexes: "
                    + FOUNDATION_INDEXES_V2.stream().filter(index -> !allIndexes.contains(index)).toList());
        }
        if (latestVersion >= 3) {
            if (!allIndexes.containsAll(FOUNDATION_INDEXES_V3)) {
                throw new MigrationException("Missing migration-owned v3 indexes: "
                        + FOUNDATION_INDEXES_V3.stream().filter(index -> !allIndexes.contains(index)).toList());
            }
            String pubs = normalized(tableSql(connection, "output_publications"));
            if (!pubs.contains("publication_origin in ('task_run', 'chat', 'legacy')")
                    || !pubs.contains("workspace_kind in ('agent', 'chat', 'user')")
                    || !pubs.contains("publication_state in (")
                    || !pubs.contains("'reserved', 'staged', 'published', 'failed', 'integrity_failed'")
                    || !pubs.contains("unique (publication_origin, operation_key)")
                    || !pubs.contains("unique (workspace_kind, workspace_key, root_relative_path)")
                    || !pubs.contains("length(request_digest) = 64")
                    || !pubs.contains("length(expected_sha256) = 64")
                    || !pubs.contains("length(content_sha256) = 64")) {
                throw new MigrationException("output_publications is missing required constraints");
            }
        }
        if (latestVersion >= 7) {
            if (!allIndexes.containsAll(FOUNDATION_INDEXES_V7)) {
                throw new MigrationException("Missing migration-owned v7 indexes: "
                        + FOUNDATION_INDEXES_V7.stream().filter(index -> !allIndexes.contains(index)).toList());
            }
            String summaries = normalized(tableSql(connection, "run_summaries"));
            String measurements = normalized(tableSql(connection, "run_measurements"));
            if (!summaries.contains("normalized_status in (")
                    || !summaries.contains("'succeeded', 'failed', 'cancelled'")
                    || !summaries.contains("completeness in (")
                    || !summaries.contains("foreign key (task_run_id) references task_run_locations")) {
                throw new MigrationException("run_summaries is missing required constraints");
            }
            if (!measurements.contains("kind in ('model', 'tool')")
                    || !measurements.contains("outcome in (")
                    || !measurements.contains("completeness in (")
                    || !measurements.contains("foreign key (task_run_id) references task_run_locations")
                    || !measurements.contains("foreign key (task_run_id, attempt_number)")) {
                throw new MigrationException("run_measurements is missing required constraints");
            }
        }
        if (latestVersion >= 8) {
            if (!allIndexes.containsAll(FOUNDATION_INDEXES_V8)) {
                throw new MigrationException("Missing migration-owned v8 indexes: "
                        + FOUNDATION_INDEXES_V8.stream().filter(index -> !allIndexes.contains(index)).toList());
            }
            String schedDefs = normalized(tableSql(connection, "schedule_definitions"));
            String claims = normalized(tableSql(connection, "scheduler_claims"));
            String schedFirings = normalized(tableSql(connection, "schedule_firings"));
            String firingAttempts = normalized(tableSql(connection, "schedule_firing_attempts"));
            String rxnDefs = normalized(tableSql(connection, "reaction_definitions"));
            String rxnDispatches = normalized(tableSql(connection, "reaction_dispatches"));
            String rxnAttempts = normalized(tableSql(connection, "reaction_dispatch_attempts"));
            if (!schedDefs.contains("unique (id, revision)")
                    || !schedDefs.contains("revision integer not null check (revision >= 1)")
                    || !schedDefs.contains("dialect_version text not null")) {
                throw new MigrationException("schedule_definitions is missing required constraints");
            }
            if (!schedFirings.contains("unique (schedule_id, revision, slot_instant, timezone)")
                    || !schedFirings.contains("firing_state text not null check")
                    || !schedFirings.contains("length(request_digest) = 64")
                    || !schedFirings.contains("foreign key (schedule_id, revision) references schedule_definitions")) {
                throw new MigrationException("schedule_firings is missing required constraints");
            }
            if (!firingAttempts.contains("attempt_number integer not null check (attempt_number between 1 and 3)")
                    || !firingAttempts.contains("foreign key (firing_id) references schedule_firings")) {
                throw new MigrationException("schedule_firing_attempts is missing required constraints");
            }
            if (!rxnDefs.contains("unique (id, revision)")
                    || !rxnDefs.contains("action_type text not null check")) {
                throw new MigrationException("reaction_definitions is missing required constraints");
            }
            if (!rxnDispatches.contains("unique (reaction_id, revision, source_event_id)")
                    || !rxnDispatches.contains("length(request_digest) = 64")
                    || !rxnDispatches.contains("depth integer not null check")
                    || !rxnDispatches.contains("foreign key (reaction_id, revision) references reaction_definitions")) {
                throw new MigrationException("reaction_dispatches is missing required constraints");
            }
            if (!rxnAttempts.contains("foreign key (dispatch_id) references reaction_dispatches")) {
                throw new MigrationException("reaction_dispatch_attempts is missing required constraints");
            }
        }
        if (latestVersion >= 10) {
            if (!allIndexes.containsAll(FOUNDATION_INDEXES_V10)) {
                throw new MigrationException("Missing migration-owned v10 indexes: "
                        + FOUNDATION_INDEXES_V10.stream().filter(index -> !allIndexes.contains(index)).toList());
            }
            String materializations = normalized(tableSql(connection, "agent_materializations"));
            if (!materializations.contains("unique (agent_id, materialization_key)")
                    || !materializations.contains("materialization_kind text not null check")
                    || !materializations.contains("source_kind text not null check")
                    || !materializations.contains("state text not null check")
                    || !materializations.contains("check (root_relative_path is not null")) {
                throw new MigrationException("agent_materializations is missing required constraints");
            }
            String defaults = normalized(tableSql(connection, "context_defaults"));
            if (!defaults.contains("selection_kind text not null check")
                    || !defaults.contains("check ((selection_kind")) {
                throw new MigrationException("context_defaults is missing required constraints");
            }
            String bindings = normalized(tableSql(connection, "context_bindings"));
            if (!bindings.contains("unique (binding_key)")
                    || !bindings.contains("check ((selection_kind")) {
                throw new MigrationException("context_bindings is missing required constraints");
            }
            String ownerLinks = normalized(tableSql(connection, "binding_owner_links"));
            if (!ownerLinks.contains("unique (owner_type, owner_id)")
                    || !ownerLinks.contains("foreign key (binding_key) references context_bindings")) {
                throw new MigrationException("binding_owner_links is missing required constraints");
            }
            String opResults = normalized(tableSql(connection, "materialization_operation_results"));
            if (!opResults.contains("primary key (operation_type, operation_key)")
                    || !opResults.contains("length(request_digest) = 64")
                    || !opResults.contains("result_state text not null check")) {
                throw new MigrationException("materialization_operation_results is missing required constraints");
            }
        }
        if (latestVersion >= 11) {
            if (!allIndexes.containsAll(FOUNDATION_INDEXES_V11)) {
                throw new MigrationException("Missing migration-owned v11 indexes: "
                        + FOUNDATION_INDEXES_V11.stream().filter(index -> !allIndexes.contains(index)).toList());
            }
            String materializations = normalized(tableSql(connection, "agent_materializations"));
            if (!materializations.contains("length(materialization_key) = 75")
                    || !materializations.contains("substr(materialization_key, 1, 11) = 'v1-project-'")
                    || !materializations.contains("substr(materialization_key, 12) not glob '*[^0-9a-f]*'")) {
                throw new MigrationException("agent_materializations is missing strict materialization-key constraints");
            }
            String idempotency = normalized(tableSql(connection, "operation_idempotency"));
            if (!idempotency.contains("primary key (operation_type, operation_key)")
                    || !idempotency.contains("operation_type in ('binding_capture', 'owner_link')")
                    || !idempotency.contains("length(request_digest) = 64")
                    || !idempotency.contains("result_state text not null check")) {
                throw new MigrationException("operation_idempotency is missing required context-operation constraints");
            }
        }
        if (latestVersion >= 6) {
            if (!allIndexes.containsAll(FOUNDATION_INDEXES_V6)) {
                throw new MigrationException("Missing migration-owned v6 indexes: "
                        + FOUNDATION_INDEXES_V6.stream().filter(index -> !allIndexes.contains(index)).toList());
            }
            String snapshots = normalized(tableSql(connection, "skill_snapshots"));
            String trustDecisions = normalized(tableSql(connection, "skill_trust_decisions"));
            String snapshotResources = normalized(tableSql(connection, "skill_snapshot_resources"));
            String activations = normalized(tableSql(connection, "skill_activations"));
            String execAuths = normalized(tableSql(connection, "skill_executable_authorizations"));
            String packageOps = normalized(tableSql(connection, "skill_package_operations"));
            String resourceExecs = normalized(tableSql(connection, "skill_resource_executions"));
            if (!snapshots.contains("unique (snapshot_digest)")
                    || !snapshots.contains("unique (candidate_version_id)")
                    || !snapshots.contains("length(snapshot_digest) = 64")) {
                throw new MigrationException("skill_snapshots is missing required constraints");
            }
            if (!trustDecisions.contains("decision in ('approve', 'reject', 'revoke')")
                    || !trustDecisions.contains("scope in ('instruction', 'executable_resource', 'package_ingest')")
                    || !trustDecisions.contains("unique (operation_key)")) {
                throw new MigrationException("skill_trust_decisions is missing required constraints");
            }
            if (!snapshotResources.contains("unique (snapshot_id, relative_path)")
                    || !snapshotResources.contains("resource_type in ('script', 'reference', 'asset', 'skill_md')")) {
                throw new MigrationException("skill_snapshot_resources is missing required constraints");
            }
            if (!activations.contains("unique (snapshot_id, activation_context)")
                    || !activations.contains("foreign key (snapshot_id) references skill_snapshots(id)")) {
                throw new MigrationException("skill_activations is missing required constraints");
            }
            if (!execAuths.contains("unique (operation_key)")
                    || !execAuths.contains("confinement_capability in ('proven', 'unavailable', 'disabled')")) {
                throw new MigrationException("skill_executable_authorizations is missing required constraints");
            }
            if (!packageOps.contains("unique (operation_type, operation_key)")
                    || !packageOps.contains("operation_state in (")) {
                throw new MigrationException("skill_package_operations is missing required constraints");
            }
            if (!resourceExecs.contains("launch_state in (")
                    || !resourceExecs.contains("foreign key (snapshot_id) references skill_snapshots(id)")) {
                throw new MigrationException("skill_resource_executions is missing required constraints");
            }
        }
        if (latestVersion >= 5) {
            if (!allIndexes.containsAll(FOUNDATION_INDEXES_V5)) {
                throw new MigrationException("Missing migration-owned v5 indexes: "
                        + FOUNDATION_INDEXES_V5.stream().filter(index -> !allIndexes.contains(index)).toList());
            }
            String extractionDocs = normalized(tableSql(connection, "artifact_extraction_documents"));
            String lineage = normalized(tableSql(connection, "artifact_lineage_edges"));
            String retention = normalized(tableSql(connection, "task_run_retention_policies"));
            String pins = normalized(tableSql(connection, "artifact_retention_pins"));
            String sessions = normalized(tableSql(connection, "active_artifact_sessions"));
            if (!extractionDocs.contains("extraction_state in (")
                    || !extractionDocs.contains("artifact_id text not null unique")
                    || !extractionDocs.contains("foreign key (artifact_id) references output_publications")) {
                throw new MigrationException("artifact_extraction_documents is missing required constraints");
            }
            if (!lineage.contains("lineage_kind in (")
                    || !lineage.contains("unique (parent_artifact_id, child_artifact_id, lineage_kind)")
                    || !lineage.contains("parent_artifact_id <> child_artifact_id")) {
                throw new MigrationException("artifact_lineage_edges is missing required constraints");
            }
            if (!retention.contains("unique (workspace_kind, workspace_key, task_path_key)")
                    || !retention.contains("policy_type in (")) {
                throw new MigrationException("task_run_retention_policies is missing required constraints");
            }
            if (!pins.contains("pin_kind in (")
                    || !pins.contains("artifact_id is not null or task_run_id is not null")) {
                throw new MigrationException("artifact_retention_pins is missing required constraints");
            }
            if (!sessions.contains("state in (")
                    || !sessions.contains("session_token text not null unique")
                    || !sessions.contains("foreign key (artifact_id) references output_publications")) {
                throw new MigrationException("active_artifact_sessions is missing required constraints");
            }
        }
        if (latestVersion >= 4) {
            if (!allIndexes.containsAll(FOUNDATION_INDEXES_V4)) {
                throw new MigrationException("Missing migration-owned v4 indexes: "
                        + FOUNDATION_INDEXES_V4.stream().filter(index -> !allIndexes.contains(index)).toList());
            }
            String candidates = normalized(tableSql(connection, "skill_candidate_versions"));
            String observations = normalized(tableSql(connection, "skill_candidate_observations"));
            String overlays = normalized(tableSql(connection, "skill_exact_assignments"));
            String legacy = normalized(tableSql(connection, "skill_legacy_conversion_diagnostics"));
            if (!candidates.contains("scope_type in ('workspace', 'bound_agent', 'project', 'global')")
                    || !candidates.contains("provenance in ('global_package', 'workspace_flat')")
                    || !candidates.contains("length(content_digest) = 64")
                    || !observations.contains("is_available in (0, 1)")
                    || !observations.contains("references skill_candidate_versions(id)")
                    || !overlays.contains("target_type in ('agent', 'project', 'job', 'task', 'workflow', 'chat_session')")
                    || !overlays.contains("unique (logical_key, target_type, target_key)")
                    || !overlays.contains("references skill_candidate_versions(id)")
                    || !legacy.contains("unique (agent_id, logical_key)")) {
                throw new MigrationException("scoped skill tables are missing required constraints");
            }
        }
    }

    private Set<String> objectNames(Connection connection, String type, String likePattern)
            throws SQLException {
        Set<String> names = new HashSet<>();
        try (PreparedStatement statement = connection.prepareStatement(
                "select name from sqlite_master where type = ? and name like ?")) {
            statement.setString(1, type);
            statement.setString(2, likePattern);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    names.add(rows.getString(1));
                }
            }
        }
        return names;
    }

    private List<String> tableColumns(Connection connection, String table) throws SQLException {
        List<String> columns = new ArrayList<>();
        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery("pragma table_info('" + table + "')")) {
            while (rows.next()) {
                columns.add(rows.getString("name"));
            }
        }
        return columns;
    }

    private String tableSql(Connection connection, String table) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "select sql from sqlite_master where type = 'table' and name = ?")) {
            statement.setString(1, table);
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) {
                    throw new MigrationException("Missing migration-owned table: " + table);
                }
                return rows.getString(1);
            }
        }
    }

    private static String normalized(String sql) {
        return sql.replaceAll("\\s+", " ").trim().toLowerCase();
    }

    private static byte[] readBytes(Resource resource, String name) {
        try (InputStream input = resource.getInputStream()) {
            return input.readAllBytes();
        } catch (IOException exception) {
            throw new MigrationException("Failed to read migration resource: " + name, exception);
        }
    }

    static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException exception) {
            throw new MigrationException("SHA-256 is unavailable", exception);
        }
    }

    private record MigrationResource(int version, String name, byte[] bytes, String checksum) { }
    private record AppliedMigration(String name, String checksum) { }

    public static final class MigrationException extends RuntimeException {
        public MigrationException(String message) { super(message); }
        public MigrationException(String message, Throwable cause) { super(message, cause); }
    }
}
