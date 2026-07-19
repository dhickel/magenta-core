# Plain Path Segment ID Validation

## Topic

Central validation for ids that are composed into filesystem paths.

## Source References

- `.internal-dev/bugs/public-alpha-quality-review/bug-02-critical-security-agent-id-path-traversal/report.md`
- `src/main/java/io/mindspice/magenta2/core/util/PlainPathSegmentValidator.java`
- `src/main/java/io/mindspice/magenta2/ai/orchestration/workspaces/WorkspaceDirectoryService.java`
- `src/main/java/io/mindspice/magenta2/ai/orchestration/workspaces/WorkspaceKind.java`

## Key Takeaways

- Data-root confinement alone is not enough when an id is supposed to stay inside a fixed subtree like `workspace/agent/<id>`.
- Validate user-controlled ids and workspace keys before composing identity strings. The central validator rejects blank values, surrounding whitespace, dot-only names, `/`, `\`, Windows drive syntax, absolute paths, and percent-encoded path syntax.
- Encoded separators and encoded traversal syntax are rejected at the web boundary before key decoding. Decoded keys receive `PlainPathSegmentValidator` enforcement.
- Workspace keys (agent ids, chat ids, user workspace names) are validated with `requirePlainSegment` before descriptor construction. Unknown types, invalid keys, and missing entities map to `400` or `404`.
- Agent profile creation treats `null` id as omitted and generates a UUID, but explicit blank ids are invalid.

## Engine Relevance

Use `PlainPathSegmentValidator.requirePlainSegment(value, label)` for any future id that becomes a single filesystem path segment. Keep broader file path validation separate; this helper is intentionally for ids, not user-selected relative file paths. The validator is applied to all workspace keys before they become filesystem path segments.

## Open Questions

- Whether a future migration should detect and repair any invalid agent ids that may already exist in persisted local data.
