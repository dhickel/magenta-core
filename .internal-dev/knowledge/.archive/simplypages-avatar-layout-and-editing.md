# SUPERSEDED — This knowledge file is archived and no longer reflects the current codebase.

---
document_type: knowledge
status: active
created: 2026-05-23
---

# Topic

SimplyPages Avatar layout and editing patterns.

## Source References

- `/home/hickelpickle/Code/Java/cannasite/java-html-framework/docs/getting-started/02-dynamic-pages-with-slotkey-rendercontext.md`
- `/home/hickelpickle/Code/Java/cannasite/java-html-framework/docs/getting-started/03-editing-system-first-implementation.md`
- `/home/hickelpickle/Code/Java/cannasite/java-html-framework/docs/core/03-template-rendercontext-slotkey-reference.md`
- `/home/hickelpickle/Code/Java/cannasite/java-html-framework/docs/reference/editing-api-reference.md`
- `/home/hickelpickle/Code/Java/cannasite/java-html-framework/demo/src/main/java/io/mindspice/demo/EditingDemoController.java`
- `.internal-dev/specifications/web.md`
- `.internal-dev/specifications/simplypages.md`
- Production Avatar UI files under `src/main/java/io/mindspice/magenta2/api/web/` and `src/main/resources/static/css/avatar-dashboard.css`

## Key Takeaways

- Use SimplyPages `Row` and `Column.create().withWidth(...)` for 12-column dashboard composition. Presets such as 3, 4, 6, 8, and 12 are good fast choices, but the layout model may support any `1..12` width when the row still fits.
- Use in-place row/module controls for layout operations. The SimplyPages editing demo renders rows and modules as the real page structure, then places add-module and insert-row controls around that structure.
- Use `EditableModule.wrap(...)` and `EditModalBuilder` for module-specific editing where appropriate, but do not put layout positioning in a separate modal-only editor.
- Match the demo's visual hierarchy: the module/card content stays primary, edit/detail/delete controls are tiny top-corner decorators, `add-module-section` is a centered dashed affordance, and `insert-row-section` is a quiet separator. Large row headers, stacked movement buttons, and widget-internal resize panels are failure patterns for production `/avatar` edit mode.
- Use HTMX and OOB swaps for save/delete/add/move responses so the dashboard grid, modal container, and status fragments can update without a full page reload.
- Use `Template`, `SlotKey`, and `RenderContext` when a stable component structure is reused across requests. Do not share mutable component instances across concurrent requests.
- A scratch layout page can be useful for planning and Playwright experiments, but it is not a source of truth. Extract stable lessons into production components or this knowledge file.

## Engine Relevance

Avatar dashboard work must be treated as operational UI, not a landing page. Agents should compare screenshots to `/dashboard` and `/agents`, inspect SimplyPages docs/demos before changing UI, and actively critique practical layout quality.

For `/avatar`, a good layout has balanced width usage, coherent first-viewport density, aligned rows, clear hierarchy, compact action areas, no excessive dead space, no clipped controls, and responsive stacking without horizontal overflow.

In-place edit mode should decorate the real dashboard:

- row controls for move up/down and safe row delete as secondary controls near the row-level add-module affordance;
- centered add-widget controls and low-emphasis insert-row separators modeled after `.add-module-section` and `.insert-row-section`;
- widget top-corner controls for move left/right/up/down, remove, refresh, and open detail without consuming the widget content area;
- width changes from a compact anchored picker near the width control, with preset buttons plus an optional custom `n/12` entry that closes on outside click or after apply;
- module detail modal/drawer for content-specific iteration only.

Empty rows are a special case. They should not render as full blank dashboard rows in normal mode, and in edit mode they should collapse to a compact insertion affordance such as `.avatar-empty-row-insert`. The empty-row affordance may include add-widget and safe row-delete controls, but it must not create a large dead band or make editor chrome more prominent than real dashboard content.

Add-widget selection should be focused. Prefer a modal/drawer/local picker in the shared edit container over a full-width inline block appended below a long grid. The picker should describe available widgets, show used widgets as disabled, keep 12-column width choice clear, and close through the existing shared edit container/OOB refresh pattern.

Avatar chat can borrow hierarchy from `/chat` without importing the full browser chat client: title, session/model/status chips, bounded transcript, and a clear composer are enough for the dashboard surface. Keep the JavaScript narrow and leave standard widget CRUD/layout actions to HTMX.

List-heavy widgets should have bounded visual bodies and summary text when they render only a subset of available items. Do not depend on a clean local database for visual quality.

Validation must compare `/avatar?edit=true` against the live SimplyPages editing demo when layout editing is touched. A pass requires screenshot review for practical UI quality: no oversized editor chrome, no modal-only layout workflow, no stranded columns or massive gaps, no overlapped/clipped controls, and no mobile horizontal overflow.

## Open Questions

- Whether SimplyPages should grow a reusable first-class dashboard decorator component if this pattern repeats across more Magenta pages.
- Whether the Avatar scratch page should remain as an internal route after the refactor or be removed once production components stabilize.
