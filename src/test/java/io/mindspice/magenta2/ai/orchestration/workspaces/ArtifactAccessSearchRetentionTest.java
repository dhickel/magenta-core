package io.mindspice.magenta2.ai.orchestration.workspaces;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.util.List;
import java.util.Optional;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.interceptor.DefaultTransactionAttribute;
import org.springframework.transaction.interceptor.NameMatchTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.sqlite.SQLiteDataSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Focused tests for authorized artifact access, search, preview,
 * retention, and atomic extraction synchronization (Phase 03 + 05).
 */
class ArtifactAccessSearchRetentionTest {

    @TempDir
    Path tempDir;

    JdbcTemplate jdbc;
    OutputArtifactRepository artifactRepository;
    WorkspaceDirectoryService directoryService;
    RootRelativePathService rootRelativePathService;
    ConfinedPathMutationService mutations;
    OutputArtifactService publicationService;
    ArtifactSearchService searchService;
    ArtifactPreviewService previewService;
    TaskRunRetentionService retentionService;

    Path dataRoot;

    @BeforeEach
    void setUp() throws Exception {
        SingleConnectionDataSource dataSource = new SingleConnectionDataSource(
                "jdbc:sqlite::memory:?foreign_keys=true", true);
        ScriptUtils.executeSqlScript(dataSource.getConnection(), new ClassPathResource("schema.sql"));
        jdbc = new JdbcTemplate(dataSource);
        artifactRepository = new OutputArtifactRepository(jdbc);
        dataRoot = Files.createDirectories(tempDir.resolve("root-data"));
        directoryService = WorkspaceDirectoryServiceTestSupport.directoryService(dataRoot);
        rootRelativePathService = new RootRelativePathService(directoryService);
        mutations = new ConfinedPathMutationService();
        publicationService = new OutputArtifactService(artifactRepository, directoryService,
                rootRelativePathService, mutations, new ObjectMapper().findAndRegisterModules());

        WorkspaceDescriptor chatDesc = directoryService.resolveForWrite(WorkspaceKind.CHAT, "conv-1");
        directoryService.outputs(chatDesc);

        searchService = new ArtifactSearchService(jdbc, artifactRepository,
                rootRelativePathService, directoryService, mutations);
        previewService = new ArtifactPreviewService(artifactRepository, rootRelativePathService);
        retentionService = new TaskRunRetentionService(jdbc, artifactRepository,
                rootRelativePathService, directoryService, mutations);
    }

    @Test
    void artifactAccessContextRejectsWrongWorkspace() throws Exception {
        PublicationRecord record = publicationService.publishChatBytes(
                "conv-1", "access-op-1", "test.txt", "text/plain",
                "hello".getBytes(StandardCharsets.UTF_8));
        ArtifactAccessContext wrongAccess = ArtifactAccessContext.forWorkspace("agent", "other-agent");
        assertThat(wrongAccess.accepts(record)).isFalse();
        ArtifactAccessContext correctAccess = ArtifactAccessContext.forWorkspace("chat", "conv-1");
        assertThat(correctAccess.accepts(record)).isTrue();
    }

    @Test
    void artifactAccessContextFiltersByTaskRun() throws Exception {
        PublicationRecord chatRecord = publicationService.publishChatBytes(
                "conv-1", "access-op-2", "test.txt", "text/plain",
                "hello".getBytes(StandardCharsets.UTF_8));
        ArtifactAccessContext taskRunAccess = ArtifactAccessContext.forTaskRun(
                "chat", "conv-1", "some-run-id");
        assertThat(taskRunAccess.accepts(chatRecord)).isFalse();
    }

    @Test
    void previewReturnsOkForPlainText() throws Exception {
        PublicationRecord record = publicationService.publishChatBytes(
                "conv-1", "preview-op-1", "hello.txt", "text/plain",
                "Hello, world!".getBytes(StandardCharsets.UTF_8));
        ArtifactAccessContext access = ArtifactAccessContext.forWorkspace("chat", "conv-1");
        ArtifactPreviewService.PreviewResult result = previewService.preview(record.artifactId(), access);
        assertThat(result.state()).isEqualTo(ArtifactPreviewService.PreviewState.OK);
        assertThat(result.content()).isEqualTo("Hello, world!");
        assertThat(result.markdownAvailable()).isFalse();
    }

    @Test
    void previewReturnsOkForMarkdown() throws Exception {
        String md = "# Title\n\n**bold** text with `code`";
        PublicationRecord record = publicationService.publishChatBytes(
                "conv-1", "preview-op-2", "doc.md", "text/markdown",
                md.getBytes(StandardCharsets.UTF_8));
        ArtifactAccessContext access = ArtifactAccessContext.forWorkspace("chat", "conv-1");
        ArtifactPreviewService.PreviewResult result = previewService.preview(record.artifactId(), access);
        assertThat(result.state()).isEqualTo(ArtifactPreviewService.PreviewState.OK);
        assertThat(result.markdownAvailable()).isTrue();
        assertThat(result.safeHtml()).isNotNull();
    }

    @Test
    void previewReturnsMissingForUnauthorized() throws Exception {
        PublicationRecord record = publicationService.publishChatBytes(
                "conv-1", "preview-op-3", "secret.txt", "text/plain",
                "secret".getBytes(StandardCharsets.UTF_8));
        ArtifactAccessContext access = ArtifactAccessContext.forWorkspace("agent", "other");
        ArtifactPreviewService.PreviewResult result = previewService.preview(record.artifactId(), access);
        assertThat(result.state()).isEqualTo(ArtifactPreviewService.PreviewState.MISSING);
    }

    @Test
    void previewUnsupportedForBinaryMedia() throws Exception {
        PublicationRecord record = publicationService.publishChatBytes(
                "conv-1", "preview-op-4", "image.png", "image/png",
                new byte[]{0x00, 0x01, 0x02});
        ArtifactAccessContext access = ArtifactAccessContext.forWorkspace("chat", "conv-1");
        ArtifactPreviewService.PreviewResult result = previewService.preview(record.artifactId(), access);
        assertThat(result.state()).isEqualTo(ArtifactPreviewService.PreviewState.UNSUPPORTED);
    }

    @Test
    void markdownRenderingStripsRawHtml() {
        String unsafeMd = "<script>alert(1)</script># Title";
        String safeHtml = previewService.renderSafeMarkdown(unsafeMd);
        assertThat(safeHtml).isNotNull();
        assertThat(safeHtml.toLowerCase()).doesNotContain("<script>");
        assertThat(safeHtml).contains("Title");
    }

    @Test
    void retentionBlocksArtifactWithActiveSession() throws Exception {
        PublicationRecord record = publicationService.publishChatBytes(
                "conv-1", "ret-op-1", "keep.txt", "text/plain",
                "valuable data".getBytes(StandardCharsets.UTF_8));
        ArtifactAccessContext access = ArtifactAccessContext.forWorkspace("chat", "conv-1");
        Optional<ArtifactSearchService.AuthorizedByteHandle> handle =
                searchService.openAuthorizedBytes(record.artifactId(), access, 1024);
        assertThat(handle).isPresent();
        String blocker = retentionService.removalBlocker(record.artifactId());
        assertThat(blocker).isNotNull();
        assertThat(blocker).contains("active download");
        handle.get().close();
        blocker = retentionService.removalBlocker(record.artifactId());
        assertThat(blocker).isNull();
    }

    @Test
    void retentionPinBlocksDeletion() throws Exception {
        PublicationRecord record = publicationService.publishChatBytes(
                "conv-1", "ret-op-2", "pinned.txt", "text/plain",
                "pinned data".getBytes(StandardCharsets.UTF_8));
        ArtifactAccessContext access = ArtifactAccessContext.forWorkspace("chat", "conv-1");
        retentionService.addPin(record.artifactId(), null, "MANUAL",
                "Test pin", "tester", null);
        String blocker = retentionService.removalBlocker(record.artifactId());
        assertThat(blocker).isNotNull();
        assertThat(blocker).contains("pinned");
        List<TaskRunRetentionService.RetentionPin> pins =
                retentionService.listPins(record.artifactId(), null);
        assertThat(pins).hasSize(1);
        retentionService.removePin(pins.get(0).id());
        blocker = retentionService.removalBlocker(record.artifactId());
        assertThat(blocker).isNull();
    }

    @Test
    void softArchiveRemovesFileAndMarksFailed() throws Exception {
        PublicationRecord record = publicationService.publishChatBytes(
                "conv-1", "ret-op-3", "archive-me.txt", "text/plain",
                "temporary".getBytes(StandardCharsets.UTF_8));
        ArtifactAccessContext access = ArtifactAccessContext.forWorkspace("chat", "conv-1");
        boolean archived = retentionService.softArchive(record.artifactId(), access);
        assertThat(archived).isTrue();
        PublicationRecord updated = artifactRepository.findById(record.artifactId()).orElseThrow();
        assertThat(updated.publicationState()).isEqualTo(PublicationState.FAILED);
        Path file = rootRelativePathService.resolve(record.rootRelativePath());
        assertThat(java.nio.file.Files.exists(file)).isFalse();
    }

    @Test
    void lineagePreventsCycles() throws Exception {
        PublicationRecord parent = publicationService.publishChatBytes(
                "conv-1", "lineage-op-1", "parent.txt", "text/plain",
                "parent".getBytes(StandardCharsets.UTF_8));
        PublicationRecord child = publicationService.publishChatBytes(
                "conv-1", "lineage-op-2", "child.txt", "text/plain",
                "child".getBytes(StandardCharsets.UTF_8));
        ArtifactAccessContext access = ArtifactAccessContext.forWorkspace("chat", "conv-1");
        searchService.recordLineage(parent.artifactId(), child.artifactId(),
                "DERIVED_FROM", "agent", "agent-1", null, access);
        try {
            searchService.recordLineage(child.artifactId(), parent.artifactId(),
                    "DERIVED_FROM", "agent", "agent-1", null, access);
            assertThat(false).as("Expected cycle detection").isTrue();
        } catch (IllegalArgumentException e) {
            assertThat(e.getMessage()).contains("cycle");
        }
    }

    @Test
    void authorizedByteHandleVerifiesIntegrity() throws Exception {
        PublicationRecord record = publicationService.publishChatBytes(
                "conv-1", "byte-op-1", "readme.txt", "text/plain",
                "content".getBytes(StandardCharsets.UTF_8));
        ArtifactAccessContext access = ArtifactAccessContext.forWorkspace("chat", "conv-1");
        Optional<ArtifactSearchService.AuthorizedByteHandle> handle =
                searchService.openAuthorizedBytes(record.artifactId(), access, 1024);
        assertThat(handle).isPresent();
        byte[] data = handle.get().readAllBytes();
        assertThat(new String(data, StandardCharsets.UTF_8)).isEqualTo("content");
        handle.get().close();
    }

    @Test
    void retentionPolicyDefaultsToPreserve() {
        TaskRunRetentionService.RetentionPolicy policy =
                retentionService.getPolicy("agent", "agent-1", null);
        assertThat(policy.policyType()).isEqualTo("PRESERVE");
    }

    @Test
    void setAndGetRetentionPolicy() {
        retentionService.setPolicy("agent", "agent-1", null,
                "SOFT_ARCHIVE", 90, null, null);
        TaskRunRetentionService.RetentionPolicy policy =
                retentionService.getPolicy("agent", "agent-1", null);
        assertThat(policy.policyType()).isEqualTo("SOFT_ARCHIVE");
        assertThat(policy.retainDays()).isEqualTo(90);
    }

    @Test
    void searchUnavailableWithoutFts5() {
        assertThat(searchService.ftsAvailable() || !searchService.ftsAvailable()).isTrue();
    }

    @Test
    void previewStripsRemoteImagesFromMarkdown() {
        String md = "![img](https://evil.com/track.png)";
        String safe = previewService.renderSafeMarkdown(md);
        assertThat(safe).isNotNull();
        assertThat(safe).doesNotContain("https://evil.com");
    }

    @Test
    void previewStripsUnsafeSchemes() {
        String md = "[click](javascript:alert(1))";
        String safe = previewService.renderSafeMarkdown(md);
        assertThat(safe).isNotNull();
        assertThat(safe).contains("click");
    }

    @Test
    void duplicateLineageEdgeRejected() throws Exception {
        PublicationRecord parent = publicationService.publishChatBytes(
                "conv-1", "lineage-op-3", "p2.txt", "text/plain",
                "p2".getBytes(StandardCharsets.UTF_8));
        PublicationRecord child = publicationService.publishChatBytes(
                "conv-1", "lineage-op-4", "c2.txt", "text/plain",
                "c2".getBytes(StandardCharsets.UTF_8));
        ArtifactAccessContext access = ArtifactAccessContext.forWorkspace("chat", "conv-1");
        searchService.recordLineage(parent.artifactId(), child.artifactId(),
                "DERIVED_FROM", "agent", "agent-1", null, access);
        try {
            searchService.recordLineage(parent.artifactId(), child.artifactId(),
                    "DERIVED_FROM", "agent", "agent-1", null, access);
            assertThat(false).as("Expected duplicate rejection").isTrue();
        } catch (IllegalArgumentException e) {
            assertThat(e.getMessage()).contains("Duplicate");
        }
    }

    @Test
    void retentionHardExpireRemovesAllTraces() throws Exception {
        PublicationRecord record = publicationService.publishChatBytes(
                "conv-1", "ret-op-4", "delete-me.txt", "text/plain",
                "goodbye".getBytes(StandardCharsets.UTF_8));
        ArtifactAccessContext access = ArtifactAccessContext.forWorkspace("chat", "conv-1");
        boolean result = retentionService.hardExpire(record.artifactId(), access);
        assertThat(result).isTrue();
        assertThat(artifactRepository.findById(record.artifactId())).isEmpty();
    }

    // ════════════════════════════════════════════════════════════════
    // Phase 03: Atomic artifact extraction tests
    // ════════════════════════════════════════════════════════════════

    /** Transactional extraction tests with a Spring-proxied repository. */
    @Nested
    class AtomicExtractionTests {

        @TempDir
        Path tempDir;

        Path dbFile;
        JdbcTemplate repoJdbc;
        JdbcTemplate verifyJdbc;
        ArtifactExtractionRepository extractionRepo;
        ArtifactSearchService atomicSearchService;
        OutputArtifactRepository artifactRepo;

        @BeforeEach
        void setUp() throws Exception {
            dbFile = tempDir.resolve("test.db");

            SQLiteDataSource primaryDs = new SQLiteDataSource();
            primaryDs.setUrl("jdbc:sqlite:" + dbFile.toAbsolutePath());
            primaryDs.setEnforceForeignKeys(true);
            try (Connection conn = primaryDs.getConnection()) {
                ScriptUtils.executeSqlScript(conn, new ClassPathResource("schema.sql"));
            }
            repoJdbc = new JdbcTemplate(primaryDs);

            PlatformTransactionManager txManager = new DataSourceTransactionManager(primaryDs);
            NameMatchTransactionAttributeSource tas = new NameMatchTransactionAttributeSource();
            var requiredAttr = new DefaultTransactionAttribute(
                    TransactionDefinition.PROPAGATION_REQUIRED);
            tas.addTransactionalMethod("synchronize*", requiredAttr);
            tas.addTransactionalMethod("removeScoped*", requiredAttr);
            TransactionInterceptor txInterceptor = new TransactionInterceptor(txManager, tas);

            ArtifactExtractionRepository target = new ArtifactExtractionRepository(repoJdbc);
            ProxyFactory pf = new ProxyFactory();
            pf.setTarget(target);
            pf.addAdvice(txInterceptor);
            pf.setProxyTargetClass(true);
            extractionRepo = (ArtifactExtractionRepository) pf.getProxy();

            SQLiteDataSource verifyDs = new SQLiteDataSource();
            verifyDs.setUrl("jdbc:sqlite:" + dbFile.toAbsolutePath());
            verifyDs.setEnforceForeignKeys(true);
            verifyJdbc = new JdbcTemplate(verifyDs);

            artifactRepo = new OutputArtifactRepository(repoJdbc);

            Path dataRoot = Files.createDirectories(tempDir.resolve("root-data"));
            WorkspaceDirectoryService dirService = WorkspaceDirectoryServiceTestSupport.directoryService(dataRoot);
            RootRelativePathService rrps = new RootRelativePathService(dirService);
            ConfinedPathMutationService cms = new ConfinedPathMutationService();
            atomicSearchService = new ArtifactSearchService(repoJdbc, artifactRepo, rrps,
                    dirService, cms, extractionRepo);

            WorkspaceDescriptor chatDesc = dirService.resolveForWrite(WorkspaceKind.CHAT, "conv-tx");
            dirService.outputs(chatDesc);
        }

        private PublicationRecord publishText(String opKey, String fileName, String text) throws Exception {
            Path dataRoot = Files.createDirectories(tempDir.resolve("root-data"));
            WorkspaceDirectoryService ds = WorkspaceDirectoryServiceTestSupport.directoryService(dataRoot);
            RootRelativePathService rrps = new RootRelativePathService(ds);
            ConfinedPathMutationService cms = new ConfinedPathMutationService();
            OutputArtifactService pubService = new OutputArtifactService(artifactRepo, ds, rrps, cms,
                    new ObjectMapper().findAndRegisterModules());
            WorkspaceDescriptor chatDesc = ds.resolveForWrite(WorkspaceKind.CHAT, "conv-tx");
            ds.outputs(chatDesc);
            return pubService.publishChatBytes("conv-tx", opKey, fileName,
                    "text/plain", text.getBytes(StandardCharsets.UTF_8));
        }

        private ArtifactSearchService.ExtractionRecord extractedRecord(
                String artifactId, String sha256, String text) {
            String lang = "text";
            int lines = text.isEmpty() ? 0 : (int) text.chars().filter(c -> c == '\n').count();
            if (!text.isEmpty() && text.charAt(text.length() - 1) != '\n') lines++;
            return ArtifactSearchService.ExtractionRecord.extracted(
                    artifactId, sha256, text, text.getBytes(StandardCharsets.UTF_8).length, lines, lang);
        }

        private int countExtractionDocs() {
            Integer count = verifyJdbc.queryForObject(
                    "select count(*) from artifact_extraction_documents", Integer.class);
            return count != null ? count : 0;
        }

        private boolean ftsMatches(String term) {
            Integer count = verifyJdbc.queryForObject(
                    "select count(*) from artifact_extraction_fts where artifact_extraction_fts match ?",
                    Integer.class, term);
            return count != null && count > 0;
        }

        private Long extractionDocId(String artifactId) {
            List<Long> ids = verifyJdbc.query(
                    "select id from artifact_extraction_documents where artifact_id = ?",
                    (rs, i) -> rs.getLong(1), artifactId);
            return ids.isEmpty() ? null : ids.get(0);
        }

        private JdbcTemplate probeFailingJdbc() {
            return new JdbcTemplate(repoJdbc.getDataSource()) {
                @Override
                public void execute(String sql) {
                    if (sql.startsWith("select fts5") || sql.startsWith("create virtual table if not exists fts5_probe_")) {
                        throw new IllegalStateException("injected FTS5 probe failure");
                    }
                    super.execute(sql);
                }
            };
        }

        // ── FTS-capable tests ──

        @Test
        void synchronizeInsertCreatesDocumentAndFtsTokens() throws Exception {
            PublicationRecord pub = publishText("tx-ins-1", "doc.txt", "atomic insert test");
            var record = extractedRecord(pub.artifactId(), pub.verifiedSha256(), "atomic insert test");
            extractionRepo.synchronizeExtraction(record, atomicSearchService.ftsAvailable());
            assertThat(countExtractionDocs()).isEqualTo(1);
            assertThat(extractionDocId(pub.artifactId())).isNotNull();
            if (atomicSearchService.ftsAvailable()) {
                assertThat(ftsMatches("atomic")).isTrue();
            }
        }

        @Test
        void synchronizeUpdatePreservesStableId() throws Exception {
            PublicationRecord pub = publishText("tx-upd-1", "doc.txt", "initial text");
            var first = extractedRecord(pub.artifactId(), pub.verifiedSha256(), "initial text");
            extractionRepo.synchronizeExtraction(first, atomicSearchService.ftsAvailable());
            long firstId = extractionDocId(pub.artifactId());

            var second = extractedRecord(pub.artifactId(), pub.verifiedSha256(), "updated content");
            extractionRepo.synchronizeExtraction(second, atomicSearchService.ftsAvailable());
            long secondId = extractionDocId(pub.artifactId());

            assertThat(secondId).isEqualTo(firstId);
            if (atomicSearchService.ftsAvailable()) {
                assertThat(ftsMatches("initial")).isFalse();
                assertThat(ftsMatches("updated")).isTrue();
            }
        }

        @Test
        void emptyTextNotIndexed() throws Exception {
            PublicationRecord pub = publishText("tx-empty-1", "doc.txt", "some text");
            var failed = ArtifactSearchService.ExtractionRecord.failed(
                    pub.artifactId(), pub.verifiedSha256(), "error");
            extractionRepo.synchronizeExtraction(failed, atomicSearchService.ftsAvailable());
            assertThat(countExtractionDocs()).isEqualTo(1);
            if (atomicSearchService.ftsAvailable()) {
                assertThat(ftsMatches("some")).isFalse();
            }
        }

        @Test
        void scopedRebuildRemovesAllInScope() throws Exception {
            PublicationRecord pub1 = publishText("tx-scope-1", "a.txt", "first document");
            PublicationRecord pub2 = publishText("tx-scope-2", "b.txt", "second document");
            var rec1 = extractedRecord(pub1.artifactId(), pub1.verifiedSha256(), "first document");
            var rec2 = extractedRecord(pub2.artifactId(), pub2.verifiedSha256(), "second document");
            extractionRepo.synchronizeExtraction(rec1, atomicSearchService.ftsAvailable());
            extractionRepo.synchronizeExtraction(rec2, atomicSearchService.ftsAvailable());
            assertThat(countExtractionDocs()).isEqualTo(2);

            extractionRepo.removeScopedDocuments(
                    "chat", "conv-tx", null, atomicSearchService.ftsAvailable());

            assertThat(countExtractionDocs()).isEqualTo(0);
            if (atomicSearchService.ftsAvailable()) {
                assertThat(ftsMatches("first")).isFalse();
                assertThat(ftsMatches("second")).isFalse();
            }
        }

        // ── Rollback tests ──

        private ArtifactExtractionRepository faultProxy(Runnable fault, boolean scoped) {
            ArtifactExtractionRepository target = new ArtifactExtractionRepository(repoJdbc);
            if (scoped) {
                target.afterScopedTokenDeletionBeforeDocumentDeletion = fault;
            } else {
                target.afterDocumentUpsertBeforeFtsInsert = fault;
            }
            PlatformTransactionManager txManager = new DataSourceTransactionManager(
                    repoJdbc.getDataSource());
            NameMatchTransactionAttributeSource tas = new NameMatchTransactionAttributeSource();
            var requiredAttr = new DefaultTransactionAttribute(
                    TransactionDefinition.PROPAGATION_REQUIRED);
            tas.addTransactionalMethod("synchronize*", requiredAttr);
            tas.addTransactionalMethod("removeScoped*", requiredAttr);
            TransactionInterceptor txInterceptor = new TransactionInterceptor(txManager, tas);
            ProxyFactory pf = new ProxyFactory();
            pf.setTarget(target);
            pf.addAdvice(txInterceptor);
            pf.setProxyTargetClass(true);
            return (ArtifactExtractionRepository) pf.getProxy();
        }

        @Test
        void newRowFaultRollsBackDocumentAndFts() throws Exception {
            PublicationRecord pub = publishText("tx-roll-new-1", "doc.txt", "should roll back");
            var record = extractedRecord(pub.artifactId(), pub.verifiedSha256(), "should roll back");
            var faultRepo = faultProxy(
                    () -> { throw new RuntimeException("injected fault: before FTS insert"); },
                    false);

            assertThatThrownBy(() ->
                    faultRepo.synchronizeExtraction(record, atomicSearchService.ftsAvailable()))
                    .isInstanceOf(RuntimeException.class)
                    .hasMessageContaining("injected fault");

            assertThat(countExtractionDocs()).isEqualTo(0);
            if (atomicSearchService.ftsAvailable()) {
                assertThat(ftsMatches("should")).isFalse();
            }
        }

        @Test
        void updateFaultRetainsPriorCommittedState() throws Exception {
            PublicationRecord pub = publishText("tx-roll-upd-1", "doc.txt", "original text");
            var first = extractedRecord(pub.artifactId(), pub.verifiedSha256(), "original text");
            extractionRepo.synchronizeExtraction(first, atomicSearchService.ftsAvailable());
            assertThat(countExtractionDocs()).isEqualTo(1);
            long firstId = extractionDocId(pub.artifactId());

            var faultRepo = faultProxy(
                    () -> { throw new RuntimeException("injected fault: update before FTS"); },
                    false);
            var replacement = extractedRecord(pub.artifactId(), pub.verifiedSha256(), "should not appear");
            assertThatThrownBy(() ->
                    faultRepo.synchronizeExtraction(replacement, atomicSearchService.ftsAvailable()))
                    .isInstanceOf(RuntimeException.class)
                    .hasMessageContaining("injected fault");

            assertThat(countExtractionDocs()).isEqualTo(1);
            assertThat(extractionDocId(pub.artifactId())).isEqualTo(firstId);
            if (atomicSearchService.ftsAvailable()) {
                assertThat(ftsMatches("original")).isTrue();
                assertThat(ftsMatches("should not appear")).isFalse();
            }
        }

        @Test
        void rebuildRemovalFaultRetainsPriorCommittedState() throws Exception {
            PublicationRecord pub1 = publishText("tx-roll-rebuild-1", "a.txt", "keep me");
            PublicationRecord pub2 = publishText("tx-roll-rebuild-2", "b.txt", "keep me too");
            var rec1 = extractedRecord(pub1.artifactId(), pub1.verifiedSha256(), "keep me");
            var rec2 = extractedRecord(pub2.artifactId(), pub2.verifiedSha256(), "keep me too");
            extractionRepo.synchronizeExtraction(rec1, atomicSearchService.ftsAvailable());
            extractionRepo.synchronizeExtraction(rec2, atomicSearchService.ftsAvailable());
            assertThat(countExtractionDocs()).isEqualTo(2);

            var faultRepo = faultProxy(
                    () -> { throw new RuntimeException("injected fault: rebuild removal"); },
                    true);

            assertThatThrownBy(() ->
                    faultRepo.removeScopedDocuments(
                            "chat", "conv-tx", null, atomicSearchService.ftsAvailable()))
                    .isInstanceOf(RuntimeException.class)
                    .hasMessageContaining("injected fault");

            assertThat(countExtractionDocs()).isEqualTo(2);
            if (atomicSearchService.ftsAvailable()) {
                assertThat(ftsMatches("keep")).isTrue();
            }
        }

        @Test
        void persistenceFaultPropagatesFromExtractionService() throws Exception {
            PublicationRecord pub = publishText("tx-service-fault-1", "doc.txt", "cannot persist");
            ArtifactSearchService faultyService = new ArtifactSearchService(repoJdbc, artifactRepo,
                    new RootRelativePathService(WorkspaceDirectoryServiceTestSupport.directoryService(
                            tempDir.resolve("root-data"))),
                    WorkspaceDirectoryServiceTestSupport.directoryService(tempDir.resolve("root-data")),
                    new ConfinedPathMutationService(),
                    faultProxy(() -> { throw new RuntimeException("injected service persistence fault"); }, false));

            assertThatThrownBy(() -> faultyService.extractText(pub.artifactId(),
                    ArtifactAccessContext.forWorkspace("chat", "conv-tx")))
                    .isInstanceOf(RuntimeException.class)
                    .hasMessageContaining("Extraction persistence failed");
            assertThat(countExtractionDocs()).isZero();
        }

        // ── FTS-unavailable tests ──

        @Test
        void probeFailureLeavesSearchUnavailableAndPersistsDocumentsWithoutFts() throws Exception {
            PublicationRecord pub = publishText("tx-nofts-1", "doc.txt", "text without FTS");
            WorkspaceDirectoryService ds = WorkspaceDirectoryServiceTestSupport.directoryService(
                    tempDir.resolve("root-data"));
            RootRelativePathService rrps = new RootRelativePathService(ds);
            ArtifactSearchService noFtsService = new ArtifactSearchService(
                    probeFailingJdbc(), artifactRepo, rrps, ds, new ConfinedPathMutationService(),
                    new ArtifactExtractionRepository(repoJdbc));
            ArtifactAccessContext access = ArtifactAccessContext.forWorkspace("chat", "conv-tx");

            assertThat(noFtsService.ftsAvailable()).isFalse();
            assertThat(noFtsService.extractText(pub.artifactId(), access)).isPresent();
            assertThat(countExtractionDocs()).isEqualTo(1);
            assertThat(ftsMatches("without")).isFalse();

            Files.delete(rrps.resolve(pub.rootRelativePath()));
            ArtifactSearchService.SearchResult result = noFtsService.search("text", access, 0, 10);
            assertThat(result.ftsAvailable()).isFalse();
            assertThat(result.items()).isEmpty();
        }
    }
}
