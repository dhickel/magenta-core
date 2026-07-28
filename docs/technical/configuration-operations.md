# Configuration and Operations

Runtime defaults are split between file-backed AI configuration, persisted runtime settings, Spring application configuration, and SQLite data. Source anchors are [`application.yml`](../../src/main/resources/application.yml), [`ai/config/user`](../../src/main/java/io/mindspice/magenta2/ai/config/user), [`ai/orchestration/settings`](../../src/main/java/io/mindspice/magenta2/ai/orchestration/settings), and [`schema.sql`](../../src/main/resources/schema.sql).

## Run Mode

The app is a Spring Boot service with:

- HTTP port default `8080`.
- Magenta root default `${user.home}/.magenta`, configurable with `magenta.root.path`.
- SQLite datasource default `jdbc:sqlite:${magenta.root.path}/magenta.sqlite?foreign_keys=true`, which places the default database at `<magenta.root.path>/magenta.sqlite`.
- Spring's generic SQL initializer is disabled; `SchemaMigrationRunner` initializes an empty database from `classpath:schema.sql` and migrates existing databases from ordered resources.
- Spring AI provider auto-config is disabled where applicable; model clients are assembled from user AI config.
- Ollama base URL and default chat option present in Spring config, but application model routing primarily uses configured model definitions.

Startup smoke for backend wiring should use a bounded Spring Boot run, for example:

```bash
timeout 30s mvn spring-boot:run -Dspring-boot.run.arguments=--server.port=0
```

Use task-specific validation when a plan provides a stricter runtime path.

## AI Config File

`app.ai.config-path` defaults to `./config/ai-config.example.json`.

The local AI config is intentionally ignored because it contains provider credentials. The standard DeepSeek-only setup defines High and Max aliases for both `deepseek-v4-pro` and `deepseek-v4-flash`. It uses `deepseek-v4-pro-max` as the default/planning model, `deepseek-v4-flash-high` for summaries, and `deepseek-v4-flash-max` for context compaction. Add the same DeepSeek API key to all four model entries before making model-backed requests.

Run `./start.sh` to install the DeepSeek module from the sibling `spring-ai-dhickel` fork and start Magenta. Set `SPRING_AI_FORK_DIR` when the fork is checked out elsewhere. On a fresh clone, the script creates the ignored local config from `config/ai-config.deepseek-template.json` and asks you to add the API key before continuing.

`AiConfig.dataRoot` is resolved after the file is loaded:

- If `dataRoot` is omitted, Magenta uses `<magenta.root.path>/root`.
- If `dataRoot` is relative, Magenta resolves it under `magenta.root.path`.
- If `dataRoot` is absolute, Magenta keeps the absolute path for operator compatibility.

The file-backed config records:

- Model endpoint definitions.
- Endpoint type.
- Default model selection.
- `summaryModel` and planning model defaults. Legacy configs that still use the misspelled `summeryModel` field are accepted as a fallback, but new configs should use `summaryModel`.
- Optional `compactionModel`; when omitted, context compaction falls back to `summaryModel`.
- Context buffer policy.
- Web search settings.
- Legacy agent seed configuration.
- Unsafe wildcard shell override.

Source records include `AiConfig`, `ModelConfig`, `EndpointType`, `AgentConfig`, and `WebSearchConfig`.

Model endpoint definitions are read from file config. Durable agent profiles and runtime settings are stored in SQLite.

`EndpointType` selects a provider-specific Spring AI 2.0 client:

- `OLLAMA` uses the Ollama integration.
- `OPENAI_COMPATIBLE` uses the OpenAI integration and may apply configured OpenAI reasoning effort.
- `DEEPSEEK` uses the dedicated DeepSeek integration from the maintained `dhickel/spring-ai` fork. `thinkLevel` values 1 through 3 select `high` reasoning effort, while level 4 selects `max`; zero or an omitted value leaves the DeepSeek API default unchanged.

Keep DeepSeek endpoints classified as `DEEPSEEK`, even when their HTTP API resembles OpenAI. The dedicated provider retains DeepSeek `reasoning_content` when Magenta replays assistant tool-call messages.

Conversation title generation uses the effective summary model, not the model selected for the chat turn. Context compaction uses the effective compaction model, falling back to the effective summary model when no compaction model is configured.

## Runtime Settings

`RuntimeSettingsService` merges persisted settings with legacy file-configured defaults.

Persisted settings include:

- Default agent id/name.
- Default model.
- Planning, summary, and compaction model.
- Context buffer percent.
- System chat model and enabled flag. Legacy stored prompt, approved-tool, and context-limit fields do not broaden the fixed system-chat profile; its prompt, callback allowlist, and context bound are server-owned.
- Assignment history auto-purge days.
- Retain temp work.

API:

- `GET /api/settings/runtime`
- `PUT /api/settings/runtime`

Operational UI:

- `/settings`
- `PUT /settings`

When **Retain Temp Work** is enabled, task temp run directories are never auto-deleted. When disabled, Magenta deletes temp work only after clean completion and keeps temp work for runs that need review because required outputs, referenced files, or final-message deliverables were not satisfied.

## Feature Flags

Configured feature flags:

- `magenta.features.schedules-enabled`, default `true` in `application.yml`.
- `magenta.features.reactions-enabled`, default `true` in `application.yml`.

When disabled, schedule and event reaction routes in `AgentOrchestrationController` return `404`.

## Access Posture

Current alpha access is open at the application layer (no built-in Basic auth or CSRF gate). See [Security](security.md) for active safeguards and limits.

## Data and Schema Operations

`SchemaMigrationRunner` initializes an empty Magenta SQLite database from `schema.sql`, then applies immutable ordered resources from `db/migrations/` for both fresh and existing databases, recording exact resource names and SHA-256 checksums in `schema_migrations`. It uses one SQLite immediate transaction so migration SQL and bookkeeping are atomic and independent starters serialize. Repository beans initialize only after this runner. Older domain tables still retain repository-local compatibility DDL until explicit later migrations transfer their ownership.

Operational implications:

- Treat `schema.sql` as the fresh database inventory and ordered migration resources as immutable existing-database history; their migration-owned end states must match.
- Never edit an applied migration. Add the next contiguous `NNN-lowercase-kebab.sql` resource.
- Startup fails closed on checksum/name drift, unknown or non-contiguous bookkeeping, malformed resources, or incompatible partial migration-owned tables.
- Before changing an older column assumption, inspect the owning repository for remaining compatibility DDL.
- Keep foreign key behavior in mind; the SQLite URL enables `foreign_keys=true`.
- Warm data roots may have legacy workspace directories and older tables that repositories tolerate but do not migrate.
- Operators can still override `spring.datasource.url`; the root-owned SQLite path is the product default, not a forced migration of custom datasource settings.
- `avatar.sqlite`, `avatar-schema.sql`, Avatar tables, and the Avatar datasource beans are removed. Legacy `avatar.sqlite` files remain untouched on disk but are inactive.

## Root Carry-Forward

The root-owned default layout is a breaking cleanup. Magenta does not auto-copy, auto-delete, archive, or repair old runtime files during startup. To carry existing chat history and ordinary chat files forward:

1. Stop Magenta.
2. Back up the old SQLite database and old data root.
3. Create or choose the new Magenta root.
4. Copy the existing database from old `./chat-memory.db` or the configured database path to `<magenta.root.path>/magenta.sqlite`.
5. Copy the old data root `chats/` directory to `<magenta.root.path>/root/chats/`.
6. Do not copy workspace, output, or runtime directories unless archiving them outside Magenta. Magenta does not auto-copy or delete them.

Future migration work is intentionally not implemented in this cleanup. Candidate follow-ups are a one-time migration CLI, an admin import/API path, startup diagnostics or repair, and a controlled rewrite for old absolute database rows.

## Filesystem Workspaces

Agent execution uses filesystem-first workspaces in the closed three-kind model (`AGENT`, `CHAT`, `USER`). `application.yml` explicitly notes that agent execution uses filesystem-backed workspaces.

Workspace directories are confined under the configured data root by `WorkspaceDirectoryService`. Artifact byte reads also enforce data-root and request-scope confinement. The active roots are `workspace/agent/<id>/`, `workspace/chat/<id>/`, and `workspace/user/<name>/`. Agent/user roots may contain canonical `tasks/<taskPathKey>/<taskRunId>/` children and a lazy `.skills/` child. Run-local `outputs/` and `staging/` remain lazy and are created only by active publication. Existing workspace-root `outputs/` remains legacy/non-task publication data and is not moved.

New Magenta-owned persisted path columns store data-root-relative values. Legacy absolute values under the current configured data root are read for compatibility and may be displayed as data-root-relative values. Stale absolute values from an old root are not rewritten and fail, or are omitted from listings, when an operation tries to use that path.

## Capability And Recovery Operations

- SQLite FTS5 is probed at runtime. When absent, artifact search is unavailable; scoped metadata/detail remains available and no unapproved broad search fallback runs.
- Filesystem mutation confinement requires supported Linux 64-bit/default-provider relative-handle operations. Unsupported providers fail closed.
- Host process confinement is not available, so workspace shell and executable skill-resource authorization fail closed. Instruction approval remains usable through immutable snapshots.
- Ordered migration resources are contiguous through `013-system-chat-proposals.sql`. Migration 008 remains the immutable schedule/reaction schema owner; migration 009 marks the warm-bookkeeping compatibility release; migrations 010-012 add materialization/context/binding persistence; and migration 013 adds system-chat proposals. The runner transactionally normalizes known legacy bookkeeping and replaces the known pre-foundation task-run prototype only when all prototype tables are empty. Startup still rejects checksum drift, gaps, malformed names, non-empty prototype state, and incompatible partial migration-owned structures. See [Data Model](data-model.md); the [001–009 guide](migrations-001-009.md) remains a historical foundation reference.
- Reconciliation never promotes task staging without a live writer capability. Retention reports active attempts/claims/downloads/references/lineage/legal pins or unresolved reconciliation as blockers.

## Schedules and Recurrence

Agent schedules use revisioned `schedule_definitions`, `scheduler_claims`, `schedule_firings`, and `schedule_firing_attempts`; reactions use revisioned definition/dispatch/attempt tables. Job recurrence remains `job_recurrences`.

Cron expressions and timezones are validated. Schedule slot identity plus claim fencing and operation-key digest idempotency prevent duplicate enqueue. Reaction causation/rate/depth checks suppress loops. Simulations are side-effect-free and must never be interpreted as execution.

## Operational Validation

For nontrivial code changes:

- Run focused automated tests for changed packages.
- Run a bounded Spring startup smoke unless blocked by local services/secrets.
- For UI/chat/SSE/planning/concurrent interaction changes, use focused Playwright validation against a running app and follow the repo's Playwright workflow.

For documentation-only changes like this phase:

- Verify source links and referenced files exist.
- Check route/source references against current controllers.
- Run `git diff --check`.
- Avoid starting runtime services unless the task explicitly asks for live behavior validation.

## Common Operational Reads

- `GET /api/runtime/status`: app status, timestamp, default agent, model count.
- `GET /api/dashboard/summary`: dashboard summary for projects, work, agents, inbox, outputs, and system stats.
- `GET /api/models`: configured model summaries.
- `GET /api/settings/runtime`: persisted/effective runtime settings.

These routes are public `GET` routes in current alpha posture; avoid exposing secrets through them.

Configured model map keys are the operator-facing aliases. The default browser chat model is selected from runtime settings when present, then file-configured `defaultModel`, then the legacy default-agent model. The `/chat` model selectors display alias names and submit alias keys; provider `remoteModelName` values are resolved by the model router.

Persisted runtime settings from an older configuration may contain a remote model name instead of a current alias. Magenta accepts that value when it still matches a configured `remoteModelName`; new settings updates must use one of the current aliases.
