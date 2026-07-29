package io.mindspice.magenta2.ai.orchestration.workspaces;

import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/** Owns atomic extraction-document and external-content FTS persistence. */
@Repository
public class ArtifactExtractionRepository {
    private final JdbcTemplate jdbc;

    Runnable afterDocumentUpsertBeforeFtsInsert = () -> {};
    Runnable afterScopedTokenDeletionBeforeDocumentDeletion = () -> {};

    public ArtifactExtractionRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional
    public void synchronizeExtraction(ArtifactSearchService.ExtractionRecord record, boolean ftsAvailable) {
        ExistingDocument existing = findByArtifactId(record.artifactId());
        if (existing == null) {
            long id = insertDocument(record);
            afterDocumentUpsertBeforeFtsInsert.run();
            if (ftsAvailable && indexable(record.extractionState(), record.extractedText())) {
                insertFtsTokens(id, record.extractedText(), record.languageHint());
            }
            return;
        }

        if (ftsAvailable && indexable(existing.extractionState(), existing.extractedText())) {
            deleteFtsTokens(existing.id(), existing.extractedText(), existing.languageHint());
        }
        updateDocument(existing.id(), record);
        afterDocumentUpsertBeforeFtsInsert.run();
        if (ftsAvailable && indexable(record.extractionState(), record.extractedText())) {
            insertFtsTokens(existing.id(), record.extractedText(), record.languageHint());
        }
    }

    @Transactional
    public void removeScopedDocuments(String workspaceKind, String workspaceKey,
                                      String taskRunId, boolean ftsAvailable) {
        if (ftsAvailable) {
            for (ExistingDocument document : findInScope(workspaceKind, workspaceKey, taskRunId)) {
                if (indexable(document.extractionState(), document.extractedText())) {
                    deleteFtsTokens(document.id(), document.extractedText(), document.languageHint());
                }
            }
        }
        afterScopedTokenDeletionBeforeDocumentDeletion.run();
        deleteDocumentsInScope(workspaceKind, workspaceKey, taskRunId);
    }

    private ExistingDocument findByArtifactId(String artifactId) {
        List<ExistingDocument> rows = jdbc.query("""
                select id, extracted_text, language_hint, extraction_state
                from artifact_extraction_documents where artifact_id = ?
                """, (rs, row) -> new ExistingDocument(rs.getLong("id"),
                rs.getString("extracted_text"), rs.getString("language_hint"),
                rs.getString("extraction_state")), artifactId);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    private long insertDocument(ArtifactSearchService.ExtractionRecord record) {
        GeneratedKeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            var statement = connection.prepareStatement("""
                    insert into artifact_extraction_documents(
                        artifact_id, content_sha256, extracted_text, byte_count,
                        line_count, language_hint, extraction_state, extracted_at)
                    values (?, ?, ?, ?, ?, ?, ?, ?)
                    """, Statement.RETURN_GENERATED_KEYS);
            statement.setString(1, record.artifactId());
            statement.setString(2, record.contentSha256());
            statement.setString(3, emptyWhenNull(record.extractedText()));
            statement.setInt(4, record.byteCount());
            statement.setInt(5, record.lineCount());
            statement.setString(6, record.languageHint());
            statement.setString(7, record.extractionState());
            statement.setString(8, record.extractedAt());
            return statement;
        }, keys);
        Number key = keys.getKey();
        if (key != null) return key.longValue();
        Long id = jdbc.queryForObject("select id from artifact_extraction_documents where artifact_id = ?",
                Long.class, record.artifactId());
        if (id == null) throw new IllegalStateException("Unable to capture extraction document id");
        return id;
    }

    private void updateDocument(long id, ArtifactSearchService.ExtractionRecord record) {
        jdbc.update("""
                update artifact_extraction_documents set
                    content_sha256 = ?, extracted_text = ?, byte_count = ?, line_count = ?,
                    language_hint = ?, extraction_state = ?, extracted_at = ?
                where id = ?
                """, record.contentSha256(), emptyWhenNull(record.extractedText()), record.byteCount(),
                record.lineCount(), record.languageHint(), record.extractionState(), record.extractedAt(), id);
    }

    private void insertFtsTokens(long id, String text, String languageHint) {
        jdbc.update("""
                insert into artifact_extraction_fts(rowid, extracted_text, language_hint)
                values (?, ?, ?)
                """, id, text, languageHint);
    }

    private void deleteFtsTokens(long id, String text, String languageHint) {
        jdbc.update("""
                insert into artifact_extraction_fts(
                    artifact_extraction_fts, rowid, extracted_text, language_hint)
                values ('delete', ?, ?, ?)
                """, id, text, languageHint);
    }

    private List<ExistingDocument> findInScope(String workspaceKind, String workspaceKey, String taskRunId) {
        StringBuilder sql = new StringBuilder("""
                select ed.id, ed.extracted_text, ed.language_hint, ed.extraction_state
                from artifact_extraction_documents ed
                join output_publications op on op.artifact_id = ed.artifact_id
                where op.workspace_kind = ? and op.workspace_key = ?
                """);
        List<Object> parameters = new ArrayList<>(List.of(workspaceKind, workspaceKey));
        if (StringUtils.hasText(taskRunId)) {
            sql.append(" and op.task_run_id = ?");
            parameters.add(taskRunId);
        }
        return jdbc.query(sql.toString(), (rs, row) -> new ExistingDocument(rs.getLong("id"),
                rs.getString("extracted_text"), rs.getString("language_hint"),
                rs.getString("extraction_state")), parameters.toArray());
    }

    private void deleteDocumentsInScope(String workspaceKind, String workspaceKey, String taskRunId) {
        StringBuilder sql = new StringBuilder("""
                delete from artifact_extraction_documents where artifact_id in (
                    select artifact_id from output_publications
                    where workspace_kind = ? and workspace_key = ?
                """);
        List<Object> parameters = new ArrayList<>(List.of(workspaceKind, workspaceKey));
        if (StringUtils.hasText(taskRunId)) {
            sql.append(" and task_run_id = ?");
            parameters.add(taskRunId);
        }
        sql.append(")");
        jdbc.update(sql.toString(), parameters.toArray());
    }

    private static boolean indexable(String state, String text) {
        return ("EXTRACTED".equals(state) || "TRUNCATED".equals(state)) && StringUtils.hasText(text);
    }

    private static String emptyWhenNull(String value) {
        return value == null ? "" : value;
    }

    private record ExistingDocument(long id, String extractedText, String languageHint, String extractionState) {}
}
