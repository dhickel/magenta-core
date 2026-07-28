# Topic

SimplyPages UI refactor patterns for Magenta web surfaces.

# Source References

- `.internal-dev/reviews/simplypages-refactor.md`
- `/home/dhickel/Code/Java/SimplyPages/docs/core/01-components-htmltag-and-module-lifecycle.md`
- `/home/dhickel/Code/Java/SimplyPages/docs/patterns/03-htmx-endpoint-and-swap-patterns.md`
- `/home/dhickel/Code/Java/SimplyPages/docs/security/01-security-boundaries-and-safe-rendering.md`
- `src/main/java/io/mindspice/magenta2/api/web/FrontendController.java`

# Key Takeaways

Use `ShellTemplate.renderWithContent(...)` with request-scoped SimplyPages component trees for page routes instead of controller string templates. Keep stable page chrome in shell templates and compose pages from `Div`, `Page`, `Row`, `Column`, `Form`, `Button`, `Select`, `TextInput`, `TextArea`, `Card`, and `ChatModule`.

`ShellBuilder.addCustomJs(...)` emits normal script tags. ES module files that use `import` must be rendered as explicit `<script type="module" src="...">` components or they will fail in the browser.

Keep `/chat` visually close to the existing session workspace, but isolate its stable structure in SimplyPages components. The current live chat behavior still needs a small app-owned JS bridge for POST SSE token/tool updates.

The project guide's former `cannasite/java-html-framework` documentation path is stale in this workspace. Use `/home/dhickel/Code/Java/SimplyPages/docs` and `/home/dhickel/Code/Java/SimplyPages/demo` for current framework documentation and examples.

# Engine Relevance

Future UI work should add reusable component renderers and static modules instead of adding raw HTML strings to controllers. For operational pages, use a shared list/detail layout and one consolidated app stylesheet unless a surface has a strong reason for separate styling.

# Open Questions

Should task, workflow, agent, and job write actions eventually gain dedicated HTMX form adapter endpoints so the remaining JSON request bridges can be removed?
