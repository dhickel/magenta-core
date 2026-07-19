package io.mindspice.magenta2.ai.orchestration.workspaces;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import io.mindspice.magenta2.ai.config.user.AiConfig;
import io.mindspice.magenta2.ai.orchestration.agents.AgentProfileRepository;
import io.mindspice.magenta2.core.util.PlainPathSegmentValidator;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class WorkspaceService {
    private static final int DEFAULT_LIST_LIMIT = 50;
    private static final int MIN_LIST_LIMIT = 1;
    private static final int MAX_LIST_LIMIT = 200;

    private final WorkspaceRepository repository;
    private final RootRelativePathService rootRelativePathService;
    private final Path dataRoot;

    @Autowired
    public WorkspaceService(
        WorkspaceRepository repository,
        AiConfig aiConfig,
        RootRelativePathService rootRelativePathService
    ) throws IOException {
        this.repository = repository;
        this.rootRelativePathService = rootRelativePathService;
        if (aiConfig == null || aiConfig.dataRoot() == null) {
            throw new IllegalArgumentException("AI config dataRoot is required for workspaces");
        }
        this.dataRoot = Files.createDirectories(aiConfig.dataRoot()).toRealPath();
    }

    /** Constructor adaptation for legacy callers that previously relied on
     * the removed unchecked directory-service constructor. */
    public WorkspaceService(
            WorkspaceRepository repository,
            AiConfig aiConfig,
            AgentProfileRepository agentProfileRepository,
            ChatConversationExistenceRepository chatConversationExistenceRepository) throws IOException {
        this(repository, aiConfig,
                new RootRelativePathService(new WorkspaceDirectoryService(
                        aiConfig, agentProfileRepository, chatConversationExistenceRepository)));
    }

    public Workspace get(String id) {
        return repository.findById(id).orElseThrow(() -> new IllegalStateException("Workspace not found: " + id));
    }

    public List<Workspace> list(WorkspaceOwnerType ownerType, String ownerId, int limit) {
        int boundedLimit = boundListLimit(limit);
        return repository.findAll(ownerType, ownerId, boundedLimit);
    }

    public Workspace agentWorkspace(String agentId, String displayName) {
        PlainPathSegmentValidator.requirePlainSegment(agentId, "agentId");
        return repository.findByOwner(WorkspaceOwnerType.AGENT, agentId)
            .orElseGet(() -> createWorkspace(
                WorkspaceOwnerType.AGENT,
                agentId,
                WorkspacePathLayout.relativeString(WorkspacePathLayout.agentWorkspaceRoot(agentId)),
                displayName
            ));
    }

    public Workspace jobWorkspace(String jobId, String displayName) {
        PlainPathSegmentValidator.requirePlainSegment(jobId, "jobId");
        return repository.findByOwner(WorkspaceOwnerType.JOB, jobId)
            .orElseThrow(() -> new IllegalStateException(
                "Job-owned workspaces are retired; bind jobs to an agent, project, or Work Area instead."));
    }

    public Workspace projectWorkspace(String projectId, String displayName) {
        PlainPathSegmentValidator.requirePlainSegment(projectId, "projectId");
        return repository.findByOwner(WorkspaceOwnerType.PROJECT, projectId)
            .orElseGet(() -> createWorkspace(
                WorkspaceOwnerType.PROJECT,
                projectId,
                WorkspacePathLayout.relativeString(WorkspacePathLayout.projectRoot(projectId)),
                displayName
            ));
    }

    public Path assignmentPath(String agentId, String assignmentId) {
        PlainPathSegmentValidator.requirePlainSegment(agentId, "agentId");
        PlainPathSegmentValidator.requirePlainSegment(assignmentId, "assignmentId");
        return confined(WorkspacePathLayout.relativeString(
            WorkspacePathLayout.agentWork(agentId).resolve(assignmentId)
        ));
    }

    public List<WorkspaceLink> links(String workspaceId) {
        get(workspaceId);
        return repository.links(workspaceId).stream()
            .map(this::normalizeListedLink)
            .flatMap(Optional::stream)
            .toList();
    }

    public List<WorkspaceLease> activeLeases(String workspaceId) {
        get(workspaceId);
        return repository.findActiveLeases(workspaceId);
    }

    public WorkspaceLink addLink(String workspaceId, WorkspaceLink link) {
        Workspace workspace = get(workspaceId);
        if (!StringUtils.hasText(link.label())) {
            throw new IllegalArgumentException("workspace link label is required");
        }
        WorkspaceLinkType type = link.linkType() == null ? WorkspaceLinkType.PATH : link.linkType();
        String target = normalizeLinkTarget(workspace, type, link.target());
        return repository.saveLink(new WorkspaceLink(
            StringUtils.hasText(link.id()) ? link.id() : UUID.randomUUID().toString(),
            workspaceId,
            link.label(),
            type,
            target,
            link.readable(),
            link.writable(),
            null,
            null
        ));
    }

    public void deleteLink(String workspaceId, String linkId) {
        get(workspaceId);
        repository.deleteLink(workspaceId, linkId);
    }

    public Path archiveAgentWorkspaceData(String agentId) {
        PlainPathSegmentValidator.requirePlainSegment(agentId, "agentId");
        Path source = confined(WorkspacePathLayout.relativeString(WorkspacePathLayout.agentWorkspaceRoot(agentId)));
        if (!Files.exists(source)) {
            return source;
        }
        Path archiveRoot = confined(WorkspacePathLayout.WORKSPACE + "/.archive");
        try {
            Files.createDirectories(archiveRoot);
            String stamp = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").format(
                java.time.ZonedDateTime.ofInstant(Instant.now(), java.time.ZoneOffset.UTC));
            Path target = archiveRoot.resolve(agentId + "-" + stamp).normalize();
            Files.move(source, target);
            return target;
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to archive agent workspace data for " + agentId, exception);
        }
    }

    public void deleteAgentWorkspaceData(String agentId) {
        PlainPathSegmentValidator.requirePlainSegment(agentId, "agentId");
        Path source = confined(WorkspacePathLayout.relativeString(WorkspacePathLayout.agentWorkspaceRoot(agentId)));
        if (!Files.exists(source)) {
            return;
        }
        try (var stream = Files.walk(source)) {
            stream.sorted(Comparator.reverseOrder())
                .forEach(path -> {
                    try {
                        Files.deleteIfExists(path);
                    } catch (IOException ignored) {
                        // best effort
                    }
                });
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to delete agent workspace data for " + agentId, exception);
        }
    }

    private Workspace createWorkspace(
        WorkspaceOwnerType ownerType,
        String ownerId,
        String rootRelativePath,
        String displayName
    ) {
        if (!StringUtils.hasText(ownerId)) {
            throw new IllegalArgumentException("workspace owner id is required");
        }
        Path root = confined(rootRelativePath);
        try {
            Files.createDirectories(root);
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to create workspace root: " + root, exception);
        }
        return repository.save(new Workspace(
            UUID.randomUUID().toString(),
            ownerType,
            ownerId,
            rootRelativePath,
            StringUtils.hasText(displayName) ? displayName : ownerType.name().toLowerCase() + " " + ownerId,
            "{}",
            null,
            null
        ));
    }

    private String normalizeLinkTarget(Workspace workspace, WorkspaceLinkType type, String target) {
        if (!StringUtils.hasText(target)) {
            throw new IllegalArgumentException("workspace link target is required");
        }
        if (type != WorkspaceLinkType.PATH) {
            return target;
        }

        Path path = Path.of(target.replace('\\', '/'));
        Path resolved;
        if (path.isAbsolute()) {
            resolved = path.normalize();
        } else {
            for (Path segment : path) {
                if ("..".equals(segment.toString())) {
                    throw new IllegalArgumentException("workspace link target escapes data root");
                }
            }
            Path root = confined(workspace.rootRelativePath());
            resolved = root.resolve(path).normalize();
        }
        if (!resolved.startsWith(dataRoot)) {
            throw new IllegalArgumentException("workspace link target escapes data root");
        }
        return rootRelativePathService.store(resolved);
    }

    private Optional<WorkspaceLink> normalizeListedLink(WorkspaceLink link) {
        if (link == null || link.linkType() != WorkspaceLinkType.PATH) {
            return Optional.ofNullable(link);
        }
        try {
            String target = rootRelativePathService.store(rootRelativePathService.resolve(link.target()));
            return Optional.of(new WorkspaceLink(
                link.id(),
                link.workspaceId(),
                link.label(),
                link.linkType(),
                target,
                link.readable(),
                link.writable(),
                link.createdAt(),
                link.updatedAt()
            ));
        } catch (IllegalArgumentException ignored) {
            return Optional.empty();
        }
    }

    private Path confined(String relativePath) {
        Path relative = Path.of(relativePath);
        if (relative.isAbsolute()) {
            throw new IllegalArgumentException("workspace path must be relative to data root");
        }
        Path resolved = dataRoot.resolve(relative).normalize();
        if (!resolved.startsWith(dataRoot)) {
            throw new IllegalArgumentException("workspace path escapes data root");
        }
        return resolved;
    }

    private int boundListLimit(int limit) {
        if (limit <= 0) {
            return DEFAULT_LIST_LIMIT;
        }
        return Math.max(MIN_LIST_LIMIT, Math.min(MAX_LIST_LIMIT, limit));
    }

}
