## Context

`ArtifactSearchService` currently performs extraction-document and external-content FTS mutations itself. Its ordinary FTS deletion and log-and-continue DML handling can leave stale or missing index state. `ArtifactExtractionRepository` does not yet exist.

## Goal

synchronize extraction documents and FTS atomically

## In Scope

**Write Targets**: src/main/java/io/mindspice/magenta2/ai/orchestration/workspaces/ArtifactSearchService.java, src/main/java/io/mindspice/magenta2/ai/orchestration/workspaces/ArtifactExtractionRepository.java, src/test/java/io/mindspice/magenta2/ai/orchestration/workspaces/ArtifactAccessSearchRetentionTest.java

- Extraction insert, update, and scoped rebuild synchronization.
- A narrow repository transaction for extraction documents and external-content FTS DML only.
- Focused real-SQLite rollback and capability-unavailable evidence.

## Out of Scope

- SQL schema or ordered-migration edits, FTS probing, ranking redesign, fallback scans, publication, retention behavior, preview behavior, and API/UI changes.
- Repairing `TaskRunRetentionService` direct extraction-document deletion. Record it as a separately authorized follow-up; do not imply Phase 03 fixes retention deletion.

## Dependencies

none

## Constraints

- Ordered migration 005, `schema.sql`, and runtime FTS probing remain unchanged.
- `ArtifactExtractionRepository` owns only extraction-document and external-content FTS DML plus the document-row reads required to synchronize it. It does not create, alter, probe, or migrate tables.
- Authorization, candidate selection, extraction, file reads, digest verification, search, byte access, and lineage remain in `ArtifactSearchService` or their current owners.
- Extraction and file work occur outside the repository transaction. Each repository synchronization or scoped removal is one transaction; rebuild backfill runs afterward and does not hold a transaction across file work.
- FTS-active persistence commits both representations or neither. FTS-unavailable deployments retain extraction documents, return search unavailable, and never scan artifact bytes as a fallback.
- Preserve canonical artifact access: authorization occurs before byte opening and integrity checks remain unchanged.
- Do not add routes, payload fields, schema resources, migrations, UI, JavaScript, broad abstractions, or public production API surface.

## Implementation Steps

1. Add `ArtifactExtractionRepository` as a Spring-managed repository and make the production `ArtifactSearchService` constructor require it. Retain the current five-argument direct-construction signature only as explicit test compatibility for existing unlisted callers; Spring must select the repository-injected constructor, and new atomicity tests must use the Spring-managed repository rather than that compatibility path.

2. Define one repository synchronization operation that accepts an extraction-document value and the already-probed FTS capability. Within its transaction, load any existing document's stable `id`, `extracted_text`, `language_hint`, and `extraction_state`; retain the generated id for a new document. Treat text as indexable only for nonblank extracted/truncated text.

3. For an FTS-capable update with previously indexable text, issue the FTS5 external-content delete command with the **old** stable row id, text, and language before replacing the document. Do not use ordinary virtual-table `DELETE` DML:

   ```sql
   insert into artifact_extraction_fts(
       artifact_extraction_fts, rowid, extracted_text, language_hint)
   values ('delete', ?, ?, ?)
   ```

   Update the document by stable id, then insert current FTS tokens only when the new record is indexable. For a prior non-indexed document, skip the old-token command; for a non-indexable replacement, leave no new FTS row.

4. For a new document, insert the extraction row, capture its generated stable id inside the same transaction, then insert FTS tokens only when FTS is available and the record is indexable. Persist unsupported and failed extraction records as documents with their existing empty-text representation, but do not index them.

5. Let every repository DML exception and injected fault escape the transactional method so Spring rolls back document and FTS work together. Narrow service handling may log only the safe artifact id and exception classification after rollback, then propagate a failed persistence outcome; it must not log raw content, paths, or exception messages, return a successful extraction result, or perform compensating partial writes.

6. Move scoped rebuild removal into one repository transaction. Select only document rows joined to the supplied workspace/task-run scope, issue the same old-value external-content delete for each indexed row, then delete the scoped documents. A DML failure rolls back every removal in that transaction. `ArtifactSearchService.rebuildIndex` delegates this removal only when FTS is available, then runs its existing authorized backfill outside that transaction; unavailable rebuild leaves documents intact and performs no scan fallback.

7. Keep `ArtifactSearchService` responsible for the existing FTS capability probe, authorization-first record lookup, text extraction, bounded reads, digest behavior, search SQL, byte access, and lineage. Replace only its extraction upsert and scoped rebuild-delete DML with repository calls. Count a backfill item only after its synchronization commits; a persistence failure must not be counted as processed or concealed as an extraction success.

8. Add package-private, deterministic repository fault points solely for focused tests: one after a document insert/update and before its new FTS insertion, and one after scoped old-token deletion and before scoped document deletion. In an FTS-capable real SQLite Spring context, prove insert, update with stable row id, searchability of replacement text only, scoped rebuild removal, and rollback for new-row, update, and rebuild-removal faults. After each injected failure, inspect both document rows and `MATCH` results through a separate JDBC connection to prove the last committed pair remains intact.

9. Add mandatory FTS-unavailable coverage using a test-only probe-failing JDBC path: extraction still persists its document, `search` returns the explicit unavailable result, and no FTS rows or artifact-content scan are used. Gate only FTS-specific assertions on actual FTS5 capability; the unavailable assertions run in every environment.

## Required Guides

- AGENTS.md
- .internal-dev/AGENTS.md
- .internal-dev/specifications/AGENTS.md
- src/main/java/io/mindspice/magenta2/ai/AGENTS.md
- src/main/java/io/mindspice/magenta2/ai/orchestration/AGENTS.md
- src/main/java/io/mindspice/magenta2/ai/orchestration/workspaces/AGENTS.md

## Technical Guidance

- The FTS delete command is data-sensitive: it must receive the values currently represented in the index, not the replacement values. Its stable row id remains the document id across update.
- Keep the transaction boundary at the repository method. `@Transactional` must be exercised through a Spring proxy backed by a `DataSourceTransactionManager`; a direct repository instance does not establish the production proof.
- Use a file-backed SQLite test datasource with separate verification connections. Run the canonical schema once, but do not edit it. A single-connection fixture can support unrelated unit checks but cannot prove commit visibility or rollback isolation here.
- Make the fault hook package-private and deterministic. It throws a runtime persistence failure at the named boundary; production wiring uses the no-fault behavior. Do not add a general fault-injection framework.
- If an existing external-content FTS row is inconsistent before synchronization, do not silently repair around a failed delete. Let the DML failure roll back and surface as a safe persistence failure.
- Keep FTS availability a capability result, not an error to be hidden. Search remains unavailable rather than falling back to SQL `LIKE`, filesystem walking, or artifact-byte scans.

## Validation

- Run `ArtifactAccessSearchRetentionTest` with its Spring-proxied, real-SQLite transaction fixture. Cover FTS-capable new insert, update, stable-id preservation, old-token removal, new-token insertion, scoped rebuild removal, and injected rollback for new-row, update, and rebuild-removal paths.
- In each rollback assertion, verify document state and FTS `MATCH` behavior from a separate connection after the failed repository call. Confirm the new-row failure leaves neither representation, while update and rebuild failures retain the prior committed representation.
- Run the FTS-unavailable test in every environment. Assert document persistence, explicit unavailable search, and absence of a fallback scan; skip only capability-dependent FTS assertions when FTS5 is unavailable.
- Run existing artifact publication, preview, retention, authorization, migration-integrity, and `OutputControllerTest` suites to confirm the retained test-construction path compiles and unchanged artifact behavior remains intact.
- Run the applicable Maven test suite, configured compile/package checks, and bounded ephemeral-port Spring Boot startup.
- Inspect the diff and source searches for changes to migrations, `schema.sql`, fallback search behavior, authorization-before-byte-open behavior, public routes/payloads, raw-content/path logging, and writes outside the three ledger targets.

## Exit Criteria

- For Phase 03 insert, update, and scoped rebuild removal, FTS-active extraction documents and external-content FTS rows commit together or roll back together. Update preserves stable document id, removes old tokens using old values, and indexes only current indexable text.
- New-row, update, and scoped rebuild-removal injected failures leave no partial or stale committed document/index state.
- FTS-unavailable deployments retain extraction documents, report search unavailable, and perform no fallback scan.
- Repository DML failures escape their transaction and are not reported as successful extraction or backfill work.
- No schema, migration, API, UI, JavaScript, authorization, integrity, publication, or retention behavior changed outside the declared scope.
- The post-phase validator review-and-repair gate reports PASS before Phase 05 starts.
