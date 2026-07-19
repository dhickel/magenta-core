## Web API Package

This package owns HTTP and web-facing entry points.

### Responsibilities
- Expose chat REST endpoints, SSE streaming endpoints, command endpoints, context usage payloads, structured tool activity updates, and simple web routes.
- Expose chat plan state, queued planning questions/actions, approval/continue actions, validation criteria, deliverables, validation feedback, and execution evidence.
- Treat `/chat` planning routes as anonymous, session-local, non-saved planning. They must not create saved `/plans` definitions or submit anonymous plans to agents.
- Treat `/api/plans/*planning-chat*` as saved plan chat routes. These routes are plan-scoped and must not use `/api/chat`, `ai_chat_session_metadata`, or chat session list architecture.
- Expose runtime settings, agent profile, typed workspace/file explorer (under `/api/workspace/{type}/{id}` for agent/chat/user kinds and `GET|POST /api/workspace/user`), job, assignment, inbox, schedule, event-reaction, and agent side-panel chat APIs as thin orchestration entry points.
- Expose assignment lifecycle controls, including guarded queue cleanup, retained terminal history, history purge, and read-only audit transcript fragments, through thin orchestration entry points.
- Expose operational dashboard summary and output artifact query APIs as thin read models for orchestration UI pages.
- Expose Agent Skills management and agent-assignment endpoints as thin service adapters when that surface is implemented.
- Keep inherited shell compatibility resources local to the web layer when a shell asset reference cannot be removed directly.
- Own the current open-alpha HTTP posture: Magenta does not currently enforce built-in HTTP auth, authorization, or CSRF checks at the web/API layer; unsafe mutation/control routes must rely on explicit controller/service validation and domain ownership guards.
- Keep controllers thin and delegate behavior to services.
- Keep HTTP status handling clear and local to the web layer.

### Change guidance
- Treat controller request and response changes as public API changes.
- For workspace, output, project, task/workflow, or job request/response changes, keep controllers thin and route selection/path policy through workspace/runtime services and centralized layout helpers.
- Execution submission forms default to the bound agent by omitting workspace type/key. Do not expose retired generic workspace, Work Area, output-route, direct-output, run-root, or filesystem-lease controls.
- Agent Skills MVP browser UX should manage the root `skills/` repository and agent assignment only. Do not imply project-local/user scope loading, layered assignment, or browser-driven script execution before those deferred capabilities are accepted and implemented.
- Public plan/task/workflow run controls submit saved definitions to agent assignments; direct model-backed execution stays internal/test-only when needed.
- Public task and workflow run stream routes acknowledge queued assignment submission instead of streaming inline model execution.
- Public operational job APIs use `JobDefinition` records, allow empty `DRAFT` jobs, and expose job item routes separately from run routes.
- Workspace file explorer APIs must keep path traversal, symlink, text-edit, download-size, output-subtree, and active assignment/output target guards in services rather than duplicating filesystem policy in controllers.
- For SimplyPages-facing web surfaces, prefer reusable components/modules instead of one-off markup patterns.
- For planned skills surfaces, use reusable HTMX-first fragments for list/detail/editor/assignment flows and keep JavaScript narrow to clear path-of-least-resistance interactions.
- If UI behavior or structure is used in multiple places and is more than bare functionality, promote it into a reusable component/module.
- For similar views, prefer shared render structures and slot-key based reuse over duplicated near-identical templates.
- `/avatar` routes, Avatar dashboards, and Avatar layout editing are removed. Do not reintroduce them.
- Preserve HTMX-compatible error rendering for browser mutation routes without reintroducing stale auth/CSRF shell helpers unless the security posture changes deliberately.
- Do not put chat, persistence, or orchestration logic in controllers.
- Keep command parsing small and explicit.
- Execution submission forms default to the bound agent by omitting workspace type/key. Do not expose retired generic workspace, Work Area, output-route, direct-output, run-root, or filesystem-lease controls.
- Keep this guide updated when web routes, API contracts, or controller conventions change.

### Validation
- Add or update controller tests for new routes, request validation, status codes, and response shapes.
- Check browser client behavior when web-facing API contracts change.
- For any web/UI change, capture Playwright screenshots of affected screens for agent-side visual review/debugging and verify non-breaking behavior, layout integrity, and sound UI/UX patterns before sign-off.
- For agent Files tab UI changes, Playwright validation must capture normal and interaction states on desktop and mobile, compare against `/dashboard`, `/agents`, and the SimplyPages demo patterns, and explicitly report alignment, dead space, density, hierarchy, wrapping, and control affordance quality.
- Treat screenshots as internal validation artifacts first; use them in user communication when they help localize or explain UI issues.
