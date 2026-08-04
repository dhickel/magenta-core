## Date
2026-08-04

## Git Commit

f388a06122af851d91b71b27c4325119d385c1ca

## Change Summary
Fix project dashboard tab layout to match the agent dashboard pattern: flat horizontal tabs with chat as a persistent accordion above the tabs.

## Files
- `src/main/java/io/mindspice/magenta2/api/web/AppNavigation.java` — Added `TAB_NAV_EXCLUDE` set (`{"chat"}`); changed default tab from `queue` to `dashboard`
- `src/main/java/io/mindspice/magenta2/api/web/ProjectConsoleFragments.java` — Added `flatTabNav` and `projectChatAccordionPanel`; replaced grouped nav with flat nav + chat accordion in `projectDetailShell`
- `src/test/java/io/mindspice/magenta2/api/web/ProjectConsoleControllerTest.java` — Updated tab assertions for flat nav, chat accordion, and dashboard default

## Behavioral Impact
- Project dashboard tabs render as a flat horizontal row (like agent dashboard) instead of grouped vertical columns
- Chat is a collapsible accordion dropdown at the top of the detail view, persistent across tab switches
- Default selected tab is Dashboard (was Queue)
- Chat route (`/projects/_detail/{id}/chat`) still works for accordion lazy-loading

## Specification Impact
None — no API or service contract changes.

## Risks
Low. CSS class `agent-chat-accordion` already exists and is used by the agent dashboard. Flat tab CSS class `orch-tabs` is the same class used by the agent dashboard.

## Follow-up Items
- Consider removing unused `groupedTabNav` and `GROUP_ORDER`/`GROUP_LABELS` if no other consumers exist
- Consider suppressing the H2 "Project Chat" header inside the accordion for a cleaner look
