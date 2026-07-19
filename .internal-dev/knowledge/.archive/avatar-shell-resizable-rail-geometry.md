# SUPERSEDED — This knowledge file is archived and no longer reflects the current codebase.

# Topic

Avatar shell resizable rail geometry.

## Source References

- `.internal-dev/plans/.archive/avatar-chat-left-resizable/handoff-report.md`
- `src/main/java/io/mindspice/magenta2/api/web/AvatarDashboardComponents.java`
- `src/main/resources/static/css/avatar-dashboard.css`
- `src/main/resources/static/js/avatar-shell.js`
- `docs/technical/avatar-dashboard-fragments.md`

## Key Takeaways

- Resizable shell rails must measure pointer position in the coordinate space of the grid or shell that owns the columns. Viewport-edge math breaks when a shell is centered, capped, zoomed, or later moved from right rail to left rail.
- For a left rail, compute width as `pointer.clientX - grid.getBoundingClientRect().left`, then clamp against the same grid's width and the required main-content budget.
- Keep JavaScript rail bounds aligned with CSS grid bounds. If CSS uses `minmax(22.85rem, var(--avatar-chat-rail-width))`, JavaScript persistence should not store a lower value that the browser cannot visibly render.
- Treat click and drag as separate interactions. Do not apply or persist a resize until pointer movement crosses a small threshold.
- Sticky behavior belongs on the rail container when the visual card sits inside a dashboard grid. A sticky child inside a content-sized grid item may not have enough containing height to follow long dashboard scrolling.

## Engine Relevance

Use this note before changing Avatar shell geometry, side rails, split panes, or similar dashboard layouts. The source of truth for resize math should be the rendered container that owns the layout columns, not `window.innerWidth`.

## Open Questions

- Whether future SimplyPages dashboard shells should expose a reusable split-pane helper for rail sizing, sticky containment, and persisted browser-local width.
