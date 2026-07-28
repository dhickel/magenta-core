# SUPERSEDED — This knowledge file is archived and no longer reflects the current codebase.

---
schema_version: 1
document_type: knowledge
status: active
created: 2026-05-25
owner: codex
---

# Avatar Chat Sticky Resize Container

## Topic

Avatar chat sticky positioning and corner resizing inside the SimplyPages shell.

## Source References

- `.internal-dev/plans/.archive/avatar-chat-corner-resize-sticky-repair/`
- `src/main/resources/static/css/avatar-dashboard.css`
- `src/main/resources/static/js/avatar-shell.js`
- `/home/hickelpickle/Code/Java/cannasite/java-html-framework/simplypages/src/main/resources/static/css/framework.css`
- `/home/hickelpickle/Code/Java/cannasite/java-html-framework/simplypages/src/main/java/io/mindspice/simplypages/layout/Page.java`

## Key Takeaways

- Sticky positioning fails when an ancestor becomes a non-scrolling overflow container while the document remains the real scroll path.
- For `/avatar`, the final repair keeps `.content-wrapper` and Avatar shell ancestors at `overflow: visible` so `.avatar-shell-rail { position: sticky; top: 1.25rem; }` resolves against document scrolling.
- The SimplyPages sticky-sidebar pattern is still useful as a model: place sticky aside and main content in the same normal-flow layout, give the sticky aside a viewport-based max height, and keep the aside locally scrollable when its own content overflows.
- Avoid a full-height divider for the Avatar chat rail. The accepted interaction is a bottom-right corner handle inside the chat panel.
- Horizontal chat resize must update the grid column variable so the dashboard column claims the remaining width. Native CSS `resize` is not enough because it does not coordinate dashboard width.
- Vertical chat resize must clamp against the chat panel's current viewport top, not a fixed `100vh` value from page load. Otherwise a panel lower on the page can push its handle below the viewport.
- Desktop-only resize state should be mirrored in both CSS and JavaScript. The handle should be `hidden`, untabbable, `display:none`, and `pointer-events:none` below the desktop breakpoint.

## Engine Relevance

- Reuse this guidance for Avatar dashboard sticky rails, side panels, and any future persistent assistant panel that should follow document scroll.
- Before changing scroll containment, validate the actual scroll target with Playwright geometry and computed ancestor overflow.

## Open Questions

- None.
