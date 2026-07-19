package io.mindspice.magenta2.ai.orchestration.workspaces;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.mindspice.magenta2.ai.chat.plan.PlanFieldType;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * Materializes output values into a run's output directory and persists
 * artifact metadata. Each output value is written or copied into the
 * output directory and a {@link RunOutputArtifact} row is persisted.
 *
 * <p>Materialization rules by type:
 * <ul>
 *   <li>{@code file_path} — copy the source file into the output dir</li>
 *   <li>{@code user_message} — write {@code {outputName}.md}</li>
 *   <li>{@code json} — write {@code {outputName}.json}</li>
 *   <li>{@code string/number} — write {@code {outputName}.txt}</li>
 * </ul>
 */
@Service
public class OutputArtifactService {
    private final OutputArtifactRepository repository;
    private final WorkspaceDirectoryService directoryService;
    private final RootRelativePathService rootRelativePathService;
    private final ObjectMapper objectMapper;
    private final boolean looseArtifactDiscoveryEnabled;

    @Autowired
    public OutputArtifactService(OutputArtifactRepository repository,
                                 WorkspaceDirectoryService directoryService,
                                 RootRelativePathService rootRelativePathService,
                                 ObjectMapper objectMapper) {
        this(repository, directoryService, rootRelativePathService, objectMapper, true);
    }

    public OutputArtifactService(OutputArtifactRepository repository,
                                 WorkspaceDirectoryService directoryService,
                                 ObjectMapper objectMapper) {
        this(repository, directoryService, new RootRelativePathService(directoryService), objectMapper, true);
    }

    public OutputArtifactService(OutputArtifactRepository repository,
                                 WorkspaceDirectoryService directoryService,
                                 ObjectMapper objectMapper,
                                 boolean looseArtifactDiscoveryEnabled) {
        this(repository, directoryService, new RootRelativePathService(directoryService), objectMapper,
            looseArtifactDiscoveryEnabled);
    }

    public OutputArtifactService(OutputArtifactRepository repository,
                                 WorkspaceDirectoryService directoryService,
                                 RootRelativePathService rootRelativePathService,
                                 ObjectMapper objectMapper,
                                 boolean looseArtifactDiscoveryEnabled) {
        this.repository = repository;
        this.directoryService = directoryService;
        this.rootRelativePathService = rootRelativePathService;
        this.objectMapper = objectMapper;
        this.looseArtifactDiscoveryEnabled = looseArtifactDiscoveryEnabled;
    }

    /**
     * Materialize a single output value into the given output directory.
     *
     * @param runId          the plan run id
     * @param planId         the plan definition id
     * @param outputName     the declared output name
     * @param outputType     the declared PlanFieldType for this output
     * @param value          the value to materialize
     * @param outputDir      the output directory path
     * @return the persisted artifact
     */
    public RunOutputArtifact materialize(String runId, String planId,
                                          String outputName, PlanFieldType outputType,
                                          Object value, Path outputDir) throws IOException {
        return materialize(runId, planId, outputName, outputType, value, outputDir, OutputArtifactContext.EMPTY);
    }

    public synchronized RunOutputArtifact materialize(String runId, String planId,
                                                      String outputName, PlanFieldType outputType,
                                                      Object value, Path outputDir,
                                                      OutputArtifactContext context) throws IOException {
        requireId(runId, "runId");
        requireId(planId, "planId");
        requireId(outputName, "outputName");
        if (outputType == null) {
            outputType = PlanFieldType.STRING;
        }
        if (value == null) {
            throw new IllegalArgumentException("Output value is null for output: " + outputName);
        }

        Path realOutputDir = requireConfinedOutputDirectory(outputDir);

        return switch (outputType) {
            case FILE_PATH -> materializeFilePath(runId, planId, outputName, value, realOutputDir, context);
            case USER_MESSAGE -> materializeUserMessage(runId, planId, outputName, value, realOutputDir, context);
            case JSON -> materializeJson(runId, planId, outputName, value, realOutputDir, context);
            case STRING, NUMBER -> materializeText(runId, planId, outputName, value, realOutputDir, context);
        };
    }

    /**
     * Materialize all output values from a completed run. Uses the declared
     * output fields from the plan snapshot to determine types.
     *
     * @return list of persisted artifacts
     */
    public List<RunOutputArtifact> materializeAll(String runId, String planId,
                                                   Map<String, Object> outputValues,
                                                   Map<String, PlanFieldType> outputTypes,
                                                   Path outputDir) throws IOException {
        return materializeAll(runId, planId, outputValues, outputTypes, outputDir, OutputArtifactContext.EMPTY);
    }

    public List<RunOutputArtifact> materializeAll(String runId, String planId,
                                                  Map<String, Object> outputValues,
                                                  Map<String, PlanFieldType> outputTypes,
                                                  Path outputDir,
                                                  OutputArtifactContext context) throws IOException {
        List<RunOutputArtifact> artifacts = new java.util.ArrayList<>();
        for (Map.Entry<String, Object> entry : outputValues.entrySet()) {
            String name = entry.getKey();
            PlanFieldType type = outputTypes.getOrDefault(name, PlanFieldType.STRING);
            artifacts.add(materialize(runId, planId, name, type, entry.getValue(), outputDir, context));
        }
        return artifacts;
    }

    public List<RunOutputArtifact> artifactsForRun(String runId) {
        return repository.findArtifactsByRunId(runId);
    }

    public List<RunOutputArtifact> artifactsForPlan(String planId) {
        return repository.findArtifactsByPlanId(planId);
    }

    public List<RunOutputArtifact> query(String runId, String planId, String artifactType, Integer limit) {
        return query(OutputArtifactQuery.of(
            null, null, null, null, null, null, null, null, runId, planId, null, artifactType, limit
        ));
    }

    public List<RunOutputArtifact> query(OutputArtifactQuery query) {
        return repository.findArtifacts(query == null
            ? OutputArtifactQuery.of(null, null, null, null, null, null, null, null, null, null, null, null, 50)
            : query
        );
    }

    public int backfillAttribution(String runId, OutputArtifactContext context) {
        return repository.backfillArtifactAttribution(runId, context);
    }

    /**
     * Returns the configured data root path for path confinement checks.
     */
    public java.nio.file.Path dataRoot() {
        return directoryService.dataRoot();
    }

    /**
     * Retrieve artifact metadata by ID.
     */
    public RunOutputArtifact getArtifact(String artifactId) {
        return repository.findArtifactById(artifactId)
            .orElseThrow(() -> new IllegalArgumentException("Output artifact not found: " + artifactId));
    }

    /**
     * Load artifact content from the filesystem. Validates path confinement
     * and rejects missing, directory, non-data-root, and too-large files.
     *
     * @param artifactId the artifact id
     * @param maxBytes   maximum file size in bytes (10 MB default)
     * @return the file content as a UTF-8 string
     */
    public String loadContent(String artifactId, long maxBytes) throws IOException {
        RunOutputArtifact artifact = getArtifact(artifactId);
        return Files.readString(resolveArtifactFile(artifact, maxBytes));
    }

    public Path resolveArtifactFile(String artifactId) {
        return resolveArtifactFile(getArtifact(artifactId));
    }

    public Path resolveArtifactFile(String artifactId, long maxBytes) throws IOException {
        return resolveArtifactFile(getArtifact(artifactId), maxBytes);
    }

    public Path resolveArtifactFile(RunOutputArtifact artifact) {
        if (artifact == null) {
            throw new IllegalArgumentException("artifact is required");
        }
        String filePath = artifact.filePath();
        if (!StringUtils.hasText(filePath)) {
            throw new IllegalArgumentException("Artifact has no file path: " + artifact.id());
        }
        Path resolved = rootRelativePathService.resolve(filePath);
        Path path;
        try {
            path = resolved.toRealPath();
        } catch (IOException e) {
            throw new IllegalArgumentException("Artifact file does not exist: " + filePath, e);
        }

        if (!path.startsWith(directoryService.dataRoot())) {
            throw new IllegalArgumentException("Artifact path escapes data root: " + filePath);
        }
        if (Files.isDirectory(path)) {
            throw new IllegalArgumentException("Artifact is a directory, not a file: " + filePath);
        }
        if (!Files.isRegularFile(path)) {
            throw new IllegalArgumentException("Artifact file does not exist: " + filePath);
        }
        return path;
    }

    public Path resolveArtifactFile(RunOutputArtifact artifact, long maxBytes) throws IOException {
        Path path = resolveArtifactFile(artifact);
        long size = Files.size(path);
        if (size > maxBytes) {
            throw new IllegalArgumentException(
                "Artifact file too large: " + size + " bytes (max " + maxBytes + ")");
        }
        return path;
    }

    /**
     * Scan the output directory for files not already registered as artifacts
     * for this run and register them as discovered artifacts.
     * Non-recursive: only scans files directly in the output directory.
     *
     * @return number of newly registered artifacts
     */
    public int discoverLooseArtifacts(String runId, String planId, Path outputDir,
                                       OutputArtifactContext context) throws IOException {
        if (!looseArtifactDiscoveryEnabled || !Files.isDirectory(outputDir)) {
            return 0;
        }
        Path realDataRoot = directoryService.dataRoot().toRealPath();
        Path realOutputDir = outputDir.toRealPath();
        if (!realOutputDir.startsWith(realDataRoot)) {
            throw new IllegalArgumentException("Output directory escapes data root: " + outputDir);
        }
        java.util.Set<String> registered = repository.findArtifactsByRunId(runId).stream()
            .map(RunOutputArtifact::fileName)
            .collect(java.util.stream.Collectors.toSet());

        int count = 0;
        try (var stream = Files.list(outputDir)) {
            for (Path file : stream.filter(Files::isRegularFile).toList()) {
                Path realFile = file.toRealPath();
                if (!realFile.startsWith(realDataRoot) || !realFile.startsWith(realOutputDir)) {
                    continue;
                }
                String fileName = file.getFileName().toString();
                if (registered.contains(fileName)) {
                    continue;
                }
                // Register the file directly as a discovered artifact
                String artifactType = inferArtifactType(fileName);
                saveArtifact(runId, planId, "discovered_" + sanitizeOutputName(fileName),
                    artifactType, fileName, file, null, context);
                count++;
                registered.add(fileName);
            }
        }
        return count;
    }

    /**
     * Explicitly publish an existing file as a run output artifact. The source
     * file must resolve under the configured data root. If it is not already in
     * the run output directory, it is copied there before metadata is persisted.
     */
    public synchronized RunOutputArtifact publishExistingFile(String runId, String planId,
                                                              String outputName, String artifactType,
                                                              Path sourcePath, Path outputDir,
                                                              OutputArtifactContext context) throws IOException {
        requireId(runId, "runId");
        requireId(planId, "planId");
        requireId(outputName, "outputName");
        if (sourcePath == null) {
            throw new IllegalArgumentException("sourcePath is required");
        }
        if (outputDir == null) {
            throw new IllegalArgumentException("outputDir is required");
        }
        Path realDataRoot = directoryService.dataRoot().toRealPath();
        Path realSource = requireConfinedRealSourcePath(sourcePath, realDataRoot, outputName);
        Path realOutputDir = Files.createDirectories(outputDir).toRealPath();
        if (!realOutputDir.startsWith(realDataRoot)) {
            throw new IllegalArgumentException("Output directory escapes data root: " + outputDir);
        }

        String fileName = realSource.getFileName().toString();
        Path destination = realOutputDir.resolve(fileName).normalize();
        if (!destination.startsWith(realOutputDir)) {
            throw new IllegalArgumentException("Published output path escapes output directory: " + sourcePath);
        }
        if (!realSource.startsWith(realOutputDir)) {
            destination = copyToUniqueDestination(realSource, realOutputDir, fileName);
        } else {
            destination = realSource;
        }

        String resolvedType = StringUtils.hasText(artifactType)
            ? artifactType.trim()
            : inferArtifactType(destination.getFileName().toString());
        return saveArtifact(runId, planId, outputName, resolvedType,
            destination.getFileName().toString(), destination, null, context);
    }

    /**
     * Copies confined directory contents into {@code outputDir/copied-temp/}
     * and registers each copied regular file as a run output artifact.
     * Symbolic links are skipped and never followed.
     */
    public synchronized List<RunOutputArtifact> publishDirectoryContents(
        String runId,
        String planId,
        Path sourceDir,
        Path outputDir,
        OutputArtifactContext context
    ) throws IOException {
        requireId(runId, "runId");
        requireId(planId, "planId");
        if (sourceDir == null) {
            throw new IllegalArgumentException("sourceDir is required");
        }
        Path realDataRoot = directoryService.dataRoot().toRealPath();
        Path realSourceRoot = requireConfinedSourceDirectory(sourceDir, realDataRoot);
        Path realOutputDir = requireConfinedOutputDirectory(outputDir);
        Path copiedTempDir = realOutputDir.resolve("copied-temp").normalize();
        if (!copiedTempDir.startsWith(realOutputDir)) {
            throw new IllegalArgumentException("Copied temp directory escapes output directory: " + outputDir);
        }
        if (Files.isSymbolicLink(copiedTempDir)) {
            throw new IllegalArgumentException("Copied temp directory must not be a symlink: " + copiedTempDir);
        }
        Path realCopiedTempDir = Files.createDirectories(copiedTempDir).toRealPath();
        if (!realCopiedTempDir.startsWith(realOutputDir)) {
            throw new IllegalArgumentException("Copied temp directory escapes output directory: " + copiedTempDir);
        }

        List<RunOutputArtifact> artifacts = new ArrayList<>();
        Files.walkFileTree(realSourceRoot, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                if (!realSourceRoot.equals(dir) && Files.isSymbolicLink(dir)) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
                Path realDir = dir.toRealPath();
                if (!realDir.startsWith(realDataRoot) || !realDir.startsWith(realSourceRoot)) {
                    throw new IllegalArgumentException("Source directory escapes source root: " + dir);
                }
                if (!realSourceRoot.equals(realDir) && (realDir.equals(realOutputDir) || realDir.startsWith(realOutputDir))) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                if (Files.isSymbolicLink(file)) {
                    return FileVisitResult.CONTINUE;
                }
                Path realFile = file.toRealPath();
                if (!realFile.startsWith(realDataRoot) || !realFile.startsWith(realSourceRoot)) {
                    throw new IllegalArgumentException("Source file escapes source root: " + file);
                }
                Path relative = realSourceRoot.relativize(file);
                Path destination = realCopiedTempDir.resolve(relative).normalize();
                if (!destination.startsWith(realCopiedTempDir) || !destination.startsWith(realOutputDir)) {
                    throw new IllegalArgumentException("Copied temp destination escapes output directory: " + relative);
                }
                if (Files.isSymbolicLink(destination)) {
                    throw new IllegalArgumentException("Copied temp destination must not be a symlink: " + relative);
                }
                Path parent = Files.createDirectories(destination.getParent()).toRealPath();
                if (!parent.startsWith(realCopiedTempDir) || !parent.startsWith(realOutputDir)) {
                    throw new IllegalArgumentException("Copied temp destination parent escapes output directory: " + relative);
                }
                Files.copy(realFile, destination, StandardCopyOption.REPLACE_EXISTING);
                Path realDestination = destination.toRealPath();
                if (!realDestination.startsWith(realCopiedTempDir) || !realDestination.startsWith(realOutputDir)) {
                    throw new IllegalArgumentException("Copied temp destination escapes output directory: " + relative);
                }
                String relativeName = relativePathWithSlashes(relative);
                String artifactType = inferArtifactType(realDestination.getFileName().toString());
                artifacts.add(saveArtifact(
                    runId,
                    planId,
                    "copied_temp/" + relativeName,
                    artifactType,
                    realDestination.getFileName().toString(),
                    realDestination,
                    null,
                    context
                ));
                return FileVisitResult.CONTINUE;
            }
        });
        return artifacts;
    }

    /**
     * Promotes run-local staged outputs into a backend-owned final output
     * directory and registers the promoted copies as artifacts.
     */
    public synchronized List<RunOutputArtifact> promoteDirectoryContents(
        String runId,
        String planId,
        Path sourceDir,
        Path outputDir,
        OutputArtifactContext context
    ) throws IOException {
        requireId(runId, "runId");
        requireId(planId, "planId");
        if (sourceDir == null) {
            throw new IllegalArgumentException("sourceDir is required");
        }
        Path realDataRoot = directoryService.dataRoot().toRealPath();
        Path realSourceRoot = requireConfinedSourceDirectory(sourceDir, realDataRoot);
        Path realOutputDir = requireConfinedOutputDirectory(outputDir);
        if (realOutputDir.equals(realSourceRoot) || realOutputDir.startsWith(realSourceRoot)) {
            return List.of();
        }

        java.util.Set<String> registeredFilePaths = repository.findArtifactsByRunId(runId).stream()
            .map(RunOutputArtifact::filePath)
            .collect(java.util.stream.Collectors.toSet());
        List<RunOutputArtifact> artifacts = new ArrayList<>();
        Files.walkFileTree(realSourceRoot, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                if (!realSourceRoot.equals(dir) && Files.isSymbolicLink(dir)) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
                Path realDir = dir.toRealPath();
                if (!realDir.startsWith(realDataRoot) || !realDir.startsWith(realSourceRoot)) {
                    throw new IllegalArgumentException("Source directory escapes source root: " + dir);
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                if (Files.isSymbolicLink(file)) {
                    return FileVisitResult.CONTINUE;
                }
                Path realFile = file.toRealPath();
                if (!realFile.startsWith(realDataRoot) || !realFile.startsWith(realSourceRoot)) {
                    throw new IllegalArgumentException("Source file escapes source root: " + file);
                }
                Path relative = realSourceRoot.relativize(file);
                Path destination = realOutputDir.resolve(relative).normalize();
                if (!destination.startsWith(realOutputDir)) {
                    throw new IllegalArgumentException("Promoted destination escapes output directory: " + relative);
                }
                if (Files.isSymbolicLink(destination)) {
                    throw new IllegalArgumentException("Promoted destination must not be a symlink: " + relative);
                }
                Path parent = Files.createDirectories(destination.getParent()).toRealPath();
                if (!parent.startsWith(realOutputDir)) {
                    throw new IllegalArgumentException("Promoted destination parent escapes output directory: " + relative);
                }
                Files.copy(realFile, destination, StandardCopyOption.REPLACE_EXISTING);
                Path realDestination = destination.toRealPath();
                if (!realDestination.startsWith(realOutputDir)) {
                    throw new IllegalArgumentException("Promoted destination escapes output directory: " + relative);
                }
                String storedDestination = rootRelativePathService.store(realDestination);
                if (registeredFilePaths.contains(storedDestination)) {
                    return FileVisitResult.CONTINUE;
                }
                String relativeName = relativePathWithSlashes(relative);
                artifacts.add(saveArtifact(
                    runId,
                    planId,
                    "promoted/" + relativeName,
                    inferArtifactType(realDestination.getFileName().toString()),
                    realDestination.getFileName().toString(),
                    realDestination,
                    null,
                    context
                ));
                registeredFilePaths.add(storedDestination);
                return FileVisitResult.CONTINUE;
            }
        });
        return artifacts;
    }

    private String inferArtifactType(String fileName) {
        String lower = fileName.toLowerCase();
        if (lower.endsWith(".md")) return "user_message";
        if (lower.endsWith(".json")) return "json";
        if (lower.endsWith(".txt")) return "text";
        if (lower.endsWith(".csv")) return "text";
        if (lower.endsWith(".log")) return "text";
        return "file_path";
    }

    private String sanitizeOutputName(String fileName) {
        // Remove extension for the output name
        int dot = fileName.lastIndexOf('.');
        String base = dot > 0 ? fileName.substring(0, dot) : fileName;
        return base.replaceAll("[^a-zA-Z0-9_.-]", "_").replaceAll("_+", "_");
    }

    // ── Type-specific materialization ──

    private RunOutputArtifact materializeFilePath(String runId, String planId,
                                                    String outputName, Object value,
                                                    Path outputDir,
                                                    OutputArtifactContext context) throws IOException {
        String sourcePathStr = value.toString().trim();
        if (!StringUtils.hasText(sourcePathStr)) {
            throw new IllegalArgumentException("Source file path is blank for output: " + outputName);
        }
        Path sourcePath;
        Path realDataRoot = directoryService.dataRoot().toRealPath();

        // Handle bare filenames from model output: resolve relative to output directory
        if (!sourcePathStr.contains("/") && !sourcePathStr.contains("\\")) {
            sourcePath = outputDir.resolve(sourcePathStr).normalize();
        }
        // Handle relative paths from workspace: resolve relative to output directory
        else if (!Path.of(sourcePathStr).isAbsolute()) {
            sourcePath = resolveRunOutputPath(outputDir, sourcePathStr, sourcePathStr);
        } else {
            // Absolute host paths keep the existing data-root lexical boundary,
            // then resolve symlinks below before copying or registering.
            Path absolute = Path.of(sourcePathStr).toAbsolutePath().normalize();
            if (!absolute.startsWith(realDataRoot)) {
                throw new IllegalArgumentException(
                    "Absolute file_path output escapes data root: " + sourcePathStr);
            }
            sourcePath = absolute;
        }

        Path realSourcePath = requireConfinedRealSourcePath(sourcePath, realDataRoot, outputName);

        String fileName = sourcePath.getFileName().toString();
        // Use actual filename; add output name prefix only for collision avoidance
        Path destPath = outputDir.resolve(fileName);
        boolean sourceEqualsDest = sourceEqualsDestination(sourcePath, realSourcePath, destPath);
        if (sourceEqualsDest && artifactFileNameExists(runId, fileName)) {
            destPath = copyToUniqueDestination(realSourcePath, outputDir, sanitize(outputName) + "_" + fileName);
            sourceEqualsDest = false;
        }

        if (!sourceEqualsDest) {
            destPath = copyToUniqueDestination(realSourcePath, outputDir, destPath.getFileName().toString());
        }

        return saveArtifact(runId, planId, outputName, "file_path",
            destPath.getFileName().toString(),
            destPath, null, context);
    }

    private Path requireConfinedRealSourcePath(Path sourcePath, Path realDataRoot, String outputName) throws IOException {
        boolean sourceExistsWithoutFollowingLinks = Files.exists(sourcePath, LinkOption.NOFOLLOW_LINKS);
        if (!sourceExistsWithoutFollowingLinks && !Files.exists(sourcePath)) {
            throw new IllegalArgumentException(
                "Source file for output '" + outputName + "' does not exist: " + sourcePath);
        }
        Path realSourcePath;
        try {
            realSourcePath = sourcePath.toRealPath();
        } catch (NoSuchFileException e) {
            if (sourceExistsWithoutFollowingLinks) {
                throw new IllegalArgumentException(
                    "Source file for output '" + outputName + "' is a broken symlink: " + sourcePath, e);
            }
            throw new IllegalArgumentException(
                "Source file for output '" + outputName + "' does not exist: " + sourcePath, e);
        }
        if (!realSourcePath.startsWith(realDataRoot)) {
            throw new IllegalArgumentException(
                "Source file for output '" + outputName + "' escapes data root: " + sourcePath);
        }
        return realSourcePath;
    }

    private boolean sourceEqualsDestination(Path sourcePath, Path realSourcePath, Path destPath) throws IOException {
        if (sourcePath.toAbsolutePath().normalize().equals(destPath.toAbsolutePath().normalize())) {
            return true;
        }
        if (!Files.exists(destPath, LinkOption.NOFOLLOW_LINKS)) {
            return false;
        }
        try {
            return realSourcePath.equals(destPath.toRealPath());
        } catch (NoSuchFileException e) {
            return false;
        }
    }

    private Path resolveRunOutputPath(Path outputDir, String relativePath, String displayPath) {
        Path normalizedOutputDir = outputDir.toAbsolutePath().normalize();
        if (!StringUtils.hasText(relativePath)) {
            return normalizedOutputDir;
        }
        String cleaned = relativePath.replace('\\', '/');
        if (cleaned.startsWith("/") || cleaned.contains("//")) {
            throw new IllegalArgumentException("Output path escapes output directory: " + displayPath);
        }
        Path relative = Path.of(cleaned).normalize();
        if (relative.isAbsolute() || relative.startsWith("..")) {
            throw new IllegalArgumentException("Output path escapes output directory: " + displayPath);
        }
        if (outputDir.getFileName() != null && relative.getNameCount() > 1
            && outputDir.getFileName().toString().equals(relative.getName(0).toString())) {
            relative = relative.subpath(1, relative.getNameCount());
        }
        Path resolved = normalizedOutputDir.resolve(relative).normalize();
        if (!resolved.startsWith(normalizedOutputDir)) {
            throw new IllegalArgumentException("Output path escapes output directory: " + displayPath);
        }
        return resolved;
    }

    private RunOutputArtifact materializeUserMessage(String runId, String planId,
                                                       String outputName, Object value,
                                                       Path outputDir,
                                                       OutputArtifactContext context) throws IOException {
        String fileName = sanitize(outputName) + ".md";
        String content = value.toString();
        Path filePath = writeStringToUniqueFile(outputDir, fileName, content);

        return saveArtifact(runId, planId, outputName, "user_message",
            filePath.getFileName().toString(), filePath, null, context);
    }

    private RunOutputArtifact materializeJson(String runId, String planId,
                                                String outputName, Object value,
                                                Path outputDir,
                                                OutputArtifactContext context) throws IOException {
        String fileName = sanitize(outputName) + ".json";
        String jsonContent;
        try {
            jsonContent = value instanceof String
                ? objectMapper.writeValueAsString(
                    objectMapper.readTree((String) value))
                : objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException(
                "Output '" + outputName + "' is not valid JSON: " + e.getMessage(), e);
        }
        Path filePath = writeStringToUniqueFile(outputDir, fileName, jsonContent);

        return saveArtifact(runId, planId, outputName, "json",
            filePath.getFileName().toString(), filePath, jsonContent, context);
    }

    private RunOutputArtifact materializeText(String runId, String planId,
                                                String outputName, Object value,
                                                Path outputDir,
                                                OutputArtifactContext context) throws IOException {
        String fileName = sanitize(outputName) + ".txt";
        String content = value.toString();
        Path filePath = writeStringToUniqueFile(outputDir, fileName, content);

        return saveArtifact(runId, planId, outputName, "text",
            filePath.getFileName().toString(), filePath, null, context);
    }

    // ── Helpers ──

    private RunOutputArtifact saveArtifact(String runId, String planId,
                                            String outputName, String artifactType,
                                            String fileName, Path filePath,
                                            String contentJson,
                                            OutputArtifactContext context) {
        OutputArtifactContext resolvedContext = context == null ? OutputArtifactContext.EMPTY : context;
        String storedFilePath = rootRelativePathService.store(filePath);
        return repository.saveArtifact(new RunOutputArtifact(
            UUID.randomUUID().toString(),
            runId,
            planId,
            resolvedContext.agentId(),
            resolvedContext.jobId(),
            resolvedContext.jobAssignmentId(),
            resolvedContext.jobRunId(),
            resolvedContext.projectId(),
            resolvedContext.workspaceType(),
            resolvedContext.workspaceKey(),
            resolvedContext.workspaceIdentity(),
            resolvedContext.runType(),
            outputName,
            artifactType,
            fileName,
            storedFilePath,
            contentJson,
            Instant.now()
        ));
    }

    private Path requireConfinedOutputDirectory(Path outputDir) throws IOException {
        if (outputDir == null) {
            throw new IllegalArgumentException("outputDir is required");
        }
        Path realDataRoot = directoryService.dataRoot().toRealPath();
        Path realOutputDir = Files.createDirectories(outputDir).toRealPath();
        if (!realOutputDir.startsWith(realDataRoot)) {
            throw new IllegalArgumentException("Output directory escapes data root: " + outputDir);
        }
        return realOutputDir;
    }

    private Path requireConfinedSourceDirectory(Path sourceDir, Path realDataRoot) throws IOException {
        Path normalized = sourceDir.isAbsolute()
            ? sourceDir.toAbsolutePath().normalize()
            : realDataRoot.resolve(sourceDir).normalize();
        if (!normalized.startsWith(realDataRoot)) {
            throw new IllegalArgumentException("Source directory escapes data root: " + sourceDir);
        }
        if (Files.isSymbolicLink(normalized)) {
            throw new IllegalArgumentException("Source directory must not be a symlink: " + sourceDir);
        }
        Path realSourceRoot = normalized.toRealPath();
        if (!realSourceRoot.startsWith(realDataRoot)) {
            throw new IllegalArgumentException("Source directory escapes data root: " + sourceDir);
        }
        if (!Files.isDirectory(realSourceRoot, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalArgumentException("Source path is not a directory: " + sourceDir);
        }
        return realSourceRoot;
    }

    private void requireId(String value, String label) {
        if (!StringUtils.hasText(value)) {
            throw new IllegalArgumentException(label + " is required");
        }
    }

    private String sanitize(String value) {
        return value.replaceAll("[^a-zA-Z0-9_.-]", "_").replaceAll("_+", "_");
    }

    private boolean artifactFileNameExists(String runId, String fileName) {
        return repository.findArtifactsByRunId(runId).stream()
            .map(RunOutputArtifact::fileName)
            .anyMatch(fileName::equals);
    }

    private Path writeStringToUniqueFile(Path outputDir, String desiredFileName, String content) throws IOException {
        IOException lastError = null;
        Path normalizedOutputDir = outputDir.toAbsolutePath().normalize();
        for (int attempt = 0; attempt < 1_000; attempt++) {
            Path candidate = outputDir.resolve(uniqueFileName(desiredFileName, attempt)).normalize();
            if (!candidate.toAbsolutePath().normalize().startsWith(normalizedOutputDir)) {
                throw new IllegalArgumentException("Output path escapes output directory: " + desiredFileName);
            }
            try {
                return Files.writeString(candidate, content, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            } catch (FileAlreadyExistsException e) {
                lastError = e;
            }
        }
        throw new IOException("Could not allocate unique output file for " + desiredFileName, lastError);
    }

    private Path copyToUniqueDestination(Path source, Path outputDir, String desiredFileName) throws IOException {
        IOException lastError = null;
        Path normalizedOutputDir = outputDir.toAbsolutePath().normalize();
        for (int attempt = 0; attempt < 1_000; attempt++) {
            Path candidate = outputDir.resolve(uniqueFileName(desiredFileName, attempt)).normalize();
            if (!candidate.toAbsolutePath().normalize().startsWith(normalizedOutputDir)) {
                throw new IllegalArgumentException("Output path escapes output directory: " + desiredFileName);
            }
            try {
                Files.copy(source, candidate);
                return candidate;
            } catch (FileAlreadyExistsException e) {
                lastError = e;
            }
        }
        throw new IOException("Could not allocate unique output file for " + desiredFileName, lastError);
    }

    private String uniqueFileName(String fileName, int attempt) {
        if (attempt == 0) {
            return fileName;
        }
        int dot = fileName.lastIndexOf('.');
        String base = dot > 0 ? fileName.substring(0, dot) : fileName;
        String ext = dot > 0 ? fileName.substring(dot) : "";
        return base + "-" + (attempt + 1) + ext;
    }

    private String relativePathWithSlashes(Path path) {
        StringBuilder builder = new StringBuilder();
        for (Path segment : path) {
            if (!builder.isEmpty()) {
                builder.append('/');
            }
            builder.append(segment);
        }
        return builder.toString();
    }
}
