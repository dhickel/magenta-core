package io.mindspice.magenta2.ai.orchestration.workspaces;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.mindspice.magenta2.ai.orchestration.runtime.TaskRunLifecycleService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OutputArtifactPublicationServiceTest {

    @TempDir
    Path tempDir;

    JdbcTemplate jdbc;
    OutputArtifactRepository repository;
    WorkspaceDirectoryService directoryService;
    RootRelativePathService rootRelativePathService;
    ConfinedPathMutationService mutations;
    OutputArtifactService service;

    Path dataRoot;
    Path agentRoot;
    Path chatRoot;

    @BeforeEach
    void setUp() throws Exception {
        SingleConnectionDataSource dataSource = new SingleConnectionDataSource(
                "jdbc:sqlite::memory:?foreign_keys=true", true);
        ScriptUtils.executeSqlScript(dataSource.getConnection(), new ClassPathResource("schema.sql"));
        jdbc = new JdbcTemplate(dataSource);
        repository = new OutputArtifactRepository(jdbc);
        dataRoot = Files.createDirectories(tempDir.resolve("root-data"));
        directoryService = WorkspaceDirectoryServiceTestSupport.directoryService(dataRoot);
        rootRelativePathService = new RootRelativePathService(directoryService);
        mutations = new ConfinedPathMutationService();
        service = new OutputArtifactService(repository, directoryService,
                rootRelativePathService, mutations, new ObjectMapper().findAndRegisterModules());

        // Create chat root so chat publication works
        WorkspaceDescriptor chatDesc = directoryService.resolveForWrite(WorkspaceKind.CHAT, "conv-1");
        directoryService.outputs(chatDesc);
    }

    @Test
    void chatPublicationPublishesBytesAndReturnsRecord() throws Exception {
        byte[] content = "hello chat".getBytes(StandardCharsets.UTF_8);
        PublicationRecord record = service.publishChatBytes(
                "conv-1", "chat-op-1", "greeting.txt", "text/plain", content);

        assertThat(record.publicationOrigin()).isEqualTo(PublicationOrigin.CHAT);
        assertThat(record.publicationState()).isEqualTo(PublicationState.PUBLISHED);
        assertThat(record.isReadable()).isTrue();
        assertThat(record.workspaceKind()).isEqualTo("chat");
        assertThat(record.workspaceKey()).isEqualTo("conv-1");

        // Verify bytes are readable
        byte[] read = service.readPublishedBytes(record.artifactId());
        assertThat(read).isEqualTo(content);
    }

    @Test
    void chatPublicationIdempotentSameKeySameContentReturnsExisting() throws Exception {
        byte[] content = "hello".getBytes(StandardCharsets.UTF_8);
        PublicationRecord first = service.publishChatBytes(
                "conv-1", "chat-op-2", "hello.txt", "text/plain", content);

        PublicationRecord second = service.publishChatBytes(
                "conv-1", "chat-op-2", "hello.txt", "text/plain", content);

        assertThat(second.artifactId()).isEqualTo(first.artifactId());
        assertThat(second.publicationState()).isEqualTo(PublicationState.PUBLISHED);
    }

    @Test
    void chatPublicationSameKeyDifferentContentConflicts() throws Exception {
        service.publishChatBytes("conv-1", "chat-op-3", "a.txt", "text/plain",
                "content-a".getBytes(StandardCharsets.UTF_8));

        assertThatThrownBy(() -> service.publishChatBytes(
                "conv-1", "chat-op-3", "b.txt", "text/plain",
                "content-b".getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(TaskRunLifecycleService.OperationConflictException.class);
    }

    @Test
    void publishedBytesAreImmutable() throws Exception {
        byte[] content = "immutable".getBytes(StandardCharsets.UTF_8);
        PublicationRecord record = service.publishChatBytes(
                "conv-1", "chat-op-4", "immutable.txt", "text/plain", content);

        // Read back
        byte[] read = service.readPublishedBytes(record.artifactId());
        assertThat(read).isEqualTo(content);

        // Verify file exists
        Path finalPath = rootRelativePathService.resolve(record.rootRelativePath());
        assertThat(Files.exists(finalPath, LinkOption.NOFOLLOW_LINKS)).isTrue();
    }

    @Test
    void integrityFailedSuppressesByteAccess() throws Exception {
        byte[] content = "integ".getBytes(StandardCharsets.UTF_8);
        PublicationRecord record = service.publishChatBytes(
                "conv-1", "chat-op-5", "integ.txt", "text/plain", content);

        // Verify integrity passes
        assertThat(service.verifyIntegrity(record.artifactId())).isTrue();

        // Corrupt the file
        Path finalPath = rootRelativePathService.resolve(record.rootRelativePath());
        Files.writeString(finalPath, "corrupted");

        // Verify integrity fails
        assertThat(service.verifyIntegrity(record.artifactId())).isFalse();

        // Byte access is suppressed
        PublicationRecord failed = repository.findById(record.artifactId()).orElseThrow();
        assertThat(failed.publicationState()).isEqualTo(PublicationState.INTEGRITY_FAILED);
        assertThat(failed.isReadable()).isFalse();

        assertThatThrownBy(() -> service.readPublishedBytes(record.artifactId()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void publishedReadRechecksIntegrityWithoutSeparateVerification() throws Exception {
        PublicationRecord record = service.publishChatBytes(
                "conv-1", "chat-op-current-integrity", "current.txt", "text/plain",
                "original".getBytes(StandardCharsets.UTF_8));
        Files.writeString(rootRelativePathService.resolve(record.rootRelativePath()), "changed");

        assertThatThrownBy(() -> service.readPublishedBytes(record.artifactId()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(repository.findById(record.artifactId()).orElseThrow().publicationState())
                .isEqualTo(PublicationState.INTEGRITY_FAILED);
    }

    @Test
    void taskPublishedTransitionRequiresMatchingOwnerAndFence() {
        String digest = "a".repeat(64);
        PublicationRecord staged = PublicationRecord.builder()
                .artifactId("task-artifact")
                .publicationOrigin(PublicationOrigin.TASK_RUN)
                .workspaceKind("agent").workspaceKey("agent-1").workspaceIdentity("agent/agent-1")
                .taskRunId("run-1").taskPathKey("v1-task-" + "b".repeat(64)).attemptNumber(1)
                .rootRelativePath("workspace/agent/agent-1/tasks/key/run-1/outputs/out.txt")
                .stagingPath("workspace/agent/agent-1/tasks/key/run-1/outputs/.staging-task-artifact")
                .operationKey("task-op").requestDigest(digest).contentSha256(digest)
                .publicationState(PublicationState.STAGED).expectedSha256(digest)
                .writerOwnerId("owner-1").fenceGeneration(7L).logicalName("out.txt").build();
        repository.insertReservation(staged);

        assertThat(repository.compareAndSetState("task-artifact", "STAGED", "PUBLISHED",
                null, null, null, digest, 1L, "text/plain", "RECONCILED")).isFalse();
        assertThat(repository.compareAndSetState("task-artifact", "STAGED", "PUBLISHED",
                "owner-1", 6L, null, digest, 1L, "text/plain", "RECONCILED")).isFalse();
        assertThat(repository.compareAndSetState("task-artifact", "STAGED", "PUBLISHED",
                "owner-1", 7L, null, digest, 1L, "text/plain", "RECONCILED")).isTrue();
    }

    @Test
    void publicationRecordPersistsIntegrityFacts() throws Exception {
        byte[] content = "facts".getBytes(StandardCharsets.UTF_8);
        String expectedSha = sha256Hex(content);
        PublicationRecord record = service.publishChatBytes(
                "conv-1", "chat-op-6", "facts.txt", "text/plain", content);

        assertThat(record.expectedSha256()).isEqualTo(expectedSha);
        assertThat(record.verifiedSha256()).isEqualTo(expectedSha);
        assertThat(record.verifiedSize()).isEqualTo(5L);
        assertThat(record.mediaType()).isEqualTo("text/plain");
        assertThat(record.logicalName()).isEqualTo("facts.txt");
        assertThat(record.fileExtension()).isEqualTo(".txt");
        assertThat(record.stagingPath()).isNotBlank();
    }

    @Test
    void queryFindsPublishedByWorkspace() throws Exception {
        service.publishChatBytes("conv-1", "chat-op-7", "a.txt", "text/plain",
                "a".getBytes(StandardCharsets.UTF_8));
        service.publishChatBytes("conv-1", "chat-op-8", "b.txt", "text/plain",
                "b".getBytes(StandardCharsets.UTF_8));

        List<PublicationRecord> published = service.findPublishedByWorkspace("chat", "conv-1");
        assertThat(published).hasSize(2);
        assertThat(published).allMatch(r -> r.publicationState() == PublicationState.PUBLISHED);
    }

    @Test
    void reconciliationDetectsMissingBytes() throws Exception {
        byte[] content = "reconcile".getBytes(StandardCharsets.UTF_8);
        PublicationRecord record = service.publishChatBytes(
                "conv-1", "chat-op-9", "reconcile.txt", "text/plain", content);

        // Delete the file
        Path finalPath = rootRelativePathService.resolve(record.rootRelativePath());
        Files.delete(finalPath);

        // Build reconciliation service
        ArtifactReconciliationService reconciliation = new ArtifactReconciliationService(
                repository, rootRelativePathService, directoryService, mutations);
        reconciliation.reconcileOne(record);

        PublicationRecord updated = repository.findById(record.artifactId()).orElseThrow();
        assertThat(updated.publicationState()).isEqualTo(PublicationState.INTEGRITY_FAILED);
        assertThat(updated.reconciliationState()).isEqualTo("UNRESOLVED");
    }

    @Test
    void reconciliationRecoversFromStaging() throws Exception {
        // Directly insert a STAGED reservation (simulating crash after staging but before promotion)
        String artifactId = UUID.randomUUID().toString();
        byte[] content = "staged-content".getBytes(StandardCharsets.UTF_8);
        String sha = sha256Hex(content);
        String finalRelative = "workspace/chat/conv-1/outputs/recover.txt";
        String stagingRelative = "workspace/chat/conv-1/outputs/.staging-" + artifactId;

        // Write staging bytes
        Path stagingPath = rootRelativePathService.resolve(stagingRelative);
        Files.createDirectories(stagingPath.getParent());
        Files.write(stagingPath, content);

        // Write final bytes (simulate that the move succeeded but record wasn't updated)
        Path finalPath = rootRelativePathService.resolve(finalRelative);
        Files.write(finalPath, content);

        repository.insertReservation(PublicationRecord.builder()
                .artifactId(artifactId)
                .publicationOrigin(PublicationOrigin.CHAT)
                .workspaceKind("chat")
                .workspaceKey("conv-1")
                .workspaceIdentity("chat/conv-1")
                .rootRelativePath(finalRelative)
                .stagingPath(stagingRelative)
                .operationKey("chat-recover-1")
                .requestDigest(sha)
                .contentSha256(sha)
                .publicationState(PublicationState.STAGED)
                .expectedSha256(sha)
                .logicalName("recover.txt")
                .fileExtension(".txt")
                .build());

        // Reconcile
        ArtifactReconciliationService reconciliation = new ArtifactReconciliationService(
                repository, rootRelativePathService, directoryService, mutations);
        reconciliation.reconcileOne(repository.findById(artifactId).orElseThrow());

        PublicationRecord updated = repository.findById(artifactId).orElseThrow();
        assertThat(updated.publicationState()).isEqualTo(PublicationState.PUBLISHED);
    }

    @Test
    void finalPathCollisionIsNotOverwritten() throws Exception {
        byte[] content = "collision-test".getBytes(StandardCharsets.UTF_8);
        PublicationRecord first = service.publishChatBytes(
                "conv-1", "chat-op-collision", "collision.txt", "text/plain", content);

        // Simulate external file occupying the claimed final path
        // (different operation key ensures it's not idempotent reuse)
        String conflictSha = sha256Hex(content);
        String conflictingPath = first.rootRelativePath();

        Path finalPath = rootRelativePathService.resolve(conflictingPath);
        // The file already exists from the first publication — a new publication
        // with a different operation key should get a suffixed name
        byte[] content2 = "collision-2".getBytes(StandardCharsets.UTF_8);
        PublicationRecord second = service.publishChatBytes(
                "conv-1", "chat-op-collision-2", "collision.txt", "text/plain", content2);

        // Should get a different path (suffixed)
        assertThat(second.rootRelativePath()).isNotEqualTo(first.rootRelativePath());
        assertThat(Files.readAllBytes(finalPath)).isEqualTo(content); // First file untouched
    }

    @Test
    void normalizeLogicalNameRejectsInvalidNames() {
        assertThat(OutputArtifactService.normalizeLogicalName("hello.txt")).isEqualTo("hello.txt");
        assertThat(OutputArtifactService.normalizeLogicalName("hello world.txt")).isEqualTo("hello_world.txt");
        assertThat(OutputArtifactService.normalizeLogicalName(".hidden")).isEqualTo("output_.hidden");
        assertThatThrownBy(() -> OutputArtifactService.normalizeLogicalName(".."))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void legacyArtifactQueriesStillWork() {
        RunOutputArtifact legacy = repository.saveArtifact(new RunOutputArtifact(
                "legacy-1", "run-1", "plan-1", null, null, null, null, null,
                null, null, null, null, "output", "text", "out.txt",
                "outputs/out.txt", null, java.time.Instant.now()));
        List<RunOutputArtifact> results = repository.findArtifactsByRunId("run-1");
        assertThat(results).hasSize(1);
        assertThat(results.get(0).id()).isEqualTo("legacy-1");

        Optional<RunOutputArtifact> found = repository.findArtifactById("legacy-1");
        assertThat(found).isPresent();
    }

    @Test
    void requestDigestIsDeterministic() throws Exception {
        // Chat digest
        String d1 = OutputArtifactService.buildChatRequestDigest(
                "conv-1", "test.txt", sha256Hex("data".getBytes(StandardCharsets.UTF_8)), "text/plain");
        String d2 = OutputArtifactService.buildChatRequestDigest(
                "conv-1", "test.txt", sha256Hex("data".getBytes(StandardCharsets.UTF_8)), "text/plain");
        assertThat(d1).isEqualTo(d2);
        assertThat(d1).hasSize(64);

        // Different content produces different digest
        String d3 = OutputArtifactService.buildChatRequestDigest(
                "conv-1", "test.txt", sha256Hex("other".getBytes(StandardCharsets.UTF_8)), "text/plain");
        assertThat(d1).isNotEqualTo(d3);
    }

    private static String sha256Hex(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }
    }
}
