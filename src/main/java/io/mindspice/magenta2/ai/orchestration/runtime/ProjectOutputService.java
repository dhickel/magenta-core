package io.mindspice.magenta2.ai.orchestration.runtime;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import io.mindspice.magenta2.ai.orchestration.workspaces.OutputArtifactRepository;
import io.mindspice.magenta2.ai.orchestration.workspaces.PublicationRecord;
import io.mindspice.magenta2.ai.orchestration.workspaces.PublicationState;
import io.mindspice.magenta2.ai.orchestration.workspaces.RunOutputArtifact;

/**
 * Sole project-output authorization and query boundary.
 *
 * <p>Discovers project outputs through durable persisted links
 * ({@code assignment → taskRunId → published artifact}), never
 * through filesystem scanning. Every metadata, preview, and download
 * access revalidates project attribution through the same resolver.</p>
 *
 * <h3>Durable-link chain</h3>
 * <ul>
 *   <li>Project → assignments ({@code work_assignments.project_id})</li>
 *   <li>Assignment → task-run identity ({@code checkpoint.taskRunId})</li>
 *   <li>Task-run → published artifacts ({@code output_publications.task_run_id})</li>
 *   <li>Legacy artifacts with direct {@code project_id} are included as-is</li>
 * </ul>
 *
 * <p>This service does not copy, re-publish, replace, or scan for
 * publications. It projects the canonical publication store through
 * a project scope only.</p>
 */
@Service
public class ProjectOutputService {
    private static final Logger log = LoggerFactory.getLogger(ProjectOutputService.class);
    private static final int DEFAULT_LIMIT = 40;
    private static final int MAX_LIMIT = 200;

    private final AssignmentService assignmentService;
    private final OutputArtifactRepository artifactRepository;
    private final ProjectService projectService;
    private final OrchestrationRuntimeRepository runtimeRepository;

    public ProjectOutputService(AssignmentService assignmentService,
                                 OutputArtifactRepository artifactRepository,
                                 ProjectService projectService,
                                 OrchestrationRuntimeRepository runtimeRepository) {
        this.assignmentService = assignmentService;
        this.artifactRepository = artifactRepository;
        this.projectService = projectService;
        this.runtimeRepository = runtimeRepository;
    }

    // ── Public DTOs ──

    /**
     * Redacted project output item safe for browser rendering.
     * Contains no filesystem paths, staging details, or writer secrets.
     */
    public record ProjectOutputItem(
            String artifactId,
            String logicalName,
            String fileExtension,
            String mediaType,
            Long verifiedSize,
            String formattedSize,
            String taskRunId,
            String workspaceKind,
            String workspaceKey,
            String origin,           // "TASK_RUN" or "LEGACY"
            Instant createdAt,
            String runType,          // legacy field
            String projectId         // for routing consistency
    ) {}

    /** Authorized handle produced after revalidating project attribution. */
    public record AuthorizedPublication(
            PublicationRecord publication,
            String projectId
    ) {}

    /** Authorized handle for a legacy artifact. */
    public record AuthorizedLegacyArtifact(
            RunOutputArtifact artifact,
            String projectId
    ) {}

    // ── List outputs ──

    /**
     * List project outputs through the durable-link chain.
     * Collects taskRunIds from all known project assignments, then
     * finds published publications and legacy artifacts.
     */
    public List<ProjectOutputItem> listOutputs(String projectId) {
        return listOutputs(projectId, DEFAULT_LIMIT);
    }

    public List<ProjectOutputItem> listOutputs(String projectId, int limit) {
        int effectiveLimit = Math.clamp(limit, 1, MAX_LIMIT);
        projectService.getProject(projectId); // 404 guard

        List<ProjectOutputItem> items = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();

        // Collect taskRunIds from all known project assignments
        Set<String> projectTaskRunIds = collectProjectTaskRunIds(projectId);

        // 1. Published publication records linked via taskRunId
        for (String taskRunId : projectTaskRunIds) {
            if (items.size() >= effectiveLimit) break;
            List<PublicationRecord> publications = artifactRepository.findByTaskRunId(taskRunId);
            for (PublicationRecord pub : publications) {
                if (items.size() >= effectiveLimit) break;
                if (pub.publicationState() != PublicationState.PUBLISHED) continue;
                if (!pub.isReadable()) continue;
                String key = "pub:" + pub.artifactId();
                if (!seen.add(key)) continue;
                items.add(toItem(pub, "TASK_RUN"));
            }
        }

        // 2. Legacy run_output_artifacts with direct project_id
        List<RunOutputArtifact> legacy = artifactRepository.findArtifactsByProjectId(
                projectId, effectiveLimit);
        for (RunOutputArtifact art : legacy) {
            if (items.size() >= effectiveLimit) break;
            String key = "legacy:" + art.id();
            if (!seen.add(key)) continue;
            items.add(toItem(art));
        }

        items.sort(Comparator.comparing(ProjectOutputItem::createdAt,
                Comparator.nullsLast(Comparator.reverseOrder()))
                .thenComparing(ProjectOutputItem::artifactId));
        if (items.size() > effectiveLimit) {
            items = new ArrayList<>(items.subList(0, effectiveLimit));
        }
        return Collections.unmodifiableList(items);
    }

    // ── Authorize single output ──

    /**
     * Revalidate project attribution for a single artifact id.
     * Checks both {@code output_publications} and {@code run_output_artifacts}
     * and proves the durable relation to the scoped project.
     */
    public Optional<AuthorizedPublication> authorizePublication(String projectId, String artifactId) {
        if (!StringUtils.hasText(projectId) || !StringUtils.hasText(artifactId)) {
            return Optional.empty();
        }
        PublicationRecord publication = artifactRepository.findById(artifactId).orElse(null);
        if (publication == null) return Optional.empty();
        if (publication.publicationState() != PublicationState.PUBLISHED) return Optional.empty();
        if (!publication.isReadable()) return Optional.empty();

        // Prove durable project lineage
        if (!hasDurableProjectLineage(projectId, publication)) {
            return Optional.empty();
        }
        return Optional.of(new AuthorizedPublication(publication, projectId));
    }

    public Optional<AuthorizedLegacyArtifact> authorizeLegacyArtifact(String projectId, String artifactId) {
        if (!StringUtils.hasText(projectId) || !StringUtils.hasText(artifactId)) {
            return Optional.empty();
        }
        RunOutputArtifact artifact = artifactRepository.findArtifactById(artifactId).orElse(null);
        if (artifact == null) return Optional.empty();
        if (!projectId.equals(artifact.projectId())) return Optional.empty();
        return Optional.of(new AuthorizedLegacyArtifact(artifact, projectId));
    }

    // ── Durable lineage proof ──

    /**
     * Prove that a publication belongs to a project through the
     * durable assignment chain. Accepts only artifacts whose
     * taskRunId maps to a project-attributed assignment.
     */
    private boolean hasDurableProjectLineage(String projectId, PublicationRecord publication) {
        if (!StringUtils.hasText(publication.taskRunId())) return false;

        // Check all active and terminal assignments for the project
        List<AssignmentService.AssignmentSummary> active = assignmentService
                .activeSummariesForProject(projectId);
        for (AssignmentService.AssignmentSummary a : active) {
            if (publication.taskRunId().equals(a.taskRunId())) return true;
        }

        List<WorkAssignment> terminal = runtimeRepository
                .findTerminalAssignmentsForProject(projectId, 200);
        for (WorkAssignment a : terminal) {
            String trId = textFromCheckpoint(a, "taskRunId");
            if (publication.taskRunId().equals(trId)) return true;
        }

        return false;
    }

    // ── Internal helpers ──

    /** Collect all taskRunIds from active and terminal project assignments. */
    private Set<String> collectProjectTaskRunIds(String projectId) {
        Set<String> ids = new LinkedHashSet<>();

        List<AssignmentService.AssignmentSummary> active = assignmentService
                .activeSummariesForProject(projectId);
        for (AssignmentService.AssignmentSummary a : active) {
            if (StringUtils.hasText(a.taskRunId())) {
                ids.add(a.taskRunId());
            }
        }

        List<WorkAssignment> terminal = runtimeRepository
                .findTerminalAssignmentsForProject(projectId, 200);
        for (WorkAssignment a : terminal) {
            String trId = textFromCheckpoint(a, "taskRunId");
            if (StringUtils.hasText(trId)) {
                ids.add(trId);
            }
        }

        return ids;
    }

    @SuppressWarnings("unchecked")
    private static String textFromCheckpoint(WorkAssignment assignment, String key) {
        if (assignment == null || assignment.checkpoint() == null) return null;
        Object value = assignment.checkpoint().get(key);
        if (value == null) {
            // Also check output map
            if (assignment.output() != null) {
                value = assignment.output().get(key);
            }
        }
        if (value instanceof String s) return s;
        if (value != null) return value.toString();
        return null;
    }

    private static ProjectOutputItem toItem(PublicationRecord pub, String origin) {
        return new ProjectOutputItem(
                pub.artifactId(),
                pub.logicalName(),
                pub.fileExtension(),
                pub.mediaType(),
                pub.verifiedSize(),
                formatSize(pub.verifiedSize()),
                pub.taskRunId(),
                pub.workspaceKind(),
                pub.workspaceKey(),
                origin,
                pub.publishedAt() != null ? pub.publishedAt() : pub.createdAt(),
                null,
                null);
    }

    private static ProjectOutputItem toItem(RunOutputArtifact art) {
        return new ProjectOutputItem(
                art.id(),
                art.outputName(),
                extractExtension(art.fileName()),
                null, // legacy artifacts don't store mediaType
                null,
                null,
                art.runId(),
                art.workspaceType(),
                art.workspaceKey(),
                "LEGACY",
                art.createdAt(),
                art.runType(),
                art.projectId());
    }

    public static String formatSize(Long bytes) {
        if (bytes == null) return null;
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
        if (bytes < 1024 * 1024 * 1024) return String.format("%.1f MB", bytes / (1024.0 * 1024.0));
        return String.format("%.1f GB", bytes / (1024.0 * 1024.0 * 1024.0));
    }

    private static String extractExtension(String fileName) {
        if (fileName == null) return null;
        int dot = fileName.lastIndexOf('.');
        return dot > 0 && dot < fileName.length() - 1 ? fileName.substring(dot) : null;
    }
}
