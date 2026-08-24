# Persistence and queueing

The framework initializes one fresh schema using `magenta_core_*` tables. A file-backed SQLite database is created beneath `dataRoot`; a supplied JDBC `DataSource` is used directly. Existing `turnloop_*` tables are legacy data and are never migrated, rewritten, or deleted.

Queueing is durable and explicit: `enqueue`, `claim`, `ack`, and `release` provide claim-based processing, but Magenta does not start a background queue worker. Pending-question submissions are automatically enqueued for later explicit claim.
