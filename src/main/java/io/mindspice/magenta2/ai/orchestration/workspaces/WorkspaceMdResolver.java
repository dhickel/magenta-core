package io.mindspice.magenta2.ai.orchestration.workspaces;

import java.io.IOException;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Objects;

import io.mindspice.magenta2.ai.orchestration.context.AgentContextRepository.PersonalSelection;
import org.springframework.util.StringUtils;

/**
 * Resolves at most one bounded {@code WORKSPACE.md} from the single trusted
 * root selected by the immutable captured context.
 *
 * <p>A materialization binding selects the authorized materialization root;
 * a personal binding selects the captured personal-area root ({@code home},
 * {@code scripts}, or {@code apps}). Both roots are never scanned, and a
 * failed root never falls through to an alternate.
 *
 * <p>{@code WORKSPACE.md} is human-authored untrusted guidance. It is never
 * parsed as settings, policy, tool permission, or authority. An absent
 * optional file contributes no guidance without implying failed acquisition.
 */
public class WorkspaceMdResolver {

    private static final String WORKSPACE_MD = "WORKSPACE.md";
    private static final int MAX_BYTES = 65536; // 64 KB hard limit
    private static final int MAX_PROMPT_CHARS = 8192; // truncation for prompt injection

    private WorkspaceMdResolver() {
    }

    /**
     * Read a bounded, confined {@code WORKSPACE.md} from a materialization
     * location. Returns empty when the file is absent; any integrity or I/O
     * failure is terminal.
     *
     * @param location the verified materialization location
     * @return resolved guidance or empty for an absent optional file
     * @throws WorkspaceMdException for any integrity, UTF-8, link, escape,
     *         or I/O failure
     */
    public static WorkspaceMdResult resolveFromMaterialization(
            MaterializationLocation location) {
        Objects.requireNonNull(location, "location");
        return resolveFromRoot(location.confinedPath(),
                "materialization " + location.materializationKey().serialized(),
                location.generation(),
                location.agentDescriptor().root());
    }

    /**
     * Read a bounded, confined {@code WORKSPACE.md} from a personal-area
     * root within an AGENT descriptor.
     *
     * @param agentDescriptor the verified AGENT descriptor
     * @param personalRoot    the confined personal-area path
     * @param areaName        display name for provenance labelling
     * @return resolved guidance or empty for an absent optional file
     * @throws WorkspaceMdException for any integrity, UTF-8, link, escape,
     *         or I/O failure
     */
    public static WorkspaceMdResult resolveFromPersonalArea(
            WorkspaceDescriptor agentDescriptor,
            PersonalSelection selection) {
        Objects.requireNonNull(agentDescriptor, "agentDescriptor");
        Objects.requireNonNull(selection, "selection");
        if (agentDescriptor.kind() != WorkspaceKind.AGENT) {
            throw new IllegalArgumentException(
                    "Personal-area WORKSPACE.md requires an AGENT descriptor");
        }
        AgentWorkspaceArea area = switch (selection) {
            case HOME -> AgentWorkspaceArea.HOME;
            case SCRIPTS -> AgentWorkspaceArea.SCRIPTS;
            case APPS -> AgentWorkspaceArea.APPS;
        };
        Path personalRoot = agentDescriptor.root().resolve(area.directoryName());
        return resolveFromRoot(personalRoot,
                "personal area " + area.directoryName(), null,
                agentDescriptor.root());
    }

    private static WorkspaceMdResult resolveFromRoot(
            Path searchRoot, String provenanceLabel, Integer generation,
            Path authorityBoundary) {
        Path normalizedAuthority = authorityBoundary.normalize();
        Path normalizedSearchRoot = searchRoot.normalize();
        if (!normalizedSearchRoot.startsWith(normalizedAuthority)) {
            throw new WorkspaceMdException("WORKSPACE.md search root escapes authority boundary");
        }
        rejectLinkedComponents(normalizedAuthority, normalizedSearchRoot, provenanceLabel);

        if (Files.notExists(normalizedSearchRoot, LinkOption.NOFOLLOW_LINKS)) {
            return WorkspaceMdResult.absent();
        }
        if (!Files.isDirectory(normalizedSearchRoot, LinkOption.NOFOLLOW_LINKS)) {
            throw new WorkspaceMdException("WORKSPACE.md search root is not a directory");
        }

        Path workspaceMd = normalizedSearchRoot.resolve(WORKSPACE_MD).normalize();
        if (!workspaceMd.startsWith(normalizedSearchRoot)) {
            throw new WorkspaceMdException(
                    "WORKSPACE.md path escapes root: " + provenanceLabel);
        }

        if (!Files.exists(workspaceMd, LinkOption.NOFOLLOW_LINKS)) {
            if (Files.notExists(workspaceMd, LinkOption.NOFOLLOW_LINKS)) {
                return WorkspaceMdResult.absent();
            }
            throw new WorkspaceMdException(
                    "Failed to determine whether WORKSPACE.md exists: " + provenanceLabel);
        }

        // Reject links and non-regular files.
        if (Files.isSymbolicLink(workspaceMd)) {
            throw new WorkspaceMdException(
                    "WORKSPACE.md is a symbolic link: " + provenanceLabel);
        }
        if (!Files.isRegularFile(workspaceMd, LinkOption.NOFOLLOW_LINKS)) {
            throw new WorkspaceMdException(
                    "WORKSPACE.md is not a regular file: " + provenanceLabel);
        }

        // Validate both resolved paths against the selected authority boundary.
        try {
            Path realAuthority = normalizedAuthority.toRealPath(LinkOption.NOFOLLOW_LINKS);
            Path realSearchRoot = normalizedSearchRoot.toRealPath(LinkOption.NOFOLLOW_LINKS);
            Path realMd = workspaceMd.toRealPath(LinkOption.NOFOLLOW_LINKS);
            if (!realSearchRoot.startsWith(realAuthority) || !realMd.startsWith(realSearchRoot)
                    || !realMd.startsWith(realAuthority)) {
                throw new WorkspaceMdException(
                        "WORKSPACE.md escapes selected authority root: " + provenanceLabel);
            }
        } catch (IOException e) {
            throw new WorkspaceMdException("Failed to resolve WORKSPACE.md real path", e);
        }

        // Read bounded bytes.
        byte[] bytes;
        try {
            long fileSize = Files.size(workspaceMd);
            if (fileSize > MAX_BYTES) {
                throw exceedsMaximumSize(provenanceLabel);
            }
            bytes = Files.readAllBytes(workspaceMd);
            if (bytes.length > MAX_BYTES) {
                throw exceedsMaximumSize(provenanceLabel);
            }
        } catch (IOException e) {
            throw new WorkspaceMdException("Failed to read WORKSPACE.md", e);
        }

        // Validate UTF-8
        String content;
        try {
            content = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(java.nio.ByteBuffer.wrap(bytes))
                    .toString();
        } catch (CharacterCodingException e) {
            throw new WorkspaceMdException(
                    "WORKSPACE.md is not valid UTF-8: " + provenanceLabel);
        }

        // Check for null bytes (reject binary content)
        if (content.indexOf('\0') >= 0) {
            throw new WorkspaceMdException(
                    "WORKSPACE.md contains null bytes: " + provenanceLabel);
        }

        // Build provenance label
        StringBuilder label = new StringBuilder();
        label.append("WORKSPACE.md from ");
        label.append(provenanceLabel);
        if (generation != null) {
            label.append(" (generation ").append(generation).append(")");
        }

        // Truncate for prompt injection
        String promptContent = content;
        boolean truncated = false;
        if (promptContent.length() > MAX_PROMPT_CHARS) {
            promptContent = promptContent.substring(0, MAX_PROMPT_CHARS);
            truncated = true;
        }

        return WorkspaceMdResult.present(promptContent, label.toString(), truncated);
    }

    private static void rejectLinkedComponents(Path authorityBoundary, Path searchRoot,
                                               String provenanceLabel) {
        Path component = authorityBoundary;
        while (true) {
            if (Files.isSymbolicLink(component)) {
                throw new WorkspaceMdException(
                        "WORKSPACE.md path component is a symbolic link: " + provenanceLabel);
            }
            if (component.equals(searchRoot)) {
                return;
            }
            component = component.resolve(authorityBoundary.relativize(searchRoot)
                    .subpath(component.getNameCount() - authorityBoundary.getNameCount(),
                            component.getNameCount() - authorityBoundary.getNameCount() + 1));
        }
    }

    private static WorkspaceMdException exceedsMaximumSize(String provenanceLabel) {
        return new WorkspaceMdException("WORKSPACE.md exceeds maximum size (" + MAX_BYTES + " bytes): "
                + provenanceLabel);
    }

    // ── Result type ──

    /**
     * Bounded result for WORKSPACE.md resolution.
     *
     * <p>Present results carry the truncated prompt-safe content and an
     * untrusted provenance label. Absent results represent a genuinely
     * missing optional file, not an integrity failure.
     */
    public record WorkspaceMdResult(
            String content,
            String provenanceLabel,
            boolean present,
            boolean truncated) {

        static WorkspaceMdResult present(String content, String label, boolean truncated) {
            return new WorkspaceMdResult(
                    Objects.requireNonNull(content, "content"),
                    Objects.requireNonNull(label, "label"),
                    true, truncated);
        }

        static WorkspaceMdResult absent() {
            return new WorkspaceMdResult(null, null, false, false);
        }

        public boolean hasContent() {
            return present && StringUtils.hasText(content);
        }
    }

    // ── Exception ──

    /**
     * Terminal exception for WORKSPACE.md resolution failures.
     *
     * <p>Any integrity, confinement, UTF-8, link, escape, or I/O failure
     * is terminal. Only an absent optional file resolves to empty.
     */
    public static final class WorkspaceMdException extends RuntimeException {
        public WorkspaceMdException(String message) {
            super(message);
        }

        public WorkspaceMdException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
