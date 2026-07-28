
# AGENTS.md Specification — Implementation Research Report

> **Source:** [AGENTS.md Specification](https://agents.md/)
> **Date:** 2025-07-17
> **Status:** Research — for implementation team review

---

## 1. Specification Summary

### 1.1 What Is AGENTS.md?

AGENTS.md is a simple, open format for guiding coding agents. It is a **Markdown file** placed at the root of a repository (or in subdirectories for monorepos) that provides context and instructions to AI coding agents working on the project. The format is intentionally unopinionated — no required fields, no schema, just standard Markdown.

The core philosophy: **AGENTS.md is a README for agents.** README.md is for humans; AGENTS.md contains the extra, detailed context coding agents need — build steps, test commands, conventions, gotchas, and security considerations.

### 1.2 Format

- **Plain Markdown.** No frontmatter, no YAML, no structured schema.
- **No required fields.** Any headings, any content. The agent parses whatever text is provided.
- **Hierarchical.** Nested AGENTS.md files in subdirectories override/clarify the root one for files in that subtree.
- **The closest AGENTS.md to the edited file wins.** Explicit user chat prompts override everything.

### 1.3 Canonical Location

| Location | Scope |
|----------|-------|
| `<repo-root>/AGENTS.md` | Repository-wide instructions |
| `<repo-root>/<subdir>/AGENTS.md` | Sub-project or package-specific instructions |

For monorepos: place another AGENTS.md inside each package. Agents automatically read the nearest file in the directory tree.

### 1.4 Recommended Sections

The spec suggests covering:

- **Project overview** — what the project is, its purpose
- **Build and test commands** — how to build, run, and test
- **Code style guidelines** — conventions, patterns, preferences
- **Testing instructions** — test framework, how to run, coverage expectations
- **Security considerations** — auth patterns, secret handling, sensitive paths
- **Dev environment tips** — setup commands, tooling, common pitfalls
- **PR instructions** — title format, required checks, review process
- **Commit message guidelines** — format, conventions
- **Deployment steps** — how to deploy, environments
- **Large datasets** — where data lives, how to access
- **Gotchas** — environment-specific facts that defy reasonable assumptions

### 1.5 Resolution Model — Official Rule vs. Magenta Contract

The official AGENTS.md site documents two resolution rules that Magenta must preserve in user-visible prompt wording:

1. The closest AGENTS.md to the edited file takes precedence.
2. Explicit user chat prompts override AGENTS.md guidance.

The official site also says nested AGENTS.md files can be placed inside monorepo packages and that agents automatically read the nearest file in the directory tree. It does **not** explicitly require all ancestor AGENTS.md files to remain loaded at the same time.

Magenta intentionally uses an ancestor-retention interpretation for runtime work: it resolves all AGENTS.md files from the bound root to the active target path, orders them root-to-leaf, and tells the model that the closest layer wins only when instructions conflict. This is a Magenta project contract and documented divergence, not official spec text.

```
repo/
├── AGENTS.md              ← Always loaded (baseline)
│                            "Build: mvn test, Code style: 4-space indents"
├── src/main/java/
│   └── AGENTS.md          ← Loaded when working under src/main/java/
│                            "Use Java 17 records, package prefix: com.example"
└── src/test/java/
    └── AGENTS.md          ← Loaded when working under src/test/java/
                             "Test framework: JUnit 5, naming: *Test.java"
```

**Magenta active set when working on `src/main/java/foo/Bar.java`:**
- Root AGENTS.md → `mvn test`, 4-space indents (baseline)
- `src/main/java/AGENTS.md` → Java 17 records, package prefix (layered on top)

**Magenta active set when working on `src/test/java/foo/BarTest.java`:**
- Root AGENTS.md → `mvn test`, 4-space indents (baseline)
- `src/test/java/AGENTS.md` → JUnit 5, naming convention (layered on top)
  - `src/main/java/AGENTS.md` is **no longer active** — the agent left that subtree

**Conflict example under Magenta's contract:** If root says "indent: 4 spaces" and `src/main/java/AGENTS.md` says "indent: 2 spaces", the nested file wins for files under `src/main/java/`. Magenta still retains the root's non-conflicting build commands, PR conventions, and security guidelines.

**Dynamic active path:** As the agent uses runtime file or shell tools in different directories, Magenta refreshes the applicable AGENTS.md context for the current confined target path. Sibling nested files should not remain active after the target path moves to another sibling subtree.

### 1.6 Agent Behavior with AGENTS.md

Based on the official site plus Magenta's implementation contract:

- **Official closest precedence:** The nearest AGENTS.md to the edited file takes precedence.
- **Official user override:** Explicit user chat prompts override AGENTS.md guidance.
- **Magenta ancestor retention:** Magenta keeps non-conflicting ancestor guidance active from the bound root to the active path. This is project policy, not an official spec requirement.
- **Dynamic target tracking:** Runtime file and shell tools update the active path from their own confined target resolution so prompt/context assembly can follow real model-backed tool use.
- **Command execution:** If any active AGENTS.md lists build/test commands, the agent will attempt to run them and fix failures before finishing
- **Living document:** AGENTS.md can be updated by the agent itself as the project evolves
- **Complementary to README:** It doesn't replace README — it adds agent-specific context

### 1.7 Ecosystem Support

AGENTS.md is stewarded by the **Agentic AI Foundation** under the Linux Foundation. Supported by: OpenAI Codex, Google Jules, Cursor, Windsurf, Aider, goose, Zed, Warp, VS Code, GitHub Copilot, Devin, Augment Code, Amp, RooCode, Gemini CLI, Kilo Code, Phoenix, Semgrep, Ona, and others.

Over 60,000 open-source projects have adopted it.

---

## 2. Magenta Codebase Analysis — Current AGENTS.md Usage

### 2.1 Existing AGENTS.md Files

Magenta already has an AGENTS.md ecosystem:

| File | Purpose |
|------|---------|
| `<repo-root>/AGENTS.md` | Root project guide (~380 lines): internal-dev workflow, project guide, SimplyPages conventions, engineering style, architecture expectations, agent/tool direction, validation expectations |
| `src/main/java/io/mindspice/magenta2/core/AGENTS.md` | Package-level guide for core utilities |
| `src/main/java/io/mindspice/magenta2/ai/chat/tool/AGENTS.md` | Package-level guide for chat tools |
| `src/main/java/io/mindspice/magenta2/ai/chat/tool/orchestration/AGENTS.md` | Package-level guide for orchestration tools |
| `src/main/java/io/mindspice/magenta2/CLAUDE.md` | References `@AGENTS.md` (delegation pattern) |
| Various `CLAUDE.md` files in sub-packages | Each references `@AGENTS.md` |

The root `AGENTS.md` is comprehensive and follows the spec's recommendations — it covers project overview, build commands, code style, testing, PR conventions, engineering style, and gotchas.

### 2.2 Current Loading Pattern

Magenta programmatically resolves AGENTS.md files from the effective workspace descriptor root to the active confined tool path during execution through `AgentsMdResolver`. Resolution is confined; no starter content is generated. The existing AGENTS.md files in the Magenta repository are also consumed by external AI coding tools (Claude, Cursor, etc.) as static development documentation. Magenta has a **CLAUDE.md → AGENTS.md delegation pattern** where `CLAUDE.md` files in sub-packages contain `@AGENTS.md`, which tells Claude to read the closest AGENTS.md.

### 2.3 Relevant Magenta Architecture for AGENTS.md Loading

If we want Magenta agents to load AGENTS.md files from the projects they work on, we need to identify where in the architecture this would fit. The key insight: **Magenta is an agent host that operates on user projects, not just its own source code.**

#### A) How Magenta Accesses External Directories

Magenta's agents operate in workspace contexts via `OrchestrationTaskContext`:

```java
// src/main/java/io/mindspice/magenta2/ai/orchestration/runtime/OrchestrationTaskContext.java
public record OrchestrationTaskContext(
    String agentId, String agentName,
    String jobId, String projectId, String workspaceId,
    String hostWorkspacePath,      // Active workspace path on host filesystem
    String hostOutputPath,         // Output directory
    String hostDurableWorkspacePath,  // Durable workspace root
    String hostRunPath,            // Run-specific directory
    String hostRootPath,           // Root path
    // ...
) { }
```

The `hostWorkspacePath` (and `hostRootPath`) fields point to the project/workspace directory the agent is working in. **This is the directory where we'd look for AGENTS.md files.**

#### B) System Prompt Assembly

`PromptContextAssembler.mergeModePrompt()` builds the system prompt. If AGENTS.md content should be injected into the system prompt, this is the insertion point.

#### C) File Tools

Magenta's `AgentFileTools` already has `file_read` and `file_search` capabilities. If the model should read AGENTS.md files on demand (file-read activation pattern), these tools already support it. No new tools needed.

#### D) Tool Access Policy

`ToolAccessPolicy` controls which tools are available per interaction mode. If we wanted to restrict AGENTS.md loading to certain modes, this is where the gate would go.

### 2.4 Existing Pattern: System Prompt Loading

Magenta currently loads system prompts from files referenced in `AgentConfig`:

```java
// ExternalAiConfigLoader.java — resolves systemPrompt file path to inline text
private static AgentConfig resolveSystemPrompt(
    Path configDirectory, String agentName, AgentConfig agentConfig
) {
    Path promptPath = Path.of(agentConfig.systemPrompt());
    Path resolvedPromptPath = promptPath.isAbsolute()
        ? promptPath.normalize()
        : configDirectory.resolve(promptPath).normalize();
    // Reads file content into AgentConfig.systemPrompt()
    return new AgentConfig(agentConfig.model(), Files.readString(resolvedPromptPath), ...);
}
```

This shows a well-established pattern of loading instruction text from files at startup and baking it into the agent configuration. AGENTS.md loading would follow a similar pattern but dynamically at runtime (since the project directory varies per job/workspace).

---

## 3. What AGENTS.md Means for Magenta — Two Distinct Scenarios

There are **two separate use cases** for AGENTS.md in the Magenta context:

### Scenario A: Magenta's Own Development (Already Implemented)

Magenta already has AGENTS.md files for its own development. External coding agents (Claude, Cursor, etc.) read these when working on Magenta's codebase. **No changes needed — this is already working.**

### Scenario B: Magenta Agents Reading Project AGENTS.md (New Implementation)

When Magenta's agents work on a user's project (via orchestration jobs, workspaces, or chat with a project directory context), the agents should read and follow the project's AGENTS.md. This is **not yet implemented** and is the subject of this report.

**This is the core question:** Should Magenta agents automatically discover and load AGENTS.md files from the projects they're working on?

---

## 4. Implementation Plan — AGENTS.md Loading for Magenta Agents

### 4.1 Design Decisions

| Decision | Recommendation | Rationale |
|----------|---------------|-----------|
| **Load method** | Runtime prompt/context injection of resolved bound-root-to-target layers | Magenta implementation policy; not an official AGENTS.md loading mandate. |
| **Root baseline** | Include root AGENTS.md when it is inside the bound root and applicable to the active target path | Root conventions (build commands, code style, PR format) remain useful under Magenta's ancestor-retention policy. |
| **Nested layering** | Resolve AGENTS.md files from the bound root through the current file/directory target; inject as runtime prompt context | Magenta contract: retain ancestors while making the closest layer authoritative on conflicts |
| **Dynamic trigger** | Re-resolve when runtime file or shell tools target a different confined path than the last resolution | Aligns prompt context with real model-backed tool targets instead of a static workspace root |
| **Conflict resolution** | Nested instructions override root for the same topic; non-conflicting instructions from both levels remain active | Magenta divergence from official text; official site only states closest precedence and user override |
| **Scope** | Only project-level AGENTS.md (not user-level) | AGENTS.md is a project convention. User-level preferences belong in Magenta's own config. |
| **Trust** | Always load if the workspace is configured | Workspaces are explicitly set up by the user — implicit trust |
| **Size limit** | Root: ~4,000 tokens; nested: ~2,000 tokens each | Keeps total AGENTS.md budget manageable; root provides the essentials, nested files are targeted |
| **Caching** | Cache resolved AGENTS.md content per path for session | Don't re-read filesystem on every turn; invalidate if file mtime changes |

### 4.2 New Components

| Component | Package | Responsibility |
|-----------|---------|----------------|
| `AgentsMdResolver` | `ai.skill` | Given a file path, walk up the directory tree and return the closest AGENTS.md (or null). Caches results. |
| `AgentsMdRootLoader` | `ai.skill` | Load the root AGENTS.md from the workspace directory at session start for system prompt baseline |
| `AgentsMdContext` | `ai.skill` | Immutable value object: path, content, depth relative to workspace root |
| `AgentsMdLayer` | `ai.skill` | Tracks the currently active nested AGENTS.md; detects when the working directory changes and triggers load/unload |

### 4.3 `AgentsMdResolver` Design

Resolves the **single closest** AGENTS.md for a given file path, not all ancestors:

```java
package io.mindspice.magenta2.ai.skill;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

public class AgentsMdResolver {
    private static final String AGENTS_MD = "AGENTS.md";
    private static final int MAX_WALK_UP = 20;

    private final Path workspaceRoot;
    private final Map<Path, Optional<AgentsMdContext>> cache = new ConcurrentHashMap<>();

    public AgentsMdResolver(Path workspaceRoot) {
        this.workspaceRoot = workspaceRoot.toAbsolutePath().normalize();
    }

    /**
     * Resolves the closest AGENTS.md for a given file path by walking up
     * from the file's parent directory toward the workspace root.
     * Returns empty if no AGENTS.md exists in any ancestor up to the root.
     * The root AGENTS.md itself is resolved separately by AgentsMdRootLoader.
     */
    public Optional<AgentsMdContext> resolveClosest(Path filePath) {
        Path dir = filePath.toAbsolutePath().normalize().getParent();
        return cache.computeIfAbsent(dir, this::walkUp);
    }

    private Optional<AgentsMdContext> walkUp(Path startDir) {
        Path current = startDir;
        int depth = 0;
        while (current != null && depth < MAX_WALK_UP) {
            // Don't walk above the workspace root
            if (!current.startsWith(workspaceRoot) && !current.equals(workspaceRoot)) {
                break;
            }
            Path agentsFile = current.resolve(AGENTS_MD);
            if (Files.isRegularFile(agentsFile)) {
                // Don't return the root AGENTS.md — that's handled separately
                if (!current.equals(workspaceRoot)) {
                    return Optional.of(new AgentsMdContext(
                        agentsFile,
                        readContent(agentsFile),
                        depth
                    ));
                }
                // Root exists but we're looking for a closer nested one
                return Optional.empty();
            }
            Path parent = current.getParent();
            if (parent == null || parent.equals(current)) break;
            current = parent;
            depth++;
        }
        return Optional.empty();
    }

    private String readContent(Path path) {
        try {
            String content = Files.readString(path);
            // Cap nested content at ~2,000 tokens
            if (content.length() > 8_000) {
                content = content.substring(0, 8_000)
                    + "\n\n[AGENTS.md content truncated — exceeds nested context budget]";
            }
            return content;
        } catch (Exception e) {
            return null;
        }
    }

    /** Clear cache — call when files may have changed */
    public void invalidate() { cache.clear(); }
}
```

### 4.4 `AgentsMdLayer` — Tracking Active Nested Context

This component tracks which nested AGENTS.md is currently active and detects when the agent's working directory changes:

```java
package io.mindspice.magenta2.ai.skill;

public class AgentsMdLayer {
    private final AgentsMdResolver resolver;
    private AgentsMdContext activeNested;  // null = no nested AGENTS.md active

    /**
     * Called when the agent touches a file in a given directory.
     * Returns the nested AGENTS.md content if it changed, or null if no change.
     */
    public String updateForPath(Path filePath) {
        AgentsMdContext resolved = resolver.resolveClosest(filePath).orElse(null);

        // Same nested AGENTS.md as before — no change needed
        if (activeNested == null && resolved == null) return null;
        if (activeNested != null && resolved != null
            && activeNested.path().equals(resolved.path())) return null;

        // Different nested AGENTS.md (or entering/leaving a nested context)
        AgentsMdContext previous = activeNested;
        activeNested = resolved;

        if (resolved == null) {
            // Left a nested subtree — unload the nested context
            return null; // Signal to remove nested context notice
        }

        // Entered a new nested subtree — return the new content
        return resolved.content();
    }

    public AgentsMdContext active() { return activeNested; }
    public boolean hasActiveNested() { return activeNested != null; }
}
```

### 4.5 Injection Points — Two-Tier Architecture

**Tier 1 — Root baseline (system prompt, loaded once per session):**

The root AGENTS.md is loaded at workspace initialization and appended to the system prompt. It stays there for the entire session.

```java
// In PromptContextAssembler.mergeModePrompt():
public String mergeModePrompt(PlanMode mode, String conversationId) {
    // ... existing logic builds `result` ...

    // Append root AGENTS.md baseline (always present)
    String rootAgentsMd = agentsMdRootLoader.getRootContent();
    if (StringUtils.hasText(rootAgentsMd) && StringUtils.hasText(result)) {
        result = result.stripTrailing() + "\n\n" + rootAgentsMd;
    }

    return result;
}
```

**Tier 2 — Nested layering (context notices, loaded/unloaded dynamically):**

When the agent uses file tools that target a new directory subtree, `AgentsMdLayer` detects the change and the nested AGENTS.md is injected as a system message in the conversation (not the system prompt). When the agent leaves that subtree, a notice is injected to mark the transition.

```java
// In ChatService, after tool execution that touches files:
private void updateAgentsMdContext(String conversationId, Path touchedFilePath) {
    if (agentsMdLayer == null || touchedFilePath == null) return;

    String nestedContent = agentsMdLayer.updateForPath(touchedFilePath);
    if (nestedContent != null) {
        // Entered a new nested AGENTS.md subtree
        String notice = String.format(
            "[Active project context updated — now working under %s]\n\n%s",
            agentsMdLayer.active().relativePath(),
            nestedContent
        );
        chatMemoryRepository.addMessage(conversationId, new SystemMessage(notice));
    } else if (!agentsMdLayer.hasActiveNested()) {
        // Left a nested subtree, back to root-only
        chatMemoryRepository.addMessage(conversationId,
            new SystemMessage("[Active project context — back to project root conventions]"));
    }
}
```

### 4.6 Trigger Points for Dynamic Resolution

The nested AGENTS.md should be re-resolved when the agent performs file operations that indicate a working directory change:

| Trigger | When to resolve |
|---------|----------------|
| `file_read` on a path in a new subtree | After tool result is returned |
| `file_write` on a path in a new subtree | Before the write (so conventions are known) |
| `file_search` returning results in a new subtree | After results are shown |
| `file_list` in a directory different from current | After listing |
| User mention of a specific file/directory | On next turn assembly |

**Optimization:** Don't re-resolve on every file touch — only when the closest AGENTS.md for the new path would be *different* from the currently active one. The `AgentsMdLayer.updateForPath()` method already handles this by comparing resolved paths.

### 4.7 Context Protection

- **Root AGENTS.md:** Part of the system prompt → naturally protected from compaction (compaction only touches stored messages)
- **Nested AGENTS.md:** Injected as system messages in conversation history → needs protection from compaction. Mark nested AGENTS.md messages with a prefix (e.g., `[[AGENTS_MD_NESTED]]`) so `ContextManagementAdvisor` can identify and exempt them from pruning, similar to how it already protects compaction notices and summaries.

```java
// In ContextManagementAdvisor:
private static final String NESTED_AGENTS_MD_PREFIX = "[[AGENTS_MD_NESTED]]";

private boolean isNestedAgentsMd(Message message) {
    return message instanceof SystemMessage
        && message.getText() != null
        && message.getText().startsWith(NESTED_AGENTS_MD_PREFIX);
}

// In compact(), filter out nested AGENTS.md from compactable messages:
List<Message> compactable = storedMessages.stream()
    .filter(message -> !isHiddenSummary(message))
    .filter(message -> !isCompactionNotice(message))
    .filter(message -> !isNestedAgentsMd(message))  // NEW
    .toList();
```

### 4.8 Agent Config Changes

Add AGENTS.md loading configuration to `AgentConfig`:

```java
public record AgentConfig(
    String model,
    String systemPrompt,
    List<String> approvedTools,
    List<String> allowedShellCommands,
    Boolean skillsEnabled,
    List<String> skillsDirectories,
    Boolean skillsTrustProjectSkills,
    // NEW:
    @JsonProperty("agentsMdEnabled") Boolean agentsMdEnabled,
    @JsonProperty("agentsMdMaxTokens") Integer agentsMdMaxTokens
) {
    public boolean isAgentsMdEnabled() {
        return agentsMdEnabled == null || agentsMdEnabled; // enabled by default
    }
    public int agentsMdMaxTokens() {
        return agentsMdMaxTokens == null ? 8000 : agentsMdMaxTokens;
    }
}
```

---

## 5. Comparison: AGENTS.md vs. Agent Skills

| Aspect | AGENTS.md | Agent Skills |
|--------|-----------|--------------|
| **Format** | Plain Markdown, no schema | YAML frontmatter + Markdown body in SKILL.md |
| **Location** | Project root, subdirectories | `.agents/skills/`, `~/.agents/skills/`, client-specific dirs |
| **Loading** | Official site documents nearest-file precedence; Magenta loads bound-root-to-target ancestor layers dynamically | Progressive disclosure (catalog → body → resources) |
| **Scope** | Project-wide conventions, build commands, style | Task-specific workflows, domain expertise |
| **Activation** | Always active | On-demand when task matches description |
| **Granularity** | One per project/sub-project | Many per project (different skills for different tasks) |
| **Portability** | Project-specific | Cross-project reusable |
| **Token cost** | Paid upfront (system prompt) | Paid on activation only |
| **Versioning** | Lives in version control with project | Can be versioned independently |
| **Ecosystem** | 60K+ open-source projects | Growing ecosystem, Anthropic-originated |

**They are complementary, not competing:**
- AGENTS.md = "Here's how this project works" (always-on context)
- Agent Skills = "Here's how to perform task X" (on-demand expertise)

---

## 6. Senior Analysis

### 6.1 Should Magenta Implement AGENTS.md Loading?

**Yes, with caveats.** The case is strong:

1. **It's a de facto standard.** With 60K+ open-source projects using it and support from virtually every major coding agent, AGENTS.md is the expected convention. If Magenta agents work on projects that have AGENTS.md files, they should read them.

2. **It's trivially simple to implement.** No parsing, no schema, no validation. Just read a Markdown file and inject it into the system prompt. The entire implementation is ~100 lines of Java.

3. **It aligns with Magenta's mission.** Magenta agents operate on user projects. Following the project's own conventions (as stated in AGENTS.md) is table-stakes behavior for an effective agent.

4. **It's low risk.** AGENTS.md content goes into the system prompt, which is naturally protected from compaction. The size cap prevents context budget blow-up.

### 6.2 Implementation Concerns

1. **Context budget.** The root AGENTS.md lives in the system prompt for every turn. Nested AGENTS.md files are injected as conversation messages only when the agent is working in their subtree — they're unloaded when the agent leaves. A 4,000-token root cap + 2,000-token nested cap keeps the budget manageable even for large monorepos.

2. **Trust boundary.** Unlike the Agent Skills spec (which explicitly mentions trust gates for project-level skills), AGENTS.md loading has no trust model. Since Magenta agents already operate in user-configured workspaces, this is acceptable — the user explicitly set up the workspace.

3. **Conflicting instructions.** AGENTS.md might contain instructions that conflict with Magenta's own agent instructions (e.g., "never use tool X" when the agent needs tool X). The official site says explicit user chat prompts override AGENTS.md guidance. Magenta prompt wording should also keep core runtime instructions above project guidance.

4. **Layering semantics.** The official site says the closest AGENTS.md wins, but does not explicitly require ancestor retention. Magenta deliberately resolves ancestor layers from the bound root to the active target path and treats closest-wins as conflict precedence within that retained set. Keep that wording clear in specs, docs, and prompts.

5. **Dynamic load/unload granularity.** Re-resolving on every file touch would be chatty. The `AgentsMdLayer` approach only injects context when the *closest AGENTS.md changes* — which typically only happens when crossing major directory boundaries (e.g., `src/main/` → `src/test/`). Within the same subtree, no changes occur.

### 6.3 Interaction with Agent Skills

When both AGENTS.md and Agent Skills are implemented, the system prompt structure would be:

```
[CORE SYSTEM PROMPT — Magenta's agent instructions]
[AGENTS.MD CONTENT — Project conventions, build commands, gotchas]
[SKILL CATALOG — Available skills with names + descriptions]
[MODE-SPECIFIC INSTRUCTIONS — Plan/Task runtime state]
[WORKTYPE PROFILE — If applicable]
```

This ordering is intentional:
- Core instructions first (highest priority)
- Project conventions next (always relevant)
- Skill catalog next (progressive disclosure tier 1)
- Mode-specific instructions last (overrides for plan/task modes)

### 6.4 Implementation Priority

AGENTS.md loading is **much simpler** than Agent Skills implementation. It can be implemented independently and should probably come first:

1. **Phase 1 — Basic AGENTS.md loading:**
   - `AgentsMdLoader` + `AgentsMdContext`
   - Injection into `PromptContextAssembler.mergeModePrompt()`
   - Agent config toggle (`agentsMdEnabled`)
   - Size cap and caching

2. **Phase 2 — Hierarchical loading:**
   - Walk up directory tree, merge nested AGENTS.md files
   - Precedence markers for model clarity

3. **Phase 3 — UI/observability:**
   - Display loaded AGENTS.md status in chat UI
   - Log which AGENTS.md files were loaded

---

## 7. Contract Requirements for Specification Adherence

The AGENTS.md specification is intentionally minimal. There are no "MUST" requirements — it's just "create a Markdown file." However, for practical interoperability:

### 7.1 What We SHOULD Do

- [ ] Read AGENTS.md from the workspace descriptor root when files exist
- [ ] Read nested AGENTS.md files along the active confined tool path
- [ ] Follow Magenta's ancestor-retention policy: closest wins only on conflict
- [ ] Inject AGENTS.md content into the agent's context (system prompt)
- [ ] Not require any specific format, headings, or sections
- [ ] Allow AGENTS.md to be updated between sessions (re-read on new sessions)
- [ ] Provide a toggle to disable AGENTS.md loading per agent
- [ ] Respect explicit user instructions over AGENTS.md content

### 7.2 What We COULD Do (Ecosystem Alignment)

- [ ] Support `AGENT.md` as a fallback filename (backward compatibility with older projects)
- [ ] Display which AGENTS.md files are active in the chat UI
- [ ] Allow the agent to update AGENTS.md when conventions change
- [ ] Magenta-specific: retain ancestor AGENTS.md layers from bound root to active runtime target, with sibling layers dropped when the target path moves

### 7.3 What We Should NOT Do

- [ ] Generate starter `AGENTS.md` files — Magenta never creates AGENTS.md content
- [ ] Require any specific frontmatter or metadata format
- [ ] Fail if AGENTS.md is missing — it's optional
- [ ] Override explicit user instructions with AGENTS.md content
- [ ] Parse or validate AGENTS.md content structure
- [ ] Resolve AGENTS.md outside the bound workspace descriptor root

---

## 8. Magenta's Own AGENTS.md — Current vs. Spec Alignment

Magenta's root `AGENTS.md` already aligns well with the specification:

| Spec Recommendation | Magenta's AGENTS.md | Status |
|--------------------|--------------------|--------|
| Project overview | ✅ Magenta Project Guide section | Present |
| Build and test commands | ✅ Referenced in internal-dev workflow | Present (via `.internal-dev/`) |
| Code style guidelines | ✅ Engineering style, HTMX policy, SimplyPages conventions | Present |
| Testing instructions | ✅ Validation expectations section | Present |
| Security considerations | ⚠️ Implicit in agent/tool direction | Could be explicit |
| Dev environment tips | ⚠️ Not explicit | Could be added |
| PR instructions | ✅ `.internal-dev` workflow covers commit practices | Present |
| Gotchas | ✅ Package guides, SimplyPages layout research policy | Present |

**Recommendation:** Add an explicit "Security considerations" section and "Dev environment setup" section to Magenta's root AGENTS.md for completeness.

---

## 9. References

- [AGENTS.md Homepage](https://agents.md/)
- [AGENTS.md GitHub Examples](https://github.com/search?q=path%3AAGENTS.md) — 60K+ examples
- [Agentic AI Foundation (Linux Foundation)](https://agentic-ai.org/)
- [Mastra AGENTS.md Guide](https://mastra.ai/docs/agentsmd) — One agent's approach to loading
- [Fully-Hacks AGENTS.md Guide](https://fully-hacks.pages.dev/posts/agents-md/) — Community guide
