# Product Model Rectification — Exploration, Specification, and Handoff

## Date

2026-08-09

## Git Commit

d6ccdc782eb71b07ed1be7da7a4e1297fff53f20

## Change Summary

Formalized the user's product braindump as the accepted target model and prepared the rectification sprint planning input:

1. **New specification** `.internal-dev/specifications/product-model.md` (registered in `index.md`): terminology contract (workspace/task/plan/workflow/project/agent/job), workspace and filesystem model (`chats/` per-chat folders with `outputs/`; agent home workspaces with `home/`, `scripts/`, `skills/`, `agents.md` layered onto projects; project contained workspaces with root `agents.md` + `skills/` + `scripts/` and inner `project/`, orchestrating-agent root above `project/`), task model (schema in/out + criteria, labels), workflow model (node-based task composition, workflow-as-task), non-cron scheduling (future date / daily / interval), project model (owner default `magenta` replaceable, project-assigned run authority, root-agent capability tier, per-project work tracking), and dashboard `agents.md`/skills editing UI. Includes `DECISION-20260809-01` and a conflicts-with-current-state table.
2. **Exploration** (5 deepseek-v4-flash:max read-only agents): mapped terminology conflation (plan×4, task×3, job×3 meanings; `plan_definitions` kind conflation; parallel discriminators; 4 definition/run snapshot pairs; three task-creation pathways), filesystem layout (pre-deletion `workspace/agent|chat|user` authority, `chats/` absent, `.skills` vs `skills/`, no per-agent `agents.md`, projects with no filesystem root, materializations), scheduling (cron-only `schedule_definitions` CRON|ONE_TIME + `job_recurrences`, cron-only agent tools, no interval/daily, no repeatable prompt), project/authority (owner_agent_id presentation-only, membership enforced only when projectId supplied, no root-agent concept, no task labels, partial per-project tracking), UI (wording clash catalog, duplicated editors/explorer/chat transports, available primitives, no agents.md/skills editing surface), and spec/docs/schema drift (orphaned specs, no drift record for the 2026-08-04 core extraction, explicit conflicting sentences, doc duplication, schema cruft).
3. **Evidence review** `.internal-dev/reviews/2026-08-09-product-rectification-exploration.md`: consolidated exploration findings, risk assessment, recommendations, follow-ups.
4. **Handoff** `.internal-dev/handoffs/2026-08-09-product-model-rectification.md` (synthesized by deepseek-v4-pro:max): baseline context (branch `core` mid-refactor; full product on `master` and `sprint/20260729-163244-project-dashboard`), recommended branch strategy (Option A: rebuild on `core`, salvage from sprint branch) flagged for user confirmation, gap map per domain, settled decisions, 21 open questions with recommended defaults, six workstreams (WS-0 baseline/spec supersession, WS-1 terminology, WS-2 filesystem/workspace, WS-3 scheduling, WS-4 project/authority, WS-5 UI, WS-6 docs/schema reconciliation), spec supersession and cruft lists, risks, validation expectations, recovery commands.
5. **Knowledge** `core-extraction-branch-topology.md`: the working-tree branch topology gotcha for future agents.

## Files

- `.internal-dev/specifications/product-model.md` (new)
- `.internal-dev/specifications/index.md` (updated: new file row)
- `.internal-dev/reviews/2026-08-09-product-rectification-exploration.md` (new)
- `.internal-dev/handoffs/2026-08-09-product-model-rectification.md` (new)
- `.internal-dev/knowledge/core-extraction-branch-topology.md` (new)
- `.internal-dev/changelogs/2026-08-09-product-model-rectification-preparation.md` (this file)

## Behavioral Impact

None — no product code changed. This session produced planning artifacts only. The rectification sprint (not yet planned/approved) will implement the target model.

## Specification Impact

New target-model specification `product-model.md` created and registered in the index; it intentionally conflicts with current living specs (e.g., "projects have no filesystem root authority", closed three-kind workspace model, cron scheduling) and supersedes them for rectification scope via `DECISION-20260809-01`. The handoff's Spec Supersession section lists the exact entries to update during the rectification sprint, including a to-be-recorded drift entry for the 2026-08-04 core extraction.

## Risks

- The handoff's branch strategy (Option A) and four confirmation items are pending user confirmation before planning starts; choosing another base invalidates phase detail.
- The spec corpus describes the deleted master-era product; specs must be superseded explicitly during rectification, not silently overwritten.
- The current working tree cannot run the full product; all implementation evidence comes from other branches.

## Follow-up Items

- User confirms base branch and the four confirmation items (base, extraction-sprint disposition, schema baseline, migration infrastructure).
- Run the sprint-planner workflow on the handoff (brainstorm/ironout/advanceplan) to produce the rectification sprint plan.
- Record `DRIFT-20260804-01` in `architecture.md` for the core extraction (part of WS-0).
