# Phase 02 Materialization/Context Validator Remediation

## Date

2026-07-27

## Change Summary

Added append-only migration 011 to repair strict materialization-key persistence without changing checksum-applied migration 010. Added durable operation-key/request-digest replay/conflict records for immutable binding capture and owner linking. Replaced context binding resolution exceptions for expected absent, stale, and non-ready facts with typed outcomes. Renumbered the planned system-chat migration to 012.

## Files

- `src/main/resources/db/migrations/011-materialization-key-constraints.sql`
- `src/main/resources/schema.sql`
- `src/main/java/io/mindspice/magenta2/core/persistence/SchemaMigrationRunner.java`
- `src/main/java/io/mindspice/magenta2/ai/orchestration/context/AgentContextRepository.java`
- `src/main/java/io/mindspice/magenta2/ai/orchestration/context/AgentContextService.java`
- Focused context and migration tests
- Phase plan, review, package-guide, knowledge, architecture, and service-graph records

## Behavioral Impact

Canonical materialization keys are now enforced as `v1-PROJECT-` plus lowercase SHA-256 hex in fresh and upgraded databases. Matching capture/link retries replay immutable results; contradictory operation-key reuse conflicts. Binding consumers can distinguish unavailable context from stale and non-ready context without a fallback location.

## Specification Impact

Updated `architecture.md` and `service-graph.md` for ordered migrations through 011. The Phase 02 and later plan records reserve 012 for planned system-chat proposals.

## Risks

Migration 011 rebuilds SQLite tables because SQLite cannot add a column `CHECK` constraint in place. A warm database containing a noncanonical migration-010 key fails closed and rolls back instead of silently accepting invalid data.

## Follow-up Items

None for Phase 02. Phase 03 should consume `BindingResolution` outcomes and reserve system-chat persistence as migration 012.
