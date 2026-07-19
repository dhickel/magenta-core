# Topic
Direct descriptor-relative file and shell tool paths.

# Source References
- `.internal-dev/specifications/architecture.md` (`ARCH-20260719-01`)
- `src/main/java/io/mindspice/magenta2/ai/chat/tool/file/AgentFileToolService.java`
- `src/main/java/io/mindspice/magenta2/ai/chat/tool/shell/AgentShellToolService.java`
- Phase 06 validation on 2026-07-19

# Key Takeaways
- The earlier alias model in this file was superseded by the filesystem-first descriptor contract.
- In active execution context, blank or `.` selects the resolved descriptor root; ordinary paths are direct workspace-relative paths.
- `workspace/`, `root/`, `run/`, `work/`, `projects/`, scratch, and job prefixes are rejected instead of remapped.
- `outputs/` is a real reserved child. File reads may inspect it, but file mutations and shell working-directory selection reject it; output publication is its only writer.
- Tool target tracking stores the direct workspace-relative target (for example `src/main`) so `AgentsMdResolver` can layer instructions from the descriptor root without alias stripping.
- Canonical agent roots are `workspace/agent/<agentId>`, not `workspace/<agentId>` or `workspace/agent-<id>`.

# Engine Relevance
Alias compatibility can silently recreate retired filesystem identities. Tests and UI defaults must use direct relative paths and assert that retired prefixes fail closed.

# Open Questions
None for the Phase 06 contract.
