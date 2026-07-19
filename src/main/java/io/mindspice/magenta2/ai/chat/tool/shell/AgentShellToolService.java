package io.mindspice.magenta2.ai.chat.tool.shell;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import io.mindspice.magenta2.ai.config.user.AgentConfig;
import io.mindspice.magenta2.ai.config.user.AiConfig;
import io.mindspice.magenta2.ai.orchestration.runtime.OrchestrationTaskContext;
import io.mindspice.magenta2.ai.orchestration.runtime.OrchestrationTaskContextHolder;
import io.mindspice.magenta2.ai.orchestration.settings.RuntimeSettingsService;
import io.mindspice.magenta2.ai.orchestration.workspaces.WorkspaceDirectoryService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class AgentShellToolService {
    private static final String WILDCARD = "*";
    private static final int DEFAULT_TIMEOUT_SECONDS = 10;
    private static final int MAX_TIMEOUT_SECONDS = 30;
    private static final int OUTPUT_LIMIT_BYTES = 16_384;
    private static final Set<String> SHELL_WRAPPERS = Set.of(
        "sh", "bash", "dash", "zsh", "ksh", "fish", "csh", "tcsh",
        "env", "sudo", "su", "doas", "xargs", "parallel", "busybox"
    );
    private static final Set<String> SHELL_CONTROL_TOKENS = Set.of(
        "|", "||", "&", "&&", ";", ">", ">>", "<", "<<", "<<<", "2>", "2>>"
    );
    private static final Set<String> RETIRED_OR_RESERVED_PREFIXES = Set.of(
        "workspace", "root", "run", "work", "projects", "scratch", "jobs", "job", "outputs"
    );

    private final Path root;
    private final Set<String> allowedCommands;
    private final boolean allowAllCommands;
    private final WorkspaceDirectoryService workspaceDirectoryService;

    @Autowired
    public AgentShellToolService(AiConfig aiConfig,
                                  @Autowired(required = false) RuntimeSettingsService runtimeSettingsService,
                                  @Autowired(required = false) WorkspaceDirectoryService workspaceDirectoryService) throws IOException {
        if (aiConfig == null || aiConfig.dataRoot() == null) {
            throw new IllegalArgumentException("AI config dataRoot is required for shell tools");
        }
        if (!Files.isDirectory(aiConfig.dataRoot())) {
            throw new IllegalArgumentException("AI config dataRoot must be an existing directory: " + aiConfig.dataRoot());
        }
        AgentConfig defaultAgent = aiConfig.agents() == null ? null : aiConfig.agents().get(aiConfig.defaultAgent());
        List<String> commands = runtimeSettingsService == null
            ? (defaultAgent == null ? List.of() : defaultAgent.allowedShellCommands())
            : runtimeSettingsService.allowedShellCommands();
        this.root = aiConfig.dataRoot().toRealPath();
        this.allowAllCommands = aiConfig.unsafeAllowWildcardShellCommandsEnabled()
            && commands != null
            && commands.contains(WILDCARD);
        this.allowedCommands = normalizeAllowedCommands(commands);
        this.workspaceDirectoryService = workspaceDirectoryService;
    }

    public AgentShellToolService(AiConfig aiConfig) throws IOException {
        this(aiConfig, null, null);
    }

    AgentShellToolService(Path root, List<String> allowedCommands) throws IOException {
        this(root, allowedCommands, false);
    }

    AgentShellToolService(Path root, List<String> allowedCommands, boolean unsafeAllowWildcardShellCommands)
        throws IOException {
        this.root = root.toRealPath();
        List<String> commands = allowedCommands == null ? List.of() : allowedCommands;
        this.allowAllCommands = unsafeAllowWildcardShellCommands && commands.contains(WILDCARD);
        this.allowedCommands = normalizeAllowedCommands(commands);
        this.workspaceDirectoryService = null;
    }

    AgentShellToolService(Path root, List<String> allowedCommands,
                          WorkspaceDirectoryService workspaceDirectoryService) throws IOException {
        this(root, allowedCommands, workspaceDirectoryService, false);
    }

    AgentShellToolService(Path root, List<String> allowedCommands,
                          WorkspaceDirectoryService workspaceDirectoryService,
                          boolean unsafeAllowWildcardShellCommands) throws IOException {
        this.root = root.toRealPath();
        List<String> commands = allowedCommands == null ? List.of() : allowedCommands;
        this.allowAllCommands = unsafeAllowWildcardShellCommands && commands.contains(WILDCARD);
        this.allowedCommands = normalizeAllowedCommands(commands);
        this.workspaceDirectoryService = workspaceDirectoryService;
    }

    public ShellExecResult exec(String command, String workingDirectory, Integer timeoutSeconds)
        throws IOException, InterruptedException {
        List<String> commandLine = parseCommandLine(command);
        String executable = validateExecutable(commandLine.getFirst());
        validateCommandLineSafety(commandLine);
        if (!allowAllCommands && !allowedCommands.contains(executable)) {
            throw new IllegalArgumentException("shell command is not allowed: " + executable);
        }

        OrchestrationTaskContext taskContext = OrchestrationTaskContextHolder.current();
        ResolvedWorkingDirectory resolved;

        if (taskContext != null && taskContext.hasContext()) {
            resolved = resolveContextWorkingDirectory(taskContext, workingDirectory);
            OrchestrationTaskContextHolder.recordActiveRuntimePath(resolved.displayPath());
        } else {
            Path workingDir = resolveWorkingDirectory(workingDirectory);
            resolved = new ResolvedWorkingDirectory(workingDir, displayPath(workingDir));
        }

        return execOnHost(command, executable, commandLine, resolved.path(), resolved.displayPath(), timeoutSeconds);
    }

    /** Resolves one direct workspace-relative shell target. Retired aliases and outputs are rejected. */
    private ResolvedWorkingDirectory resolveContextWorkingDirectory(OrchestrationTaskContext ctx, String workingDirectory)
        throws IOException {
        Path workspaceRoot;
        if (StringUtils.hasText(ctx.hostDurableWorkspacePath())) {
            workspaceRoot = activeScopeRoot(ctx.hostDurableWorkspacePath(), "active workspace");
        } else if (ctx.hasAgentContext()) {
            if (workspaceDirectoryService == null) {
                throw new IllegalStateException("Workspace directory service is not available");
            }
            workspaceRoot = workspaceDirectoryService.resolveForWrite(
                io.mindspice.magenta2.ai.orchestration.workspaces.WorkspaceKind.AGENT,
                ctx.agentId()
            ).root().toRealPath();
        } else {
            throw new IllegalStateException("Shell execution requires an active workspace");
        }

        String requested = StringUtils.hasText(workingDirectory) ? workingDirectory.trim() : ".";
        String normalized = normalizeWorkspaceRequest(requested);
        String relative = ".".equals(normalized) ? "" : normalized;
        String firstSegment = relative.contains("/") ? relative.substring(0, relative.indexOf('/')) : relative;
        if (RETIRED_OR_RESERVED_PREFIXES.contains(firstSegment)) {
            throw new IllegalArgumentException("retired or reserved workspace path prefix is not allowed: " + workingDirectory);
        }
        rejectUnsafeRelativePath(relative, "Working directory escapes active workspace: " + workingDirectory);
        Path resolved = resolveScopedDirectory(workspaceRoot, relative, workingDirectory);
        return new ResolvedWorkingDirectory(resolved, directDisplayPath(workspaceRoot, resolved));
    }

    /**
     * Execute a command on the host filesystem using ProcessBuilder/Bash.
     */
    private ShellExecResult execOnHost(String command, String executable,
                                        List<String> commandLine,
                                        Path workingDir, String displayPath,
                                        Integer timeoutSeconds)
        throws IOException, InterruptedException {
        int timeout = clamp(timeoutSeconds, DEFAULT_TIMEOUT_SECONDS, 1, MAX_TIMEOUT_SECONDS);

        Process process = new ProcessBuilder(commandLine)
            .directory(workingDir.toFile())
            .start();
        CompletableFuture<CapturedOutput> stdoutCapture = CompletableFuture.supplyAsync(() -> capture(process.getInputStream()));
        CompletableFuture<CapturedOutput> stderrCapture = CompletableFuture.supplyAsync(() -> capture(process.getErrorStream()));

        boolean interrupted = false;
        boolean completed = false;
        CapturedOutput out;
        CapturedOutput err;
        try {
            try {
                completed = process.waitFor(timeout, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                interrupted = true;
            }
            if (!completed) {
                process.destroyForcibly();
                try {
                    process.waitFor(1, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    interrupted = true;
                }
            }
        } finally {
            out = drainCaptureFuture(stdoutCapture);
            err = drainCaptureFuture(stderrCapture);
            if (process.isAlive()) {
                process.destroyForcibly();
            }
        }

        if (interrupted) {
            Thread.currentThread().interrupt();
            throw new InterruptedException("Shell execution interrupted");
        }

        return new ShellExecResult(
            executable,
            command,
            List.copyOf(commandLine.subList(1, commandLine.size())),
            displayPath,
            completed ? process.exitValue() : null,
            out.text(),
            err.text(),
            !completed,
            out.truncated() || err.truncated(),
            "bash"
        );
    }

    /**
     * Drains a capture future with a bounded timeout, cancelling the future if it
     * does not complete within the deadline. Returns a fallback {@link CapturedOutput}
     * on all failure paths so callers never block indefinitely.
     */
    private static CapturedOutput drainCaptureFuture(CompletableFuture<CapturedOutput> future) {
        try {
            return future.get(1, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            return new CapturedOutput("[capture timed out]", true);
        } catch (ExecutionException | CancellationException e) {
            return new CapturedOutput("[capture failed: " + e.getMessage() + "]", true);
        } catch (InterruptedException e) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            return new CapturedOutput("[capture interrupted]", true);
        }
    }

    private CapturedOutput capture(InputStream inputStream) {
        byte[] buffer = new byte[4096];
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        boolean truncated = false;
        try (InputStream in = inputStream) {
            int read;
            while ((read = in.read(buffer)) != -1) {
                int remaining = OUTPUT_LIMIT_BYTES - output.size();
                if (remaining > 0) {
                    output.write(buffer, 0, Math.min(read, remaining));
                }
                if (read > remaining) {
                    truncated = true;
                }
            }
        } catch (IOException exception) {
            String message = "[failed to capture process output: " + exception.getMessage() + "]";
            return new CapturedOutput(message, true);
        }
        return new CapturedOutput(output.toString(StandardCharsets.UTF_8), truncated);
    }

    private List<String> parseCommandLine(String command) {
        if (!StringUtils.hasText(command)) {
            throw new IllegalArgumentException("command is required");
        }
        List<String> tokens = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        Character quote = null;
        boolean escaped = false;
        for (int i = 0; i < command.length(); i++) {
            char ch = command.charAt(i);
            if (escaped) {
                current.append(ch);
                escaped = false;
                continue;
            }
            if (ch == '\\') {
                escaped = true;
                continue;
            }
            if (quote != null) {
                if (ch == quote) {
                    quote = null;
                } else {
                    current.append(ch);
                }
                continue;
            }
            if (ch == '\'' || ch == '"') {
                quote = ch;
                continue;
            }
            if (Character.isWhitespace(ch)) {
                if (!current.isEmpty()) {
                    tokens.add(current.toString());
                    current.setLength(0);
                }
                continue;
            }
            current.append(ch);
        }
        if (escaped) {
            current.append('\\');
        }
        if (quote != null) {
            throw new IllegalArgumentException("command has an unterminated quote");
        }
        if (!current.isEmpty()) {
            tokens.add(current.toString());
        }
        if (tokens.isEmpty()) {
            throw new IllegalArgumentException("command is required");
        }
        return List.copyOf(tokens);
    }

    private String validateExecutable(String executable) {
        if (executable.contains("/") || executable.contains("\\") || executable.chars().anyMatch(Character::isWhitespace)) {
            throw new IllegalArgumentException("command must be a bare executable name");
        }
        if (SHELL_WRAPPERS.contains(executable)) {
            throw new IllegalArgumentException("shell wrapper executables are not allowed: " + executable);
        }
        return executable;
    }

    private static Set<String> normalizeAllowedCommands(List<String> commands) {
        if (commands == null) {
            return Set.of();
        }
        return commands.stream()
            .filter(StringUtils::hasText)
            .filter(command -> !WILDCARD.equals(command))
            .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    private void validateCommandLineSafety(List<String> commandLine) {
        for (int i = 1; i < commandLine.size(); i++) {
            String token = commandLine.get(i);
            if (SHELL_CONTROL_TOKENS.contains(token) || token.contains("$(") || token.contains("`")) {
                throw new IllegalArgumentException("shell control syntax is not allowed");
            }
            if (containsPathTraversal(token)) {
                throw new IllegalArgumentException("shell arguments may not contain parent-directory traversal");
            }
            if (containsAbsolutePathPattern(token)) {
                throw new IllegalArgumentException("shell arguments may not reference absolute filesystem paths");
            }
        }
    }

    private boolean containsPathTraversal(String token) {
        String normalized = token.replace('\\', '/');
        return normalized.equals("..")
            || normalized.startsWith("../")
            || normalized.endsWith("/..")
            || normalized.contains("/../");
    }

    private boolean containsAbsolutePathPattern(String token) {
        if (!StringUtils.hasText(token)) {
            return false;
        }
        if (token.matches("^[a-zA-Z][a-zA-Z0-9+.-]*://.*")) {
            return false;
        }
        if (token.startsWith("/") || token.startsWith("\\") || token.startsWith("~")) {
            return true;
        }
        if (token.matches("^[A-Za-z]:[\\\\/].*")) {
            return true;
        }
        return token.matches(".*(^|[^A-Za-z0-9._-])/(?!/).*");
    }

    private String normalizeWorkspaceRequest(String requested) {
        String normalized = requested.replace('\\', '/');
        if (normalized.startsWith("/")) {
            throw new IllegalArgumentException("Absolute working directory not allowed in agent context: " + requested);
        }
        if (normalized.contains("//")) {
            throw new IllegalArgumentException("Working directory escapes agent workspace: " + requested);
        }
        return normalized;
    }

    private void rejectUnsafeRelativePath(String normalized, String message) {
        if (containsPathTraversal(normalized)) {
            throw new IllegalArgumentException(message);
        }
    }

    private Path activeScopeRoot(String path, String label) throws IOException {
        if (!StringUtils.hasText(path)) {
            throw new IllegalStateException("Shell execution requires an " + label);
        }
        Path real = Path.of(path).toRealPath();
        if (!real.startsWith(root)) {
            throw new IllegalArgumentException(label + " escapes data root");
        }
        if (!Files.isDirectory(real, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalArgumentException(label + " is not a directory");
        }
        return real;
    }

    private Path resolveScopedDirectory(Path scopeRoot, String relativePath, String originalRequest) throws IOException {
        String path = relativePath == null ? "" : relativePath;
        Path resolved = path.isEmpty() ? scopeRoot : scopeRoot.resolve(path).normalize();
        if (!resolved.startsWith(scopeRoot)) {
            throw new IllegalArgumentException("Working directory escapes active workspace: " + originalRequest);
        }
        Path real = resolved.toRealPath();
        if (!real.startsWith(scopeRoot)) {
            throw new IllegalArgumentException("Working directory escapes active workspace: " + originalRequest);
        }
        if (!Files.isDirectory(real, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalArgumentException("Working directory is not a directory: " + originalRequest);
        }
        return real;
    }

    private Path resolveWorkingDirectory(String workingDirectory) throws IOException {
        String requested = StringUtils.hasText(workingDirectory) ? workingDirectory : ".";
        Path input = Path.of(requested);
        Path resolved = input.isAbsolute() ? input.normalize() : root.resolve(input).normalize();
        if (!resolved.startsWith(root)) {
            throw new IllegalArgumentException("workingDirectory escapes data root");
        }
        Path real = resolved.toRealPath();
        if (!real.startsWith(root)) {
            throw new IllegalArgumentException("workingDirectory escapes data root");
        }
        if (!Files.isDirectory(real, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalArgumentException("workingDirectory is not a directory: " + displayPath(resolved));
        }
        return real;
    }

    private int clamp(Integer value, int defaultValue, int min, int max) {
        int actual = value == null ? defaultValue : value;
        return Math.min(max, Math.max(min, actual));
    }

    private String displayPath(Path path) {
        Path relative = root.relativize(path.toAbsolutePath().normalize());
        return relative.toString().isEmpty() ? "." : relative.toString();
    }

    private String directDisplayPath(Path workspaceRoot, Path path) {
        String relative = workspaceRoot.relativize(path.toAbsolutePath().normalize()).toString().replace('\\', '/');
        return relative.isEmpty() ? "." : relative;
    }

    private record CapturedOutput(String text, boolean truncated) {
    }

    private record ResolvedWorkingDirectory(Path path, String displayPath) {
    }

    public record ShellExecResult(
        String command,
        String commandLine,
        List<String> args,
        String workingDirectory,
        Integer exitCode,
        String stdout,
        String stderr,
        boolean timedOut,
        boolean truncated,
        String executionType
    ) {
        public ShellExecResult(String command, String commandLine, List<String> args,
                               String workingDirectory, Integer exitCode, String stdout,
                               String stderr, boolean timedOut, boolean truncated) {
            this(command, commandLine, args, workingDirectory, exitCode, stdout, stderr,
                timedOut, truncated, "bash");
        }
    }
}
