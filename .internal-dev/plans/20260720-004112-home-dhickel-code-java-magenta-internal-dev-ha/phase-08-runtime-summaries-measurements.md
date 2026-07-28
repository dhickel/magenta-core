# Phase 08 — Truthful Runtime Facts, Health, Audit, and Focused UI

## Context

Native run tables remain status truth, while companion runtime facts are incomplete across execution families. Repeated model and tool calls require typed, correlated records; legacy or missing facts must remain unavailable. This phase adds rebuildable summaries and bounded operational reads without introducing a generic telemetry stream or composing the final cross-domain Files/run experience.

## Goal

truthful runtime facts, health/audit APIs, and focused UI

## In Scope

**Write Targets**: src/main/java/io/mindspice/magenta2/ai/orchestration/runtime/RunSummaryService.java, src/main/java/io/mindspice/magenta2/ai/orchestration/runtime/RunMeasurementRepository.java, src/main/java/io/mindspice/magenta2/ai/chat/service/ChatService.java, src/main/java/io/mindspice/magenta2/api/web/RuntimeController.java, src/main/java/io/mindspice/magenta2/api/web/RuntimeHealthFragments.java

- Add migration 008 and fresh-schema equivalents for typed run summaries and repeated model/tool invocation measurements.
- Record model and tool calls at their actual shared execution boundaries with run, attempt, invocation, and correlation identity.
- Derive rebuildable summaries, elapsed facts, bounded health, history, measurement, and audit read models.
- Expose scope-authorized APIs and reusable HTMX fragments for health, history, repeated calls, and audit details.
- Add focused repository, service, controller, fragment, migration, real-execution, and browser validation.

## Out of Scope

- A generic JSON event or telemetry stream.
- Synthetic token, process-exit, timing, task attribution, or historical backfill.
- Changes to native run status ownership or retry/replay/resume semantics.
- Cross-domain run/Files composition, schedule/reaction hardening, or final operational UI integration.
- Unbounded audit export, prompt or snapshot-body disclosure, or broad JavaScript transport.

## Dependencies

phase-02-run-lifecycle-fencing.md, phase-04-artifact-publication-reconciliation.md, phase-07-skill-trust-snapshots-packages.md

## Constraints

- Native run repositories remain authoritative for status; summaries normalize only read semantics.
- One native execution family maps to the canonical `taskRunId` and explicit links established by earlier phases. Runtime code never reconstructs workspace identity from a path.
- Every actual model or tool invocation is a separate typed row. Retries never overwrite or flatten earlier calls.
- Resume adds a fenced attempt to the same run. Operator retry and replay use distinct lineage-linked runs. None of these modes is a terminal outcome.
- Measurement fields whose provider or process boundary did not report a value remain null/unavailable, never zero or inferred.
- Active and unavailable runs are reported separately and excluded from terminal health denominators. Empty denominators remain explicit.
- Audit attribution crosses skills, artifacts, schedules, reactions, or outputs only through persisted exact identifiers. Skill-to-output attribution requires explicit producer evidence.
- APIs and fragments reveal no claim secrets, absolute paths, prompts, skill snapshot bodies, or unauthorized counts and links.
- Measurement persistence must not convert a successful external call into a failed call or hide the original execution failure. Recorder failures remain observable and leave safely incomplete facts for recovery.

## Implementation Steps

1. Add immutable migration `008` through the ordered migration owner and update `schema.sql` to the same end state. Create typed summary and invocation-measurement structures with canonical run and attempt links; unique invocation identity; model/tool kind; call and correlation IDs; start/completion facts; nullable usage, timeout, exit, truncation, and interruption facts; completeness; outcome; and timestamps. Add foreign keys and indexes required by bounded run-, workspace-, and history-scoped reads. Test fresh schema parity, upgrade, rerun, checksum behavior, and interruption recovery.
2. Define an exhaustive read-model mapping from each inspected native status to `SUCCEEDED`, `FAILED`, `CANCELLED`, `TIMED_OUT`, `INTERRUPTED`, `PARTIAL`, `ABANDONED`, or `UNAVAILABLE`. Do not change native enums for display convenience. Unknown or legacy states map to unavailable and remain visible rather than entering a terminal denominator.
3. Derive wall elapsed from persisted creation to terminal or recovery completion. Derive active elapsed only from persisted active-attempt intervals, excluding queued, waiting, unclaimed, and inter-attempt gaps. A non-terminal read may expose a clearly labeled live elapsed value separately from persisted final elapsed. Missing or contradictory boundaries yield unavailable completeness rather than guessed duration.
4. At the actual shared model-call boundary, reserve one measurement before invocation and complete that same row idempotently afterward. Record run, attempt, invocation/call/correlation IDs, provider and model identifiers, outcome, timing, and response or usage facts only when supplied. If execution stops after reservation, retain an incomplete measurement. Outside an active trusted run context, recording is a no-op and must not fabricate a run.
5. At the actual shared tool-execution boundary, apply the same reserve/complete protocol for every attempted call. Record tool call ID and name, run and attempt correlation, outcome, timing, rejection, truncation, timeout, exit, and interruption only when reported. Rejected, timed-out, interrupted, and confinement-disabled calls are never recorded as successes. Use the central boundary rather than divergent per-tool instrumentation.
6. Persist shell/process facts returned by the established shell boundary without interpreting missing exit data. When confinement disables execution, record the truthful rejection/disabled outcome and absent process facts; do not synthesize an exit code, elapsed value, or successful tool execution.
7. Implement deterministic summary rebuilding from authoritative native status, canonical links, attempts, claims/recovery facts, and measurements. Aggregate only available values, retain repeated-call counts, expose completeness per aggregate, and make rebuild/upsert idempotent. A summary cache may be replaced from source facts; it must not become status authority.
8. Compose a bounded per-run audit read model from authorized workspace selection, canonical run location identity, attempts and fenced claims, exact activated skill snapshot/trust identifiers, artifact publication and producer links, measurements, schedule/reaction causation, and correlation IDs. Authorize the requested scope before joins or counts. Omit unavailable domains explicitly and never infer skill-to-output attribution from temporal proximity or shared run membership alone.
9. Compute health and bounded history with explicit terminal numerator, terminal denominator, active count, unavailable count, and legacy count. Define numerator membership by requested outcome rather than subtraction. Apply stable ordering and cursor or bounded-page semantics. Legacy runs lacking required companion facts remain visible as unavailable and never receive fabricated summaries.
10. Add scope-authorized runtime endpoints for summary, measurement history, health, and composed audit. Validate workspace kind/key and run linkage through trusted services, reject chat task-run selection, cap page sizes and expansions, and serialize typed read models only. Recheck authorization for each requested run and linked domain; return explicit unavailable or forbidden states without leaking existence through counts, status distinctions, or links.
11. Implement reusable, slot-safe `RuntimeHealthFragments` with truthful numerator/denominator labels, separate active/unavailable/legacy states, bounded history, repeated call rows, and on-demand audit expansion. Use HTMX for refresh, pagination, and detail loading. Preserve stable empty, partial, loading, forbidden, and error states; do not display absent numeric facts as zero. Add no JavaScript unless a narrowly documented interaction cannot be expressed more simply with HTMX.
12. Test every native status mapping; repeated model/tool calls; duplicate reserve/complete delivery; incomplete calls; resumed attempts; internal retries; operator retry/replay lineage; waiting gaps; stale or abandoned claims; timeout/interruption/rejection; partial usage; confinement-disabled shell execution; missing and contradictory legacy timestamps; empty denominators; summary rebuilds; audit authorization and exact joins; absent producer evidence; pagination bounds; redaction; fragments; and HTMX refresh. Include concurrency tests showing invocation uniqueness and idempotent completion.

## Required Guides

- `AGENTS.md`, `.internal-dev/AGENTS.md`, and `.internal-dev/specifications/AGENTS.md`
- Relevant architecture, service graph, services, API, web, and SimplyPages specifications
- Closest orchestration runtime, chat service, tool, API, and web package guides
- Task-relevant execution-history, stuck-assignment, Spring AI metadata, and live-chat/Playwright knowledge files
- SimplyPages dynamic rendering, component, HTMX, slot-key, and relevant dashboard demo guidance

## Technical Guidance

- Keep `RunMeasurementRepository` persistence-focused. Put native-status mapping, interval derivation, denominator rules, rebuild behavior, and audit composition behind runtime services; controllers only validate transport input and delegate.
- Represent invocation type, outcome, and completeness as closed typed values. Use nullable typed columns for optional provider/process facts; do not hide arbitrary event payloads in JSON.
- Use a stable invocation key supplied by the execution boundary. Reserving the same key with the same immutable identity returns the existing row; conflicting identity is rejected. Completion is idempotent and cannot mutate immutable correlation fields.
- Catch recorder failures at the instrumentation boundary only after making them observable through the project’s established failure reporting. Preserve the original model/tool result or exception and leave reconstruction-safe incomplete state.
- Keep summary derivation pure over persisted facts where practical so repository fixtures can prove deterministic rebuilds. Persisted summaries are read optimization and audit snapshots, not a replacement for native truth.
- Query audit domains through their owning services or bounded read interfaces. Do not let fragments query repositories or reopen artifact/skill content.
- Keep health/history defaults small, enforce hard maximums, and use stable keys for pagination so concurrent inserts do not duplicate or skip already traversed rows.

## Validation

- Run focused migration, repository, status-mapping, interval, measurement, summary, health, audit, chat-boundary, tool-boundary, controller, and fragment tests through the required `gpt-5.3-codex` medium-reasoning test subagent.
- Prove migration 008 upgrades a representative existing database, matches fresh `schema.sql`, reruns as a no-op, rejects checksum drift, and recovers from an injected interruption.
- Execute a real task containing repeated model and tool calls and verify one typed row per actual invocation, stable run/attempt correlation, nullable unavailable facts, and no flattened retries.
- Interrupt and resume a run; verify one run with distinct attempts, active elapsed excluding waiting gaps, wall elapsed including them, and idempotent summary rebuild.
- Exercise failed, cancelled, timed-out, interrupted, partial, abandoned, active, unavailable, and empty-history cases; verify terminal numerator/denominator math and separate active/unavailable/legacy counts.
- Verify unauthorized scopes cannot observe runtime counts, rows, correlations, audit links, claim data, paths, prompts, or snapshot content. Verify missing producer evidence prevents skill-to-output attribution.
- Run focused Playwright validation against the running application for health, history, repeated measurements, and audit expansion on desktop and mobile. Capture screenshots and critique hierarchy, density, alignment, wrapping, overflow, responsive stacking, empty/error states, and first-viewport usefulness. Confirm standard interactions are HTMX-first and record any narrow JavaScript justification.
- Run the broader Maven suite and bounded Spring Boot startup. Treat a blocked real-execution dependency according to repository blocker policy; unit-only evidence cannot produce phase PASS.
- Have the required `openai-codex/gpt-5.6-sol` medium-reasoning validator review evidence, repair in-scope defects, and report PASS before phase completion.

## Exit Criteria

- Migration 008 and fresh schema agree, and typed repeated measurements preserve invocation identity, nullable facts, completeness, and attempt correlation under retry and interruption.
- Every inspected native status has an explicit truthful read mapping; native status ownership is unchanged.
- Wall and active elapsed follow persisted boundaries, exclude the correct gaps, and remain unavailable when source facts are insufficient.
- Summaries are deterministic and rebuildable from authoritative facts; repeated calls are never flattened and unavailable values are never manufactured.
- Health exposes explicit numerator, denominator, active, unavailable, and legacy counts; active and unavailable records do not enter terminal denominators.
- Audit composition is authorized, bounded, redacted, and joined only by exact persisted identifiers, with no speculative skill, artifact, output, schedule, or reaction attribution.
- Runtime APIs and fragments handle bounded history, partial data, empty states, forbidden scopes, and errors; fragments are reusable, responsive, slot-safe, and HTMX-first.
- Focused tests, migration validation, real repeated-call and resume checks, Maven suite, bounded startup, and desktop/mobile Playwright evidence pass.
- Final diff review finds no generic telemetry stream, fabricated legacy facts, absolute persisted paths, path-derived workspace identity, claim-secret or content leakage, speculative attribution, placeholders, or unrelated scope expansion.
- Post-phase validator review-and-repair reports PASS before phases 09 or 10 consume these runtime facts.
