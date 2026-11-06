# Configure persistence

Every loop requires exactly one storage source. Magenta Core installs a fresh,
namespaced SQLite schema and validates it strictly at startup. This page covers
both storage modes, the clean-break checks, restart expectations, and caller
migrations.

## Mode A: Magenta owns the SQLite file

```java
TurnLoop loop = TurnLoopBuilder.create()
    .dataRoot(Path.of("./data"))
    .model("local", modelConfig)
    .build();
```

What `build()` does with `dataRoot`:

1. Normalizes the path to an absolute, normalized form.
2. Creates the directory (including parents) when missing; fails with
   `ConfigurationException` (detail key `invalid_data_root`) when the path
   exists but is not a directory, cannot be created, or is not readable and
   writable.
3. Opens `jdbc:sqlite:<root>/magenta-core.db` and initializes the framework
   schema.

The `dataRoot` directory and database remain **caller-owned files** — the loop
closes its connections on `close()`, but nothing deletes the files.

## Mode B: caller-owned DataSource

```java
SQLiteDataSource dataSource = new SQLiteDataSource();
dataSource.setUrl("jdbc:sqlite:/srv/app/magenta.db");

TurnLoop loop = TurnLoopBuilder.create()
    .dataSource(dataSource)
    .model("local", modelConfig)
    .build();
```

The builder:

- initializes and validates the Magenta Core schema through the supplied
  `DataSource`;
- never closes caller-owned data sources;
- configures Xerial SQLite specifics on `SQLiteDataSource` instances
  (foreign keys enforced, busy timeout 5000 ms, `IMMEDIATE` transaction mode);
  other `DataSource` types are used as supplied, and the schema script enables
  SQLite foreign keys per connection.

## Exactly one storage source

Configure neither or both and `build()` throws `ConfigurationException`:

| Condition | Detail key |
| --- | --- |
| Neither `dataRoot` nor `dataSource` | `storage_missing` |
| Both configured | `storage_conflict` |
| `dataRoot` unusable (blank, file, unreadable, unwritable) | `invalid_data_root` |
| Schema install/validation failure | `schema_initialization_failed` |

## Clean-break schema checks

Initialization runs inside one transaction and expects:

- schema ID `io.mindspice:magenta-core:sealed-adts-v1`;
- fresh schema version `1`;
- the exact SHA-256 of the packaged `schema.sql` bytes.

Before executing any `CREATE IF NOT EXISTS`, it rejects:

- another Magenta Core identity, version, or hash (the prior pre-release
  identity and any future incompatible identity);
- an empty or malformed identity table;
- `magenta_core_*` tables or indexes present **without** a schema identity;
- a matching identity whose required tables, columns, indexes, checks, or
  foreign keys differ from the checked-in schema (structure is compared
  against a freshly built in-memory schema).

Rejected state is left **untouched**: nothing is rewritten, dropped, or
migrated, and the failure surfaces as `ConfigurationException` with
`schema_initialization_failed`. This is a deliberate clean break, not an
automatic data migration. Historical `turnloop_*` tables from earlier
pre-release builds may coexist in the same database and are neither read nor
modified.

## What the default stores cover

SQLite-backed defaults for every persistence SPI:

| Store | Holds |
| --- | --- |
| `SqliteConversationMemory` | Ordered sealed `TurnMessage` history. |
| `SqliteSessionStore` | Session metadata, title, model, favorite/archive flags, origin. |
| `SqliteInteractionStore` | Interaction snapshots, ordered questions/answers, tool activity. |
| `SqliteMessageQueueStore` | Queued requests, claims, and leases. |
| `SqliteAuditWriter` | Session-scoped typed JSON audit events. |

All runtime child tables reference `magenta_core_session_metadata` directly or
through interactions with cascading deletion; SQLite foreign keys are enabled.
Session deletion therefore cascades to framework-owned child rows.

## Restart expectations

| State | After restart |
| --- | --- |
| Sessions and history | Present; reused for context assembly. |
| Queue | Present; expired leases recovered by the next drain. |
| Interactions | `PENDING` rehydrates via `pendingQuestions`; `ANSWERED` resumes via drain before queued work. |
| Gate/turn state | Fresh: nothing is "active" in a new process. |
| Audit | Append-only rows remain. |

## Caller migrations

`MigrationRunner.apply(List<Migration>)` extends the schema after framework
version 1:

```java
// Version 1 belongs to Magenta Core; caller migrations are contiguous from 2.
Migration migration = new Migration() {
    @Override
    public int version() {
        return 2;
    }

    @Override
    public void migrate(DataSource dataSource) throws Exception {
        try (var statement = dataSource.getConnection().createStatement()) {
            statement.execute("CREATE TABLE app_settings (...)");
        }
    }
};

new MigrationRunner(dataSource).apply(List.of(migration));
```

Rules:

- Migrations may be supplied in any order; the runner sorts by version and
  requires a contiguous sequence after the current version.
- Version 1 and duplicate versions are rejected with `IllegalArgumentException`.
- Each migration receives a connection-bound `DataSource`; its changes and the
  version bookkeeping row commit or roll back together.
- A migration whose version is already applied is skipped silently.

The builder runs `apply(List.of())` during `build()`, so repository constructors
never create tables or indexes on their own.

## Related

- [Persistence schema reference](../reference/persistence-schema.md) — tables,
  checks, and constraints.
- [Clean-break compatibility and tradeoffs](../explanation/compatibility.md) —
  why the checks are strict.
- [Queue and drain work](queue-and-drain.md) — lease and recovery details.
- [Queueing and persistence tutorial](../tutorials/queueing-and-persistence.md) —
  runnable restart demonstration.
