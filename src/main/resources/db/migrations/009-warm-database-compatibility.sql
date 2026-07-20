-- Migration 009: warm-database migration bookkeeping compatibility.
--
-- Schedule and reaction hardening was shipped as immutable migration 008.
-- SchemaMigrationRunner performs the transactional normalization of legacy
-- schema_migrations constraints before ordered resources are validated. This
-- marker preserves the contiguous 001-009 history without rewriting an
-- already-applied resource or changing the migration-owned schema end state.
select 1;
