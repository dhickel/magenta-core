## API Package

This package owns Magenta's web and API entry points.

### Responsibilities
- Expose chat, agent, workspace, project, job, plan, task, workflow, output, skill, settings, and dashboard APIs through thin Spring controllers.
- Controllers parse type/key path segments for workspace routes (`/api/workspace/{type}/{id}`), delegate to services, and map domain exceptions to HTTP status codes.
- `WorkspaceController` accepts `agent`, `chat`, or `user` type path segments and exposes `GET|POST /api/workspace/user` for user workspace collection. It never accepts roots, canonical identities, or arbitrary paths.
- Keep controllers thin; services own validation and use cases; repositories own persistence.

### Change guidance
- Treat controller request and response changes as public API changes.
- For workspace, output, project, task/workflow, or job request/response changes, keep controllers thin and route selection/path policy through workspace/runtime services and centralized layout helpers.
- Execution submission forms default to the bound agent by omitting workspace type/key. Do not expose retired generic workspace, Work Area, output-route, direct-output, run-root, or filesystem-lease controls.
- Do not add `/api/work-areas`, `/api/workspaces` (generic), or `/avatar` routes.

### Validation
- Add or update controller tests for new routes, request validation, status codes, and response shapes.
- Check browser client behavior when web-facing API contracts change.
