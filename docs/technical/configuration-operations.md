# Configuration and Operations

Runtime defaults are split between file-backed AI configuration, persisted runtime settings, Spring application configuration, and SQLite data. Source anchors are [`application.yml`](../../src/main/resources/application.yml), [`ai/config/user`](../../src/main/java/io/mindspice/magenta2/ai/config/user), [`ai/orchestration/settings`](../../src/main/java/io/mindspice/magenta2/ai/orchestration/settings), and [`schema.sql`](../../src/main/resources/schema.sql).

## Run Mode

The app is a Spring Boot service with:

- HTTP port default `8080`.
- Magenta root default `${user.home}/.magenta`, configurable with `magenta.root.path`.
- SQLite datasource default `jdbc:sqlite:${magenta.root.path}/magenta.sqlite?foreign_keys=true`, which places the default database at `<magenta.root.path>/magenta.sqlite`.
- Avatar personal data uses a second SQLite database at `<magenta.root.path>/avatar.sqlite`, exposed as named beans `avatarDataSource` and `avatarJdbcTemplate`.
- SQL init always enabled against `classpath:schema.sql`.
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
- System chat model, prompt, approved tools, context limit, enabled flag.
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

Primary Magenta SQLite schema is initialized from `schema.sql` on startup. Avatar user-centric schema is initialized separately from `avatar-schema.sql` against `avatar.sqlite`. Repositories also self-bootstrap tables and add compatibility columns for existing local databases.

Operational implications:

- Treat `schema.sql` as the primary runtime table inventory for fresh `magenta.sqlite` databases.
- Treat `avatar-schema.sql` as the Avatar personal-data table inventory for fresh `avatar.sqlite` databases.
- Before changing a column assumption, inspect the owning repository for compatibility migrations.
- Keep foreign key behavior in mind; the SQLite URL enables `foreign_keys=true`.
- Warm data roots may have legacy workspace directories and older tables that repositories migrate forward.
- Operators can still override `spring.datasource.url`; the root-owned SQLite path is the product default, not a forced migration of custom datasource settings.
- Avatar's separate datasource is always resolved from `magenta.root.path`; it is not the primary application datasource and should not be used for orchestration/runtime tables.

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

Agent execution uses filesystem-backed workspaces and host shell tools. `application.yml` explicitly notes that agent execution uses filesystem-backed workspaces.

Workspace directories are confined under the configured data root by `WorkspaceDirectoryService`. Output downloads and artifact materialization also enforce data-root confinement.

New Magenta-owned persisted path columns store data-root-relative values. Legacy absolute values under the current configured data root are read for compatibility and may be displayed as data-root-relative values. Stale absolute values from an old root are not rewritten and fail, or are omitted from listings, when an operation tries to use that path.

## Schedules and Recurrence

Agent schedules use `agent_schedules` and `schedule_firings`. Job recurrence uses `job_recurrences`.

Cron expressions and timezones are validated by service/controller paths. Schedule firing rows de-duplicate assignment creation for a schedule/due time.

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
