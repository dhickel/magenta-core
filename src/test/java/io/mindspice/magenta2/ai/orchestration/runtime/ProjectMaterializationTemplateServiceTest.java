package io.mindspice.magenta2.ai.orchestration.runtime;

import java.io.IOException;
import java.nio.file.Files;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThatNoException;

class ProjectMaterializationTemplateServiceTest {

    @TempDir
    java.nio.file.Path tempDir;

    private ProjectMaterializationTemplateService service;
    private ProjectRepository projectRepository;
    private JdbcTemplate jdbc;

    @BeforeEach
    void setUp() throws IOException {
        Files.createDirectories(tempDir.resolve("db"));
        jdbc = jdbcTemplate();
        projectRepository = new ProjectRepository(jdbc, new ObjectMapper());
        service = new ProjectMaterializationTemplateService(jdbc, projectRepository);
        ensureTemplateTable();
    }

    // ── Directory validation: rejections ──

    @Test
    void rejectNullPathSet() {
        assertThatThrownBy(() -> ProjectMaterializationTemplateService.validateAndNormalizeDirectories(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must not be null or empty");
    }

    @Test
    void rejectEmptyPathSet() {
        assertThatThrownBy(() -> ProjectMaterializationTemplateService.validateAndNormalizeDirectories(Set.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must not be null or empty");
    }

    @Test
    void rejectNullPath() {
        Set<String> paths = new java.util.HashSet<>();
        paths.add(null);
        assertThatThrownBy(() -> ProjectMaterializationTemplateService.validateAndNormalizeDirectories(paths))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must not be null");
    }

    @Test
    void rejectBlankPath() {
        assertThatThrownBy(() -> validate("  "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must not be blank");
    }

    @Test
    void rejectAbsolutePath() {
        assertThatThrownBy(() -> validate("/etc/passwd"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must be relative, not absolute");
    }

    @Test
    void rejectDotComponent() {
        assertThatThrownBy(() -> validate("./foo"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("'.' component");

        assertThatThrownBy(() -> validate("foo/./bar"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("'.' component");

        assertThatThrownBy(() -> validate("."))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("'.' component");
    }

    @Test
    void rejectTraversal() {
        assertThatThrownBy(() -> validate("../foo"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("'..' traversal");

        assertThatThrownBy(() -> validate("foo/../bar"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("'..' traversal");

        assertThatThrownBy(() -> validate(".."))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("'..' traversal");
    }

    @Test
    void rejectBackslash() {
        assertThatThrownBy(() -> validate("foo\\bar"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("backslash");
    }

    @Test
    void rejectControlCharacters() {
        assertThatThrownBy(() -> validate("foo\nbar"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("control characters");

        assertThatThrownBy(() -> validate("foo\tbar"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("control characters");
    }

    @Test
    void rejectDirectivePrefix() {
        assertThatThrownBy(() -> validate("!content:foo"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("directive prefix '!'");

        assertThatThrownBy(() -> validate("!exec:bar"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("directive prefix '!'");

        assertThatThrownBy(() -> validate("!overwrite:baz"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("directive prefix '!'");
    }

    @Test
    void rejectEmptyComponent() {
        assertThatThrownBy(() -> validate("foo//bar"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("empty components");
    }

    @Test
    void rejectComponentWithLeadingWhitespace() {
        assertThatThrownBy(() -> validate("foo/ bar"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("leading/trailing whitespace");
    }

    // ── Directory validation: duplicates and prefix conflicts ──

    @Test
    void rejectDuplicateAfterNormalization() {
        assertThatThrownBy(() -> ProjectMaterializationTemplateService.validateAndNormalizeDirectories(
                Set.of("src", "src/")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Duplicate");
    }

    @Test
    void rejectExactDuplicateFromARequestCollection() {
        assertThatThrownBy(() -> ProjectMaterializationTemplateService.validateAndNormalizeDirectories(
                List.of("src", "src")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Duplicate");
    }

    @Test
    void rejectPrefixConflictAncestorFirst() {
        assertThatThrownBy(() -> ProjectMaterializationTemplateService.validateAndNormalizeDirectories(
                Set.of("src", "src/main")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Prefix conflict")
                .hasMessageContaining("ancestor");
    }

    @Test
    void rejectPrefixConflictDescendantFirst() {
        assertThatThrownBy(() -> ProjectMaterializationTemplateService.validateAndNormalizeDirectories(
                Set.of("src/main", "src")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Prefix conflict")
                .hasMessageContaining("ancestor");
    }

    @Test
    void rejectDeepPrefixConflict() {
        assertThatThrownBy(() -> ProjectMaterializationTemplateService.validateAndNormalizeDirectories(
                Set.of("a/b/c", "a")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Prefix conflict");
    }

    @Test
    void siblingPathsAreNotPrefixConflict() {
        List<String> result = ProjectMaterializationTemplateService.validateAndNormalizeDirectories(
                Set.of("src/main", "src/test"));
        assertThat(result).containsExactly("src/main", "src/test");
    }

    @Test
    void pathsWithCommonPrefixButNotAncestorAreFine() {
        // "src" and "src2" are not in ancestor/descendant relationship
        List<String> result = ProjectMaterializationTemplateService.validateAndNormalizeDirectories(
                Set.of("src", "src2"));
        assertThat(result).containsExactly("src", "src2");
    }

    // ── Directory validation: normalization and ordering ──

    @Test
    void normalizesTrailingSlashes() {
        List<String> result = ProjectMaterializationTemplateService.validateAndNormalizeDirectories(
                Set.of("src///", "docs/"));
        assertThat(result).containsExactly("docs", "src");
    }

    @Test
    void trimsWhitespace() {
        List<String> result = ProjectMaterializationTemplateService.validateAndNormalizeDirectories(
                Set.of("  src  ", "\ttest\t"));
        assertThat(result).containsExactly("src", "test");
    }

    @Test
    void sortsAlphabetically() {
        List<String> result = ProjectMaterializationTemplateService.validateAndNormalizeDirectories(
                Set.of("zebra", "alpha", "gamma", "beta"));
        assertThat(result).containsExactly("alpha", "beta", "gamma", "zebra");
    }

    @Test
    void sortsNestedPaths() {
        List<String> result = ProjectMaterializationTemplateService.validateAndNormalizeDirectories(
                Set.of("docs/api", "src/main", "docs/guide"));
        // No prefix conflict: docs/api and docs/guide are siblings under docs
        assertThat(result).containsExactly("docs/api", "docs/guide", "src/main");
    }

    @Test
    void acceptsSingleValidPath() {
        List<String> result = ProjectMaterializationTemplateService.validateAndNormalizeDirectories(
                Set.of("src"));
        assertThat(result).containsExactly("src");
    }

    @Test
    void acceptsMultiLevelPath() {
        List<String> result = ProjectMaterializationTemplateService.validateAndNormalizeDirectories(
                Set.of("src/main/java"));
        assertThat(result).containsExactly("src/main/java");
    }

    // ── JSON serialization ──

    @Test
    void snapshotJsonRoundTrip() {
        List<String> dirs = List.of("docs", "src/main", "src/test");
        String json = ProjectMaterializationTemplateService.toSnapshotJson(dirs);
        List<String> parsed = ProjectMaterializationTemplateService.parseDirectories(json);
        assertThat(parsed).containsExactly("docs", "src/main", "src/test");
    }

    @Test
    void parseDirectoriesEmptyAndBlank() {
        assertThat(ProjectMaterializationTemplateService.parseDirectories(null)).isEmpty();
        assertThat(ProjectMaterializationTemplateService.parseDirectories("")).isEmpty();
        assertThat(ProjectMaterializationTemplateService.parseDirectories("  ")).isEmpty();
    }

    @Test
    void parseDirectoriesFromJsonArray() {
        List<String> result = ProjectMaterializationTemplateService.parseDirectories("[\"a\",\"b\"]");
        assertThat(result).containsExactly("a", "b");
    }

    @Test
    void persistedSnapshotMustRemainCanonicalBeforeFilesystemUse() {
        assertThat(ProjectMaterializationTemplateService.parseValidatedDirectories("[\"docs\",\"src\"]"))
                .containsExactly("docs", "src");
        assertThatThrownBy(() -> ProjectMaterializationTemplateService.parseValidatedDirectories("[\"src\",\"docs\"]"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not canonical");
        assertThatThrownBy(() -> ProjectMaterializationTemplateService.parseValidatedDirectories("[\"safe/../escape\"]"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("traversal");
        assertThatThrownBy(() -> ProjectMaterializationTemplateService.parseValidatedDirectories("[]"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must not be empty");
    }

    @Test
    void toSnapshotJsonRejectsNull() {
        assertThatThrownBy(() -> ProjectMaterializationTemplateService.toSnapshotJson(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must not be null");
    }

    // ── Digest ──

    @Test
    void digestIsDeterministic() {
        String json = ProjectMaterializationTemplateService.toSnapshotJson(
                List.of("src", "docs"));
        String d1 = ProjectMaterializationTemplateService.digest(json);
        String d2 = ProjectMaterializationTemplateService.digest(json);
        assertThat(d1).isEqualTo(d2);
        assertThat(d1).hasSize(64); // SHA-256 hex
    }

    @Test
    void digestDiffersForDifferentSnapshots() {
        String json1 = ProjectMaterializationTemplateService.toSnapshotJson(List.of("a"));
        String json2 = ProjectMaterializationTemplateService.toSnapshotJson(List.of("b"));
        assertThat(ProjectMaterializationTemplateService.digest(json1))
                .isNotEqualTo(ProjectMaterializationTemplateService.digest(json2));
    }

    @Test
    void digestRejectsNull() {
        assertThatThrownBy(() -> ProjectMaterializationTemplateService.digest(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must not be null");
    }

    // ── Template creation ──

    @Test
    void createTemplateRevision1() {
        Project project = projectRepository.save(new Project(
                "proj-1", "Test Project", null, null, null, null, null, null,
                false, java.time.Instant.now(), java.time.Instant.now()));

        ProjectMaterializationTemplate template = service.create("proj-1",
                Set.of("src", "docs"));

        assertThat(template.projectId()).isEqualTo("proj-1");
        assertThat(template.revision()).isEqualTo(1);
        assertThat(template.directoriesSnapshot()).isNotNull();

        List<String> dirs = ProjectMaterializationTemplateService.parseDirectories(
                template.directoriesSnapshot());
        assertThat(dirs).containsExactly("docs", "src");
    }

    @Test
    void createFailsWhenTemplateExists() {
        Project project = projectRepository.save(new Project(
                "proj-2", "Test", null, null, null, null, null, null,
                false, java.time.Instant.now(), java.time.Instant.now()));

        service.create("proj-2", Set.of("src"));

        assertThatThrownBy(() -> service.create("proj-2", Set.of("docs")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("already exists");
    }

    @Test
    void createRevisionIncrementsCorrectly() {
        Project project = projectRepository.save(new Project(
                "proj-3", "Test", null, null, null, null, null, null,
                false, java.time.Instant.now(), java.time.Instant.now()));

        service.create("proj-3", Set.of("src"));
        ProjectMaterializationTemplate rev2 = service.createRevision("proj-3", Set.of("src", "docs"));
        ProjectMaterializationTemplate rev3 = service.createRevision("proj-3",
                Set.of("src", "docs", "tests"));

        assertThat(rev2.revision()).isEqualTo(2);
        assertThat(rev3.revision()).isEqualTo(3);

        List<String> dirs3 = ProjectMaterializationTemplateService.parseDirectories(
                rev3.directoriesSnapshot());
        assertThat(dirs3).containsExactly("docs", "src", "tests");
    }

    @Test
    void createRevisionFailsWhenNoTemplate() {
        Project project = projectRepository.save(new Project(
                "proj-4", "Test", null, null, null, null, null, null,
                false, java.time.Instant.now(), java.time.Instant.now()));

        assertThatThrownBy(() -> service.createRevision("proj-4", Set.of("src")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("No template exists");
    }

    @Test
    void createFailsForNonexistentProject() {
        assertThatThrownBy(() -> service.create("no-such-project", Set.of("src")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Project not found");
    }

    @Test
    void createRevisionFailsForNonexistentProject() {
        assertThatThrownBy(() -> service.createRevision("no-such-project", Set.of("src")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Project not found");
    }

    @Test
    void createRejectsInvalidDirectories() {
        Project project = projectRepository.save(new Project(
                "proj-5", "Test", null, null, null, null, null, null,
                false, java.time.Instant.now(), java.time.Instant.now()));

        assertThatThrownBy(() -> service.create("proj-5", Set.of("../escape")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("traversal");
    }

    // ── Retrieval ──

    @Test
    void getCurrentRevision() {
        Project project = projectRepository.save(new Project(
                "proj-6", "Test", null, null, null, null, null, null,
                false, java.time.Instant.now(), java.time.Instant.now()));

        assertThat(service.getCurrentRevision("proj-6")).isEmpty();

        service.create("proj-6", Set.of("a"));
        assertThat(service.getCurrentRevision("proj-6")).hasValue(1);

        service.createRevision("proj-6", Set.of("a", "b"));
        assertThat(service.getCurrentRevision("proj-6")).hasValue(2);
    }

    @Test
    void findLatestByProjectId() {
        Project project = projectRepository.save(new Project(
                "proj-7", "Test", null, null, null, null, null, null,
                false, java.time.Instant.now(), java.time.Instant.now()));

        service.create("proj-7", Set.of("v1"));
        service.createRevision("proj-7", Set.of("v2"));

        Optional<ProjectMaterializationTemplate> latest = service.findLatestByProjectId("proj-7");
        assertThat(latest).isPresent();
        assertThat(latest.get().revision()).isEqualTo(2);
    }

    @Test
    void findByProjectAndRevision() {
        Project project = projectRepository.save(new Project(
                "proj-8", "Test", null, null, null, null, null, null,
                false, java.time.Instant.now(), java.time.Instant.now()));

        service.create("proj-8", Set.of("r1"));
        service.createRevision("proj-8", Set.of("r2"));

        Optional<ProjectMaterializationTemplate> rev1 = service.findByProjectAndRevision("proj-8", 1);
        assertThat(rev1).isPresent();
        assertThat(rev1.get().revision()).isEqualTo(1);

        Optional<ProjectMaterializationTemplate> rev2 = service.findByProjectAndRevision("proj-8", 2);
        assertThat(rev2).isPresent();
        assertThat(rev2.get().revision()).isEqualTo(2);

        assertThat(service.findByProjectAndRevision("proj-8", 99)).isEmpty();
    }

    @Test
    void findByProjectIdReturnsAllRevisionsDescending() {
        Project project = projectRepository.save(new Project(
                "proj-9", "Test", null, null, null, null, null, null,
                false, java.time.Instant.now(), java.time.Instant.now()));

        service.create("proj-9", Set.of("a"));
        service.createRevision("proj-9", Set.of("b"));
        service.createRevision("proj-9", Set.of("c"));

        List<ProjectMaterializationTemplate> all = service.findByProjectId("proj-9");
        assertThat(all).hasSize(3);
        assertThat(all).extracting(ProjectMaterializationTemplate::revision)
                .containsExactly(3, 2, 1);
    }

    @Test
    void deleteByIdRejectsMutationOfAnImmutableRevision() {
        Project project = projectRepository.save(new Project(
                "proj-10", "Test", null, null, null, null, null, null,
                false, java.time.Instant.now(), java.time.Instant.now()));

        ProjectMaterializationTemplate template = service.create("proj-10", Set.of("x"));
        assertThatThrownBy(() -> service.deleteById(template.id()))
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessageContaining("append-only");
        assertThat(service.findById(template.id())).isPresent();
    }

    @Test
    void deleteByIdRejectsEvenUnknownIds() {
        assertThatThrownBy(() -> service.deleteById("nonexistent-id"))
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessageContaining("append-only");
    }

    // ── Snapshot capture at allocation time: verify template content is captured ──

    @Test
    void snapshotPreservedInCreatedTemplate() {
        Project project = projectRepository.save(new Project(
                "proj-11", "Snapshot Test", null, null, null, null, null, null,
                false, java.time.Instant.now(), java.time.Instant.now()));

        Set<String> paths = Set.of("src/main/java", "src/main/resources", "docs", "config");
        ProjectMaterializationTemplate template = service.create("proj-11", paths);

        // Verify the snapshot is a valid JSON array with the expected directories
        List<String> captured = ProjectMaterializationTemplateService.parseDirectories(
                template.directoriesSnapshot());
        assertThat(captured).containsExactly("config", "docs", "src/main/java", "src/main/resources");

        // Verify revision and snapshot are available for allocation-time capture
        assertThat(template.revision()).isEqualTo(1);
        assertThat(template.directoriesSnapshot()).isNotNull();

        // Digest stability
        String d1 = ProjectMaterializationTemplateService.digest(template.directoriesSnapshot());
        String d2 = ProjectMaterializationTemplateService.digest(template.directoriesSnapshot());
        assertThat(d1).isEqualTo(d2);
    }

    @Test
    void identicalInputProducesIdenticalSnapshotAndDigest() {
        Project project = projectRepository.save(new Project(
                "proj-12", "Idempotent Test", null, null, null, null, null, null,
                false, java.time.Instant.now(), java.time.Instant.now()));

        ProjectMaterializationTemplate t1 = service.create("proj-12",
                Set.of("  src/", "docs///"));
        String snapshot1 = t1.directoriesSnapshot();
        String digest1 = ProjectMaterializationTemplateService.digest(snapshot1);

        // Recreate same logical input via createRevision
        ProjectMaterializationTemplate t2 = service.createRevision("proj-12",
                Set.of("docs", "src"));
        String snapshot2 = t2.directoriesSnapshot();
        String digest2 = ProjectMaterializationTemplateService.digest(snapshot2);

        // Canonical form should be identical
        assertThat(snapshot1).isEqualTo(snapshot2);
        assertThat(digest1).isEqualTo(digest2);
    }

    // ── Additional edge cases ──

    @Test
    void acceptUnicodeFullwidthSeparatorAsLiteralFilename() {
        // The full-width solidus U+FF0F is a valid filename character, not a path separator.
        // "..／foo" is a single valid directory component, not a traversal.
        List<String> result = ProjectMaterializationTemplateService.validateAndNormalizeDirectories(
                Set.of("..\uFF0Ffoo"));
        assertThat(result).containsExactly("..\uFF0Ffoo");
    }

    @Test
    void rejectOnlySlashes() {
        // "///" starts with '/', so it's rejected as absolute
        assertThatThrownBy(() -> validate("///"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("relative, not absolute");
    }

    @Test
    void rejectMultiSlashIsAbsoluteAfterTrim() {
        // "   ///   " after trim becomes "///" which starts with '/'
        assertThatThrownBy(() -> validate("   ///   "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("relative, not absolute");
    }

    @Test
    void rejectTrailingSlashWithPrefixConflict() {
        assertThatThrownBy(() -> ProjectMaterializationTemplateService.validateAndNormalizeDirectories(
                Set.of("src/main/", "src/main", "src")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void acceptUnicodeDirectoryNames() {
        // Unicode in path components is valid as long as it's not control or separator
        List<String> result = ProjectMaterializationTemplateService.validateAndNormalizeDirectories(
                Set.of("café", "naïve", "résumé"));
        assertThat(result).hasSize(3);
        // Sorted alphabetically by default String ordering
        assertThat(result.get(0)).isIn("café", "naïve", "résumé");
    }

    @Test
    void acceptDeeplyNestedValidPath() {
        List<String> result = ProjectMaterializationTemplateService.validateAndNormalizeDirectories(
                Set.of("a/b/c/d/e/f/g/h/i/j"));
        assertThat(result).containsExactly("a/b/c/d/e/f/g/h/i/j");
    }

    @Test
    void identicalEmptyTemplateRevisedProducesSameDigest() {
        // Even minimal templates with single dir produce stable digests
        String s1 = ProjectMaterializationTemplateService.toSnapshotJson(List.of("src"));
        String s2 = ProjectMaterializationTemplateService.toSnapshotJson(List.of("src"));
        assertThat(s1).isEqualTo(s2);
        assertThat(ProjectMaterializationTemplateService.digest(s1))
                .isEqualTo(ProjectMaterializationTemplateService.digest(s2));
    }

    @Test
    void parseDirectoriesFromEmptyJsonArray() {
        List<String> result = ProjectMaterializationTemplateService.parseDirectories("[]");
        assertThat(result).isEmpty();
    }

    @Test
    void findByIdReturnsEmptyForBlankId() {
        assertThat(service.findById(null)).isEmpty();
        assertThat(service.findById("")).isEmpty();
        assertThat(service.findById("  ")).isEmpty();
    }

    @Test
    void findByProjectIdReturnsEmptyForBlank() {
        assertThat(service.findByProjectId(null)).isEmpty();
        assertThat(service.findByProjectId("")).isEmpty();
    }

    @Test
    void findLatestByProjectIdReturnsEmptyForBlank() {
        assertThat(service.findLatestByProjectId(null)).isEmpty();
        assertThat(service.findLatestByProjectId("")).isEmpty();
    }

    @Test
    void getCurrentRevisionReturnsEmptyForBlank() {
        assertThat(service.getCurrentRevision(null)).isEmpty();
        assertThat(service.getCurrentRevision("")).isEmpty();
    }

    @Test
    void getCurrentRevisionReturnsEmptyForNonexistentProject() {
        assertThat(service.getCurrentRevision("nonexistent-project-id")).isEmpty();
    }

    // ── Helpers ──

    private List<String> validate(String path) {
        return ProjectMaterializationTemplateService.validateAndNormalizeDirectories(Set.of(path));
    }

    private JdbcTemplate jdbcTemplate() {
        SingleConnectionDataSource ds = new SingleConnectionDataSource(
                "jdbc:sqlite::memory:?foreign_keys=true", true);
        return new JdbcTemplate(ds);
    }

    private void ensureTemplateTable() {
        jdbc.execute("""
                create table if not exists project_materialization_templates (
                    id                    text primary key check (
                        length(id) between 1 and 255
                        and instr(id, '/') = 0 and instr(id, char(92)) = 0),
                    project_id            text not null check (
                        length(project_id) between 1 and 255
                        and instr(project_id, '/') = 0 and instr(project_id, char(92)) = 0),
                    revision              integer not null check (revision >= 1),
                    directories_snapshot  text not null check (length(directories_snapshot) > 0),
                    created_at            text not null,
                    unique (project_id, revision),
                    foreign key (project_id) references projects(id) on delete cascade
                )
                """);
    }
}
