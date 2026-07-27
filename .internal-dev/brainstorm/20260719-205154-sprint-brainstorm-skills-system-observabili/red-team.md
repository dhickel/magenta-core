## Prompt

Red-team only the supplied architectural synthesis for Magenta’s task-run outputs, scoped skills, observability, schedules, and quality strategy. Identify overlooked constraints, invalid assumptions, likely defects, and failure modes without reconstructing or requesting worker reports. No project edits are authorized.

## Source

The sole source reviewed is the supplied `<authoritative-synthesis>`, including its Prompt, Findings, Options, Trade-offs, Open Questions, and Recommended Next Step. Claims about current code behavior are treated as unverified assumptions where the synthesis does not itself provide evidence.

## Findings

### 1. The proposal depends on unresolved identity semantics

- `<taskPathKey>/<taskRunId>` is not safe or stable merely because the key is “validated” and captured. The synthesis does not define key generation, uniqueness, case folding, Unicode normalization, maximum length, reserved names, collision handling, or behavior for two tasks with the same display name.
- A human-readable task-name key conflicts with durable identity. A stable task ID plus optional readable slug is safer; otherwise delete/recreate, import, clone, and concurrent creation can alias historical directories.
- `taskRunId` is assumed to be filesystem-safe and globally or workspace-unique. Neither property is established.
- A root-relative run path is not unique without also persisting its workspace kind and key. It also becomes ambiguous if a user/agent workspace key can change or be reused.
- The task/workflow/job/run cardinalities remain undefined. Resume, retry, replay, and “attempt” may be distinct concepts, but the hierarchy only models a run. Concurrent resume or retry could write into the same directory and artifact names.
- The synthesis simultaneously presents the generic `runId` relationship as an open question and bases persistence, browsing, retention, lineage, and metrics on a stable task-run identity. This is a foundational blocker, not a detail to resolve later.

### 2. The proposed filesystem policy is not an enforceable security boundary

- A `ReservedDirectoryPolicy` in the Files UI or explorer does not constrain shell tools, direct Java/NIO callers, subprocesses, imports, archive extraction, or other file APIs. “Generic file tools should not mutate `.skills/`” is an aspiration unless every mutation route is mediated or OS-level permissions/sandboxing enforce it.
- Passing a destination `Path` into `OutputArtifactService` preserves a bypass: callers can still supply structurally invalid or cross-workspace destinations. Avoiding duplicate path construction does not require accepting an untyped arbitrary path; a trusted run-output descriptor or capability would preserve authority and confinement.
- Lexical normalization is insufficient. Symlink traversal, hard links, bind mounts, parent replacement, and check-then-use races can escape confinement or mutate protected content. `publishExistingFile(...)`, package ingestion, preview extraction, and retention are especially exposed.
- Protecting `outputs/` itself does not protect it if a user can rename/delete an ancestor run directory, replace a child with a symlink, or mutate published files through a process that bypasses the explorer.
- “Final published artifacts only” has no lifecycle contract: atomic publish, filename collision policy, immutability, incomplete-copy cleanup, digest verification, and behavior if the source changes during publication are unspecified.
- `tasks/`, `.skills/`, and `outputs/` are proposed as independently named reserved structures, but classification ownership between `WorkspacePathLayout`, `WorkspaceDirectoryService`, and `ReservedDirectoryPolicy` is unclear. This risks creating a second structural authority despite the stated invariant.

### 3. Crash consistency and reconciliation are largely absent

- Filesystem writes and SQLite changes cannot be one atomic transaction. “Transaction-like” retention is not a design. Publication, indexing, run creation, approval/install, and deletion all need explicit state transitions, idempotency, crash recovery, and reconciliation.
- Retention can race with resume, replay, downloads, preview extraction, active producers, lineage creation, or reference creation. A status check before deletion is not a lock or lease.
- Artifact rows, FTS entries, run records, and files can diverge after crashes or manual/out-of-band changes. The synthesis names migration testing but not a repair model or source of truth.
- Old root-level outputs are said to remain queryable, but no backfill semantics exist for artifacts that lack task/run attribution. Search grouping, retention, and metrics could silently omit or misclassify them.
- Lazy directory creation plus persistent run records needs a defined state when a run exists but its directory does not, or when the directory exists but persistence failed.

### 4. CHAT and USER behavior is internally unsettled

- The “run-first” invariant is not universal while CHAT remains flat. The synthesis does not distinguish non-task chat artifacts from task artifacts strongly enough to prevent repositories and UI from treating both as equivalent runs.
- It states that chat execution selection is rejected, but also includes CHAT in the output matrix and legacy output behavior. The product semantics need an explicit distinction between chat-generated outputs and task-run outputs.
- USER execution is still an open product decision, yet it changes path factories, authorization, ownership, retention, trust, UI, and tests. Implementing generalized AGENT/USER support before that decision may create permanent surface area for a capability that is later prohibited.
- User-home skill scanning (`~/.<client>/skills`) is not the same as a Magenta USER workspace. On a remote/multi-user service, `~` likely belongs to the service account, potentially exposing host-global content across application users and violating centralized workspace confinement.
- Project-local skill roots are proposed after generic project roots were removed. Without an authoritative project filesystem root, discovery either cannot work or will reintroduce path authority through an ad hoc mechanism.

### 5. Skill precedence, assignment, and trust can produce unsafe or surprising resolution

- Name-based assignment is unstable when a newly created higher-precedence candidate shadows an approved lower-precedence skill. The synthesis does not say whether an assignment binds a logical name, a provenance-qualified candidate, or a content version.
- It is undefined whether an untrusted higher-precedence candidate blocks a trusted lower candidate, is skipped in favor of it, or makes the skill unavailable. Each choice has security and user-expectation consequences.
- A malicious untrusted file can cause denial of service by shadowing a trusted skill even if it cannot activate. Conversely, silently falling back can execute a different skill than the user inspected.
- “Most specific explicit decision wins” does not define how deny interacts with discovery precedence, deleted candidates, renamed scopes, or multiple candidates in the same root. Generic polymorphic assignment targets also require referential integrity and authorization across session, task, job, project, and agent identities.
- “Source default” is undefined and could become an implicit trust or enablement escalation.
- Discovery metadata itself may enter the model’s catalog prompt. Untrusted titles, descriptions, or activation hints are prompt-injection vectors even when the body is gated. Untrusted content should not reach model-visible catalog metadata without a defined sanitization or approval policy.
- Content digest approval is underspecified: canonical byte representation, newline normalization, metadata inclusion, package tree hashing, symlink treatment, resource changes, signatures, and provenance binding all affect whether approval is meaningful.
- A digest check can race with activation unless the exact bytes are read, hashed, approved, and used as one immutable snapshot. Cache invalidation alone does not solve this.
- Reproducibility is missing. A run should record the exact skill candidate identity and version/digest it used. Resume after a skill changes needs a policy: use the original snapshot, require reapproval, or adopt the new version. Provenance-only audit is insufficient.
- Separating script authorization from instruction trust is necessary but not sufficient. Trusted instructions can still induce use of an already available shell or file tool. `ToolAccessPolicy` needs a clear capability model if skill activation is meant to constrain effects.
- “Control-plane-only mutation” is not enforceable if an agent can write the same directory through shell/file tools, and it is not safe if an agent can both submit content and approve its own digest.

### 6. Flat Markdown adaptation has hidden format and resource constraints

- Optional frontmatter requires a concrete grammar, parser limits, encoding, duplicate-key behavior, unknown-field policy, schema versioning, and safe handling of aliases or parser features. “YAML” is not automatically a harmless metadata format.
- Filename-derived keys need canonicalization and duplicate handling across case-insensitive filesystems and normalized Unicode.
- Discovery needs limits on file count, file size, directory depth, parsing time, and malformed files. Otherwise a workspace can degrade every catalog refresh or prompt assembly.
- Package ingestion needs defenses against traversal, symlinks, archive bombs, duplicate paths, oversized resources, signature confusion, and non-atomic cross-filesystem moves.
- Normalizing flat files and packages into one model may conceal a meaningful capability difference. UI and runtime policy must not imply that a flat skill can safely reference package resources it cannot possess.

### 7. Artifact preview and search add security and operational risks not covered

- “Existing Markdown renderer” is not evidence of safe preview. HTML passthrough, dangerous URLs, remote images, embedded content, and download headers can create stored XSS, tracking, or content-sniffing risks.
- MIME should not be trusted from filenames or producer metadata. Detection, charset errors, malformed Unicode, huge lines, decompression, and extraction time all need bounds.
- Search requires authorization filters before results, snippets, counts, and download links are exposed. Workspace/task provenance in a result does not itself enforce access control.
- SQLite FTS5 availability in the deployed JDBC/native stack is assumed. Schema migration, backfill interruption, transactional synchronization, query limits, escaping, and index repair are not designed.
- Published files can be altered out of band unless they are immutable or content-addressed. Search text, preview, digest, and downloaded bytes may then disagree.
- Artifact lineage needs authorization and consistency rules for cross-workspace references, deleted parents, retention pins, and cycles. “Producer can identify it” is not enough.

### 8. Runtime metrics are not as derivable as claimed

- Existing status and timestamps do not automatically define success, duration, or health. Cancellation, timeout, partial success, retry, resume, replay, queue time, and abandoned runs need explicit semantics.
- A single run-level model/token field is likely lossy when a run performs multiple model calls, uses multiple models, or delegates work. Avoiding a generic event stream does not justify flattening inherently repeated measurements into incorrect summary columns.
- Exit code is meaningful for some subprocesses, not necessarily for an orchestration run. The synthesis mixes process metrics and run outcomes without a metric schema.
- Health-rate denominators and time windows can mislead if active, retried, canceled, or legacy runs are included inconsistently. “Unavailable” handling is good but does not replace definitions.
- An append-only audit in mutable SQLite is append-only by convention, not tamper-evident. Audit access, retention, actor identity, correlation IDs, and redaction of sensitive prompt/tool data remain unspecified.

### 9. Schedules and reactions are dramatically under-specified

- Typed DTOs, cron validation, simulation, and audit are only the UI/API edge. Missing runtime contracts include scheduler ownership, persistence, restart recovery, leader election, duplicate firing, idempotency, retries, backoff, concurrency, misfire policy, cancellation, and clock behavior.
- Timezone-aware cron still needs a cron dialect and explicit daylight-saving overlap/gap semantics.
- A “side-effect-free simulation” can diverge from production or accidentally execute tools unless the evaluator has a capability-free dry-run boundary. A preview of matching conditions is not equivalent to simulating execution.
- Reactions can create loops or event storms, including reacting to outputs or audits they themselves produce. Cycle detection, rate limits, depth limits, deduplication, and causation chains are absent.
- The proposed firing link `schedule/reaction → assignment/run → outputs` assumes all intermediate identities and atomic transitions already exist.
- Schedules materially accelerate run and artifact growth, yet retention is later in the sequence and still unresolved.

### 10. Validation scope omits the highest-risk cases

- Needed tests include concurrent run creation, duplicate names, resume races, crash injection, interrupted migration/backfill, symlink swaps, malicious archives, out-of-band skill changes during activation, shadowing behavior, unauthorized cross-workspace search, retention/download races, DST transitions, duplicate schedule firing, and reaction loops.
- Deterministic fixtures may hide filesystem and scheduler races. The design needs failure-injection and reconciliation tests, not only service integration and UI checks.
- The integrated review is described both as continuous and as a final sequencing item. This can devolve into late discovery of cross-cutting failures despite the stated intent.

### 11. The scope is too broad to be treated as one architectural chain

- Task-run storage, skill trust/discovery, artifact indexing, telemetry, scheduling, reactions, retention, and several HTMX surfaces are separate high-risk product capabilities. Calling them one chain risks coupling delivery and hiding independent go/no-go decisions.
- Cluster A does not inherently require artifact search or schedules, and parts of B do not require the full skill system. Shared run identity is a useful foundation, not justification for one oversized architecture commitment.
- The sequencing delays retention until after features that generate and index substantial data. Basic lifecycle and recovery semantics should be designed with run storage, even if advanced retention UI is later.
- The synthesis cites concrete current methods and service behavior without demonstrating that all callers, persistence assumptions, and compatibility paths were verified. Those statements should be treated as hypotheses during planning.
- The recommended brief resolves only questions 1–7, but question 8 controls storage growth, deletion safety, and lineage. It cannot be deferred past the run/artifact contract.

## Options

1. **Gate on a minimal run-storage contract first.** Define durable identities, AGENT/USER/CHAT semantics, path-key rules, trusted path types, lifecycle states, atomic publication, recovery, and basic retention before approving any broader chain. Keep skills, search, metrics, and scheduling as separately approved follow-ons.

2. **Adopt opaque stable directory identities with readable UI labels.** Use immutable task and run IDs in structural paths, display task names in metadata/UI, and avoid filesystem renames entirely. If readable slugs are mandatory, make them non-authoritative and suffix them with stable IDs.

3. **Keep filesystem discovery but bind decisions to candidate versions.** Resolve skills to `(logical key, provenance/root identity, content digest/version)`, require explicit shadowing behavior, snapshot activated content per run, and make assignment/trust decisions version-aware.

4. **Restrict the first release to agent workspaces and controlled skill roots.** Defer USER execution, host user-home scanning, and project-local roots until ownership and root authority exist. This reduces ambiguity but requires an explicit product restriction rather than silent fallback behavior.

5. **Separate observability into trustworthy summaries and later detailed telemetry.** Define outcome/duration semantics first. Permit repeated typed model/tool measurements where the domain is naturally one-to-many, without committing either to a universal event blob or lossy single-row summaries.

6. **Defer schedules and reactions as an independent architecture.** Their distributed-time, idempotency, loop, and recovery requirements deserve a separate decision record and threat/failure model. UI DTOs should not prematurely freeze runtime semantics.

## Trade-offs

- Opaque IDs reduce path readability but eliminate rename, collision, normalization, and reuse hazards. Readability can be restored in UI and manifests without making names structural identity.
- Capability-bearing path objects add type and service surface, but they materially reduce arbitrary-`Path` misuse and clarify where confinement must be enforced.
- Version-bound skill assignments are more explicit and auditable, but require migration and UX for upgrades, shadowing, revoked versions, and missing candidates.
- Restricting discovery roots reduces convenience and ecosystem compatibility, but avoids leaking service-account home content and inventing project roots outside the established workspace model.
- Immutable skill snapshots improve reproducibility and safe resume, but consume storage and require a clear policy for revoked or newly vulnerable content.
- Early lifecycle/retention design slows visible feature delivery, but prevents unrecoverable file/DB divergence and unbounded growth.
- Rich per-call metrics cost more schema and instrumentation than run summaries, but avoid presenting false precision for multi-model or retried runs.
- Splitting schedules/reactions reduces the appeal of a unified roadmap, but prevents a large, under-designed execution subsystem from riding on an HTMX management surface.

## Open Questions

1. What are the canonical identities and cardinalities for task, workflow, job, run, attempt, resume, retry, and replay?
2. Must directory names be human-readable, or may stable IDs be authoritative with labels/slugs supplied by metadata?
3. What exact tuple uniquely identifies a persisted run path, and what happens if a workspace key is renamed, deleted, or reused?
4. Which component enforces confinement against every writer, including shell/subprocess access, and what OS-level guarantees exist?
5. What are the publication and recovery state machines for run directories, artifact rows, previews, FTS entries, and deletion?
6. Are USER workspaces task-capable, and are CHAT outputs explicitly non-task artifacts? Who owns and may inspect each workspace’s content?
7. Is scanning the service account’s home directory an intentional multi-user product contract? If so, how is tenant isolation enforced?
8. What authoritative filesystem location, if any, defines a project-local skill root after generic project roots were removed?
9. Does an assignment bind a skill name, a provenance-qualified candidate, or an exact content version? What happens on shadowing, deletion, or digest change?
10. Does an untrusted higher-precedence skill block or yield to a trusted lower-precedence skill?
11. Who can author, approve, revoke, and execute skills, and can any actor perform more than one of those roles?
12. What exact skill bytes/resources are snapshotted for a run, and what does resume do after change or revocation?
13. Which model-visible metadata, if any, may be derived from untrusted skill content?
14. What access-control filter applies to artifact search, snippets, previews, lineage, and downloads?
15. What are the precise definitions of run success, duration, cancellation, partial success, and health-rate denominators?
16. What minimum retention, reconciliation, and pinning rules are required before nested task-run storage begins accumulating data?
17. Are schedules/reactions in scope for the same decision at all, and if so, what are their idempotency, misfire, concurrency, loop-prevention, and restart guarantees?

## Recommended Next Step

Do not advance the synthesis as a single architectural direction yet. Produce a non-binding **foundation decision brief** limited to run identity/storage and skill-resolution security. It should include:

- a canonical identity model and AGENT/USER/CHAT behavior matrix;
- path grammar and collision/normalization rules, preferably with stable IDs as authority;
- confinement and mutation enforcement across all file and shell routes;
- file/DB lifecycle, crash recovery, reconciliation, and minimum retention semantics;
- a version-qualified skill resolution algorithm covering shadowing, trust, assignment, cache races, snapshots, and resume;
- an ownership/authorization matrix for discovery, approval, activation, search, preview, and deletion;
- adversarial examples for traversal/symlink swaps, duplicate task names, concurrent resume, untrusted shadowing, changed skill digests, interrupted publication, and retention races.

Treat artifact search/preview, detailed metrics, and especially schedules/reactions as separate follow-on decisions after that foundation is accepted. The original three happy-path examples are insufficient without these failure-path examples and explicit invariants.
