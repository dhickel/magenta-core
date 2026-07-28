## Prompt

Red-team the supplied architectural/product synthesis only. Identify overlooked issues, hidden constraints, likely defects, failure modes, and invalid assumptions without reconstructing or requesting the underlying worker reports. Do not edit the project.

## Source

The sole source reviewed is the supplied `<authoritative-synthesis>`. No raw worker reports or project files were consulted.

## Findings

1. **The proposed allowlist is necessary but not a complete confinement boundary.** Checking an approved first path segment does not prevent escape through symlinks, hard links, rename races, archive extraction, Git checkout, nested repositories, malicious `.git` indirection, or time-of-check/time-of-use races. It also does not cover writes made by spawned programs, build tools, package managers, filters, applications, or libraries that bypass workspace file services. “Every mutating path” is not an actionable invariant until there is a single broker or OS-level sandbox that all writers must traverse.

2. **The policy may conflict with existing workspace contracts.** The synthesis introduces recognized root children (`skills`, scripts, apps, home, repositories, project materializations) but does not enumerate their canonical names, ownership, permissions, or interaction with the existing reserved `outputs/` area. It is unclear whether the allowlist applies only to `AGENT` workspaces or also changes `CHAT` and `USER` mutation behavior. A broad rollout could silently break current output publication, explorer actions, or user-root behavior.

3. **Application-owned metadata and an agent-writable repository are in tension.** If the agent can mutate `.git`, repository probes are not an authoritative safety source: it can alter remotes, refs, hooks/config, alternates, worktree metadata, or branch state. If `.git` is application-owned, normal agent Git activity must be fully brokered and direct filesystem tools must be unable to modify it. The synthesis does not choose an ownership model, yet most Git guarantees depend on it.

4. **“Repository state is the source of truth” is underspecified.** A repository has several independently changing states: worktree/index, local refs, remote-tracking refs, actual provider refs, PR state, and provider branch-protection state. A local probe can be stale immediately, while the remote cannot describe uncommitted work. The system needs explicit authorities per fact and reconciliation rules, not a single repository-versus-database distinction.

5. **Per-clone serialization does not make a shared working tree safe.** Concurrent readers can observe a checkout changing beneath them; a cancelled or crashed writer can leave locks, conflict markers, partial index state, or untracked files; and switching branches mutates the whole worktree. A writer lease also does not isolate one run’s uncommitted changes from the next. Separate task worktrees or clones may be required, but that choice affects storage, cleanup, branch ownership, and the claimed “one clone per agent” model.

6. **Binding granularity is unresolved in a way that blocks schema design.** “Assignment/run or conversation” combines objects with different lifetimes and mutation semantics. A long-lived chat can launch multiple runs against different branches or revisions. An immutable binding also needs to say whether it captures only project/materialization identity or a repository URL, branch, commit, credentials policy, and authorization snapshot. Without that distinction, reproducibility and retargeting guarantees are illusory.

7. **Context capture and materialization are not transactionally connected.** Submission can persist a binding while clone/materialization later fails, credentials expire, authorization is revoked, or the project is deleted. Conversely, a clone can be created before queue persistence fails. The synthesis omits idempotency keys, provisioning states, rollback/cleanup, retry behavior, and what queued work does when a captured binding becomes unusable.

8. **Agent-local copies do not resolve project identity or provenance.** Stable mapping is needed from project identity to path, remote, agent, and materialization generation. Renames, duplicate repository names, URL changes, forks, case-insensitive collisions, and deleted/recreated projects can otherwise bind work to the wrong content. Non-Git provenance is not merely an open product question; it is a blocker for claiming Option B supports “regular projects.”

9. **JGit is treated as safer without a demonstrated capability or threat matrix.** “Where adequate” leaves undefined required operations and security properties. The implementation must be verified for hook behavior, filters, LFS, submodules, symlinks, credential callbacks, remote protocols, alternates, partial clones, worktrees, cancellation, large repositories, and corrupt repositories. An in-process library reduces shell injection but does not by itself prevent filesystem escape, network abuse, resource exhaustion, or unsafe checkout content.

10. **Remote validation needs a network-security design, not only an allowlist.** Git URLs can enable SSRF or local access through `file:`, SSH/scp syntax, redirects, credential-bearing URLs, DNS rebinding, and provider-specific alternate endpoints. Submodules can introduce new remotes after the top-level remote is approved. Clone/fetch also needs quotas and limits for object count, decompression, depth, bandwidth, disk, time, and cancellation to resist repository bombs and denial of service.

11. **Provider operations contain unaddressed race and confused-deputy risks.** A pre-push divergence check can be invalidated before push; checks or approvals can become stale before merge; branch protection may change; fork PRs have different token and checkout risks. Webhooks require signature validation, replay protection, delivery deduplication, ordering handling, and repository/installation authorization. Server-held credentials need least privilege, tenant/agent/project scoping, rotation, revocation, and audit redaction—not just safe storage references.

12. **Branch naming and protection rules are incomplete.** Task-derived names can collide, exceed provider limits, contain invalid or adversarial text, leak sensitive task titles, or allow one run to adopt another run’s branch. Preventing direct push to a textual “base branch” is insufficient when default branches change, multiple protected branches exist, tags can trigger deployment, or force updates/deletions are possible.

13. **Prompt precedence is not prompt-injection resistance.** Putting generated policy first or declaring it higher priority does not neutralize malicious `AGENTS.md`, repository content, skill instructions, issue text, build logs, or reviewed diffs. Trusted policy, trusted configuration, untrusted instructions, and untrusted data need explicit provenance and tool-side enforcement. Prompt assembly also needs deterministic size limits, ordering, deduplication, encoding, and behavior when instruction files are symlinks or change during a run.

14. **`WORKSPACE.md` lacks a lifecycle and trust model.** “Outside the clone” does not identify its canonical location, writer, versioning, schema, migration, authorization, or binding to a specific project/materialization generation. If human editable, it cannot be treated like generated safety policy. If application managed and root-level, it must fit the root-immutability rule and remain discoverable before materialization without becoming a second inconsistent project record.

15. **Skill approval alone may not contain project-local skills.** Digest approval answers which bytes were approved, but not who may propose them, what permissions they receive, whether dependencies or referenced files are included in the digest, how symlinked content is handled, or what happens when project context changes. Discovery in an untrusted clone can itself expose parser/path vulnerabilities. Activation must remain scoped to user, agent, project, revision, and capability set as appropriate.

16. **The “restricted profile” for system chat is too soft a security boundary.** Reusing normal chat infrastructure risks inheriting tools, memory, model routing, prompt layers, or future defaults. Authorization must be enforced server-side per operation and per target, with a deny-by-default tool registry, not inferred from a profile prompt. Read-only file access can still disclose secrets, credentials, other users’ data, or prompt-injection content. Queue mutations need CSRF protection for UI flows, idempotency, authorization, confirmation semantics, and auditable actor identity.

17. **Cross-agent visibility remains a major authorization gap.** Enriching the console with project, repository, run, and skill data may expose repository URLs, branch names, task content, file paths, logs, or skill metadata across users or agents. Stable HTMX hosts and fragment endpoints must independently enforce authorization; hiding a module in the page is not access control. Virtual-node paths also must not become forgeable identifiers that bypass descriptor construction.

18. **Lazy conceptual directories create behavioral ambiguity.** The browser may display a node that does not exist, while read APIs currently may return empty for lazy roots. Create-on-first-write races, failed creation, permission errors, deletion, and stale UI need defined responses. Conceptual presence must not be mistaken for authorization or successful provisioning.

19. **Option A is not obviously the lowest-risk slice.** A hard mutation allowlist across all existing tools is a cross-cutting, potentially breaking security migration. It requires inventorying every write path and existing root-level behavior; shipping a partial allowlist can create a false sense of confinement. Workspace prompt guidance before binding semantics are settled can also establish paths and terminology that later need migration.

20. **Lifecycle and operational controls are largely absent.** There is no defined cleanup for clones/worktrees/branches, retention for audit events, disk quotas, backup/restore behavior, corruption recovery, credential revocation response, cancellation semantics, orphan reconciliation, or observability for policy denials and stuck leases. These are correctness requirements for durable workspaces and queues, not optional production polish.

21. **The UI direction assumes composition is semantically cheap.** Independently rendered queue, Git, skills, files, and chat fragments can show mutually inconsistent snapshots. Context switching during HTMX requests may render controls for one binding and act on another unless every request carries and validates a binding/version token. Multi-tab browser use and stale fragments are especially likely failure modes.

22. **The recommendation orders implementation before all foundational decisions are resolved.** Building canonical navigation and approved children before deciding the skills path, project-area naming, binding identity, and metadata placement risks a compatibility migration immediately afterward. The proposed first slice should not freeze filesystem or API contracts until those minimum naming and ownership decisions are made.

## Options

1. **Security-contract spike before Option A.** Define canonical child names and ownership, inventory all mutation paths, specify no-follow/atomic path resolution, decide `.git` ownership, and prove confinement against symlink, rename, archive, checkout, and process-write attacks. This is the safest precursor to visible UI work.

2. **Read-only context slice.** Introduce versioned, work-scoped bindings and read-only console navigation/status without clone creation, Git mutation, project-local skill activation, or system-chat commands. This tests identity, authorization, stale-fragment behavior, and lifecycle semantics before granting write authority.

3. **Brokered Git with isolated per-run worktrees/clones.** Keep a managed agent repository cache but give each mutating run an isolated worktree or clone. This costs storage and cleanup complexity but avoids shared-working-tree contamination and makes cancellation/recovery more tractable.

4. **Single-writer agent clone.** Permit only one active mutating run per agent/repository and reject or queue all others. This is simpler, but product latency and long-lived dirty-state recovery must be accepted explicitly; readers still need snapshot-consistency rules.

5. **Narrow provider-first pilot.** Support one provider, HTTPS only, one credential model, no submodules/LFS/external filters, bounded repository sizes, human-authorized merge, and explicit repository allowlisting. Broader Git compatibility should be added only from demonstrated workflows.

6. **Defer non-Git “projects.”** Treat Option B initially as managed Git project context plus generic agent files, rather than implying unresolved shared non-Git synchronization. Add non-Git project materialization only after selecting an authority and conflict model.

## Trade-offs

| Choice | Risk reduced | Cost introduced |
|---|---|---|
| Central mutation broker or OS sandbox | Bypass and path-race escapes | Larger integration surface; existing tools/processes may need redesign |
| Application-owned `.git` with brokered operations | Repository-policy tampering | Agents lose direct Git flexibility; all needed operations require service support |
| Per-run worktree/clone | Cross-run contamination and branch switching races | Disk use, provisioning latency, cleanup, more complex Git metadata |
| Versioned binding token on every action | Stale UI and silent retargeting | More explicit request/state plumbing and conflict responses |
| Provider/HTTPS-only pilot | SSRF, protocol, credential, and compatibility uncertainty | Narrower repository support |
| Human-gated merge initially | Racey or over-authorized autonomous merges | Less automation and slower completion |
| Read-only UI before mutation | Limits blast radius while identity/auth are tested | Delays visible end-to-end Git workflow |
| Resolve names/ownership before navigation | Avoids immediate migration | Delays Option A and requires up-front contract work |
| Defer non-Git projects | Avoids inventing unsafe synchronization | Product direction supports fewer project types initially |

## Open Questions

1. What exact canonical root children exist for each of `AGENT`, `CHAT`, and `USER`, and who may create, rename, delete, or write each one?
2. Is `.git` agent-writable, application-owned, or split by filesystem permissions? How are direct file tools prevented from bypassing that choice?
3. What mechanism confines writes from child processes and third-party libraries that do not use workspace services?
4. Which facts are authoritative in the worktree, local refs, remote provider, and database, and how are inconsistent states reconciled?
5. Does a binding identify a project, a materialization, a branch, a commit/revision, or all of them? Which fields are immutable at chat, assignment, and run scope?
6. What state machine governs binding, provisioning, ready, failed, revoked, stale, and deleted contexts?
7. Are concurrent runs rejected, serialized, assigned worktrees, or assigned clones? What happens after crash, cancellation, or a dirty working tree?
8. What repository features are explicitly unsupported in the first release, and how are they detected and rejected before side effects?
9. What URL, DNS, redirect, size, protocol, submodule, symlink, and resource policies apply to clone/fetch/checkout?
10. How are webhook authenticity, replay, ordering, deduplication, and installation/repository authorization enforced?
11. What is the minimum authorization unit for repository credentials—user, agent, project, repository, provider installation, or operation?
12. How are root layout, skills-path, and existing workspace data migrated without breaking current tools or published outputs?
13. What sensitivity rules govern read-only file inspection, logs, repository metadata, and cross-agent console visibility?
14. How do HTMX actions detect stale context after a switch in another tab or concurrent request?
15. What quotas, retention, garbage collection, backup, and disaster-recovery rules apply to durable workspaces and Git objects?
16. What acceptance tests prove confinement, authorization, concurrency safety, provider races, and failure recovery before mutation is enabled?

## Recommended Next Step

Do not begin Option A as currently framed. First produce a narrow security and identity decision proposal that fixes: canonical child names and ownership per workspace kind; `.git` ownership; the complete mutation choke point; versioned binding identity and lifecycle; concurrency isolation; and the initial Git/provider capability boundary.

Then validate those decisions with adversarial acceptance cases for symlink/rename escape, direct process writes, malicious repository metadata, stale HTMX actions, concurrent runs, failed provisioning, credential revocation, webhook replay, and remote race conditions. Only after those cases have enforceable answers should the first implementation slice combine read-only context/navigation with a deliberately limited mutation policy. Managed Git mutation, project-local skills, system-chat commands, and merge automation should each remain separately gated.
