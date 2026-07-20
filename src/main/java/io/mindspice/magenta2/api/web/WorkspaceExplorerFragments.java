package io.mindspice.magenta2.api.web;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;

import io.mindspice.magenta2.ai.orchestration.workspaces.WorkspaceExplorerService;
import io.mindspice.magenta2.ai.orchestration.workspaces.WorkspaceFileActionRecord;
import io.mindspice.magenta2.ai.orchestration.workspaces.WorkspaceFileLabel;
import io.mindspice.simplypages.components.Markdown;

final class WorkspaceExplorerFragments {
    static final String SHELL_ID = "workspace-file-explorer-shell";
    static final String LIST_ID = "workspace-file-list-region";
    static final String INSPECTOR_ID = "workspace-file-inspector";
    static final String MODAL_ID = "workspace-file-modal";

    // Stable targets for the agent-console Files tab.
    static final String AGENT_SHELL_ID = "agent-files-explorer";
    static final String AGENT_LIST_ID = "workspace-file-list";
    static final String AGENT_INSPECTOR_ID = "workspace-file-inspector";
    static final String AGENT_MODAL_ID = "workspace-file-modal";

    private static final String AGENT_FILES_PREFIX = "/agents/_detail/%s/files";

    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter
        .ofPattern("yyyy-MM-dd HH:mm", Locale.ROOT)
        .withZone(ZoneId.systemDefault());

    private WorkspaceExplorerFragments() {
    }

    static String shell(
        WorkspaceExplorerService.DirectoryListing listing,
        WorkspaceExplorerService.Entry inspected,
        String selectedPath,
        String type,
        String key
    ) {
        String currentPath = pathOrRoot(listing.path());
        String selected = selectedPath == null || selectedPath.isBlank() ? inspected == null ? null : inspected.path() : selectedPath;
        return """
            <div id="%s" class="workspace-file-explorer-shell">
              <div class="workspace-file-explorer-toolbar">
                <button type="button" class="button button-secondary small" disabled aria-disabled="true">Back</button>
                <button type="button" class="button button-secondary small" disabled aria-disabled="true">Forward</button>
                %s
                <button type="button" class="button button-secondary small" hx-get="/api/workspace/%s/%s/explorer?path=%s%s" hx-target="#%s" hx-swap="outerHTML">Refresh</button>
                <button type="button" class="button button-secondary small" hx-get="/api/workspace/%s/%s/modal/create-folder?path=%s" hx-target="#%s" hx-swap="innerHTML">New Folder</button>
                <button type="button" class="button button-secondary small" hx-get="/api/workspace/%s/%s/modal/create-text?path=%s" hx-target="#%s" hx-swap="innerHTML">New Text</button>
                <button type="button" class="button button-secondary small" hx-get="/api/workspace/%s/%s/modal/create-markdown?path=%s" hx-target="#%s" hx-swap="innerHTML">New Markdown</button>
              </div>
              <div class="workspace-file-pathbar">%s</div>
              <div class="workspace-file-explorer-layout">
                %s
                %s
              </div>
              <div id="%s"></div>
            </div>
            """.formatted(
            SHELL_ID,
            upButton(listing, type, key),
            urlPath(type),
            urlPath(key),
            url(currentPath),
            selected == null ? "" : "&selected=" + url(selected),
            SHELL_ID,
            urlPath(type),
            urlPath(key),
            url(currentPath),
            MODAL_ID,
            urlPath(type),
            urlPath(key),
            url(currentPath),
            MODAL_ID,
            urlPath(type),
            urlPath(key),
            url(currentPath),
            MODAL_ID,
            breadcrumbs(listing, type, key),
            list(listing, selected, false, type, key),
            inspector(type, key, inspected, null, false),
            MODAL_ID
        );
    }

    static String list(WorkspaceExplorerService.DirectoryListing listing, String selectedPath, boolean oob, String type, String key) {
        StringBuilder rows = new StringBuilder();
        String currentPath = pathOrRoot(listing.path());
        if (!".".equals(currentPath)) {
            rows.append("""
                <tr data-workspace-file-path="%s">
                  <td><button type="button" class="button button-link small" hx-get="/api/workspace/%s/%s/explorer?path=%s" hx-target="#%s" hx-swap="outerHTML">..</button></td>
                  <td>Folder</td><td>-</td><td>Unknown</td><td>Unknown</td><td></td><td></td>
                </tr>
                """.formatted(
                escape(parentPath(currentPath)),
                urlPath(type),
                urlPath(key),
                url(parentPath(currentPath)),
                SHELL_ID
            ));
        }
        for (WorkspaceExplorerService.Entry entry : listing.entries()) {
            rows.append(row(currentPath, entry, selectedPath, type, key));
        }
        if (listing.entries().isEmpty()) {
            rows.append("<tr><td colspan=\"7\">No entries available.</td></tr>");
        }
        return """
            <div id="%s" class="workspace-file-table-region"%s>
              <table class="workspace-file-table">
                <thead><tr><th>Name</th><th>File Type</th><th>Size</th><th>Created</th><th>Last Modified</th><th>Tags</th><th>Actions</th></tr></thead>
                <tbody>%s</tbody>
              </table>
            </div>
            """.formatted(LIST_ID, oob ? " hx-swap-oob=\"true\"" : "", rows);
    }

    static String inspector(String type, String key, WorkspaceExplorerService.Entry entry, String message, boolean oob) {
        if (entry == null) {
            return """
                <aside id="%s" class="workspace-file-inspector-pane"%s>
                  <h4>Inspect</h4>
                  <p>Select a file or directory.</p>
                  %s
                </aside>
                """.formatted(INSPECTOR_ID, oob ? " hx-swap-oob=\"true\"" : "", status(message, false));
        }
        String viewAction = entry.canView()
            ? button("View", "hx-get", "/api/workspace/" + urlPath(type) + "/" + urlPath(key) + "/viewer?path=" + url(entry.path()), "#" + MODAL_ID, "innerHTML")
            : "<span class=\"workspace-file-muted\">Viewer unavailable</span>";
        return """
            <aside id="%s" class="workspace-file-inspector-pane"%s>
              <h4>%s</h4>
              <p>%s</p>
              <dl>
                <dt>Type</dt><dd>%s</dd>
                <dt>Size</dt><dd>%s</dd>
                <dt>Created</dt><dd>%s</dd>
                <dt>Last Modified</dt><dd>%s</dd>
              </dl>
              <div class="workspace-file-entry-tags">%s</div>
              <form class="workspace-file-inline-form" hx-post="/api/workspace/%s/%s/files/labels" hx-target="#%s" hx-swap="innerHTML">
                <input type="hidden" name="path" value="%s">
                <input type="text" name="label" placeholder="Tag">
                <button type="submit" class="button small">Add Tag</button>
              </form>
              <div class="workspace-file-row-actions">
                %s
                %s
                %s
              </div>
              <div class="workspace-file-operation-stack">
                %s
                %s
              </div>
              %s
            </aside>
            """.formatted(
            INSPECTOR_ID,
            oob ? " hx-swap-oob=\"true\"" : "",
            escape(entry.name()),
            escape(entry.path()),
            escape(entry.fileType()),
            escape(entry.sizeLabel()),
            time(entry.createdAt()),
            time(entry.modifiedAt()),
            tags(type, key, entry.path(), entry.tags(), true),
            urlPath(type),
            urlPath(key),
            MODAL_ID,
            escapeAttribute(entry.path()),
            viewAction,
            modalButton("Rename", type, key, "rename", entry.path()),
            modalButton("Delete", type, key, "delete", entry.path()),
            inlineCopyMoveForm(type, key, "copy", entry.path()),
            inlineCopyMoveForm(type, key, "move", entry.path()),
            status(message, false)
        );
    }

    // ── Viewer ──

    static String viewer(String type, String key, WorkspaceExplorerService.FilePreview preview) {
        String title = "View " + fileName(preview.path());
        String body;
        if ("image".equals(preview.kind())) {
            body = """
                <div class="workspace-file-image-frame">
                  <img class="workspace-file-image" src="/api/workspace/%s/%s/files/view?path=%s" alt="%s">
                </div>
                <dl class="workspace-file-viewer-meta">
                  <dt>Path</dt><dd>%s</dd>
                  <dt>Size</dt><dd>%s</dd>
                </dl>
                <a class="button button-secondary small" href="/api/workspace/%s/%s/files/download?path=%s">Download</a>
                """.formatted(
                urlPath(type),
                urlPath(key),
                url(preview.path()),
                escapeAttribute(fileName(preview.path())),
                escape(preview.path()),
                escape(sizeLabel(preview.size())),
                urlPath(type),
                urlPath(key),
                url(preview.path())
            );
        } else if ("markdown".equals(preview.kind()) && preview.text()) {
            body = """
                <div class="workspace-file-viewer" data-viewer-kind="markdown" data-active-tab="rendered">
                  <div class="workspace-file-tabs"><span class="workspace-file-tab-active">Rendered</span><button type="button" class="button button-secondary small" hx-get="/api/workspace/%s/%s/viewer/text?path=%s&tab=text" hx-target="#%s" hx-swap="innerHTML">Text</button></div>
                  <div class="workspace-file-rendered">%s</div>
                </div>
                """.formatted(urlPath(type), urlPath(key), url(preview.path()), MODAL_ID, safeMarkdown(preview.content()));
        } else if ("text".equals(preview.kind()) && preview.text()) {
            body = textEditor(type, key, preview, false);
        } else {
            body = unsupportedViewerBody(preview);
        }
        return modal(type, key, title, body, false);
    }

    static String textViewer(String type, String key, WorkspaceExplorerService.FilePreview preview, String tab) {
        if (!preview.text()) {
            return modal(type, key, "Viewer unavailable", "<p>This file cannot be opened as editable text.</p>", false);
        }
        boolean markdown = "markdown".equals(preview.kind());
        boolean rendered = markdown && !"text".equalsIgnoreCase(tab);
        String body;
        if (rendered) {
            body = """
                <div class="workspace-file-viewer" data-viewer-kind="markdown" data-active-tab="rendered">
                  <div class="workspace-file-tabs"><span class="workspace-file-tab-active">Rendered</span><button type="button" class="button button-secondary small" hx-get="/api/workspace/%s/%s/viewer/text?path=%s&tab=text" hx-target="#%s" hx-swap="innerHTML">Text</button></div>
                  <div class="workspace-file-rendered">%s</div>
                </div>
                """.formatted(urlPath(type), urlPath(key), url(preview.path()), MODAL_ID, safeMarkdown(preview.content()));
        } else {
            body = textEditor(type, key, preview, markdown);
        }
        return modal(type, key, "View " + fileName(preview.path()), body, false);
    }

    // ── Mutation responses (OOB) ──

    static String textSaveResponse(
        String type, String key,
        WorkspaceExplorerService.FilePreview preview,
        WorkspaceExplorerService.DirectoryListing listing,
        WorkspaceExplorerService.Entry inspected,
        String selectedPath,
        String message
    ) {
        return textViewer(type, key, preview, "rendered")
            + list(listing, selectedPath, true, type, key)
            + inspector(type, key, inspected, message, true);
    }

    static String textCreateResponse(
        String type, String key,
        WorkspaceExplorerService.FilePreview preview,
        WorkspaceExplorerService.DirectoryListing listing,
        WorkspaceExplorerService.Entry inspected,
        String selectedPath,
        String message
    ) {
        return textViewer(type, key, preview, "text")
            + list(listing, selectedPath, true, type, key)
            + inspector(type, key, inspected, message, true);
    }

    static String actionModal(String type, String key, String action, String path, WorkspaceExplorerService.DeletePreflight preflight) {
        String body = switch (action) {
            case "create-folder" -> createFolderForm(type, key, path);
            case "create-text", "create-markdown" -> createTextForm(type, key, action, path);
            case "rename" -> form(type, key, "/files/rename", path, "name", "New name", "Rename");
            case "copy", "move" -> copyMoveForm(type, key, action, path);
            case "tag" -> form(type, key, "/files/labels", path, "label", "Tag", "Add Tag");
            case "delete", "delete-recursive" -> deleteForm(type, key, action, path, preflight);
            default -> "<p>Unknown file action.</p>";
        };
        return modal(type, key, title(action), body, false);
    }

    static String mutationResponse(
        WorkspaceExplorerService.DirectoryListing listing,
        WorkspaceExplorerService.Entry inspected,
        String selectedPath,
        String message,
        String type,
        String key
    ) {
        return modal(type, key, "", "", true)
            + list(listing, selectedPath, true, type, key)
            + inspector(type, key, inspected, message, true);
    }

    static String modalError(String type, String key, String title, String message) {
        return modal(type, key, title, "<div class=\"workspace-file-status-error\">" + escape(message) + "</div>", false);
    }

    static String modalMessage(String type, String key, String title, String message) {
        return modal(type, key, title, "<div class=\"workspace-file-status\">" + escape(message) + "</div>", false);
    }

    static String inspectorError(String message) {
        return inspector(null, null, null, message, false);
    }

    static String listError(String message) {
        return """
            <div id="%s" class="workspace-file-table-region">
              <div class="workspace-file-status-error">%s</div>
            </div>
            """.formatted(LIST_ID, escape(message));
    }

    // ── Private helpers ──

    private static String row(String currentPath, WorkspaceExplorerService.Entry entry, String selectedPath, String type, String key) {
        String selected = entry.path().equals(selectedPath) ? " selected" : "";
        String nameAction = entry.directory()
            ? button("Open " + entry.name(), "hx-get", "/api/workspace/" + urlPath(type) + "/" + urlPath(key) + "/explorer?path=" + url(entry.path()), "#" + SHELL_ID, "outerHTML")
            : button(entry.name(), "hx-get", "/api/workspace/" + urlPath(type) + "/" + urlPath(key) + "/explorer?path=" + url(currentPath)
                + "&selected=" + url(entry.path()), "#" + SHELL_ID, "outerHTML");
        String open = entry.directory()
            ? button("Open", "hx-get", "/api/workspace/" + urlPath(type) + "/" + urlPath(key) + "/explorer?path=" + url(entry.path()), "#" + SHELL_ID, "outerHTML")
            : entry.canView()
                ? button("View", "hx-get", "/api/workspace/" + urlPath(type) + "/" + urlPath(key) + "/viewer?path=" + url(entry.path()), "#" + MODAL_ID, "innerHTML")
                : "";
        return """
            <tr class="workspace-file-row%s" data-workspace-file-path="%s">
              <td class="workspace-file-name">%s</td>
              <td>%s</td><td>%s</td><td>%s</td><td>%s</td><td>%s</td>
              <td class="workspace-file-row-actions">%s%s%s</td>
            </tr>
            """.formatted(
            selected,
            escapeAttribute(entry.path()),
            nameAction,
            escape(entry.fileType()),
            escape(entry.sizeLabel()),
            time(entry.createdAt()),
            time(entry.modifiedAt()),
            tags(type, key, entry.path(), entry.tags(), false),
            open,
            modalButton("Rename", type, key, "rename", entry.path()),
            modalButton("Delete", type, key, "delete", entry.path())
        );
    }

    private static String createFolderForm(String type, String key, String path) {
        return """
            <form class="workspace-file-stack-form" hx-post="/api/workspace/%s/%s/directories" hx-target="#%s" hx-swap="innerHTML">
              <input type="hidden" name="path" value="%s">
              <input type="text" name="name" placeholder="Folder name">
              <button type="submit" class="button">Create Folder</button>
            </form>
            """.formatted(urlPath(type), urlPath(key), MODAL_ID, escapeAttribute(path));
    }

    private static String createTextForm(String type, String key, String action, String path) {
        String kind = "create-markdown".equals(action) ? "markdown" : "text";
        String label = "markdown".equals(kind) ? "Create Markdown" : "Create Text File";
        return """
            <form class="workspace-file-stack-form" hx-post="/api/workspace/%s/%s/files/%s" hx-target="#%s" hx-swap="innerHTML">
              <input type="hidden" name="parentPath" value="%s">
              <input type="text" name="fileName" placeholder="File name">
              <button type="submit" class="button">%s</button>
            </form>
            """.formatted(urlPath(type), urlPath(key), urlPath(kind), MODAL_ID, escapeAttribute(path), escape(label));
    }

    private static String form(String type, String key, String route, String path, String field, String placeholder, String label) {
        return """
            <form class="workspace-file-stack-form" hx-post="/api/workspace/%s/%s%s" hx-target="#%s" hx-swap="innerHTML">
              <input type="hidden" name="path" value="%s">
              <input type="text" name="%s" placeholder="%s">
              <button type="submit" class="button">%s</button>
            </form>
            """.formatted(urlPath(type), urlPath(key), route, MODAL_ID, escapeAttribute(path), escapeAttribute(field), escapeAttribute(placeholder), escape(label));
    }

    private static String copyMoveForm(String type, String key, String action, String path) {
        String label = "copy".equals(action) ? "Copy" : "Move";
        String prefix = "modal-" + action;
        return """
            <form class="workspace-file-stack-form" data-file-action="%s" hx-post="/api/workspace/%s/%s/files/%s" hx-target="#%s" hx-swap="innerHTML">
              <input type="hidden" name="path" value="%s">
              <label for="%s-destination">%s destination directory</label>
              <input id="%s-destination" type="text" name="destination" placeholder="%s destination directory" aria-label="%s destination directory" required>
              <label for="%s-name">Optional new name</label>
              <input id="%s-name" type="text" name="name" placeholder="Optional new name" aria-label="%s optional new name">
              <button type="submit" class="button" data-file-action-submit="%s">%s</button>
            </form>
            """.formatted(
            escapeAttribute(action),
            urlPath(type),
            urlPath(key),
            urlPath(action),
            MODAL_ID,
            escapeAttribute(path),
            prefix,
            escape(label),
            prefix,
            escapeAttribute(label),
            escapeAttribute(label),
            prefix,
            prefix,
            escapeAttribute(label),
            escapeAttribute(action),
            escape(label)
        );
    }

    private static String inlineCopyMoveForm(String type, String key, String action, String path) {
        String label = "copy".equals(action) ? "Copy" : "Move";
        String prefix = "inspect-" + action;
        return """
            <details class="workspace-file-operation-group" open>
              <summary>%s</summary>
              <form class="workspace-file-stack-form" data-file-action="%s" hx-post="/api/workspace/%s/%s/files/%s" hx-target="#%s" hx-swap="innerHTML">
                <input type="hidden" name="path" value="%s">
                <label for="%s-destination">%s destination directory</label>
                <input id="%s-destination" type="text" name="destination" placeholder="%s destination directory" aria-label="%s destination directory" required>
                <label for="%s-name">Optional new name</label>
                <input id="%s-name" type="text" name="name" placeholder="Optional new name" aria-label="%s optional new name">
                <button type="submit" class="button small" data-file-action-submit="%s">%s</button>
              </form>
            </details>
            """.formatted(
            escape(label),
            escapeAttribute(action),
            urlPath(type),
            urlPath(key),
            urlPath(action),
            MODAL_ID,
            escapeAttribute(path),
            prefix,
            escape(label),
            prefix,
            escapeAttribute(label),
            escapeAttribute(label),
            prefix,
            prefix,
            escapeAttribute(label),
            escapeAttribute(action),
            escape(label)
        );
    }

    private static String deleteForm(String type, String key, String action, String path, WorkspaceExplorerService.DeletePreflight preflight) {
        if ("delete-recursive".equals(action)
            || (preflight != null && preflight.requiredStep() == WorkspaceExplorerService.DeleteStep.FILE_CONFIRM)) {
            String step = "delete-recursive".equals(action) ? "DIRECTORY_RECURSIVE_CONFIRM" : "FILE_CONFIRM";
            return """
                <p>Delete %s?</p>
                <form class="workspace-file-stack-form" hx-post="/api/workspace/%s/%s/files/delete" hx-target="#%s" hx-swap="innerHTML">
                  <input type="hidden" name="path" value="%s">
                  <input type="hidden" name="step" value="%s">
                  <button type="submit" class="button">Confirm Delete</button>
                </form>
                """.formatted(escape(fileName(path)), urlPath(type), urlPath(key), MODAL_ID, escapeAttribute(path), step);
        }
        if (preflight != null && preflight.requiredStep() == WorkspaceExplorerService.DeleteStep.DIRECTORY_RECURSIVE_CONFIRM) {
            return """
                <p>This directory contains %d entries.</p>
                <button type="button" class="button" hx-get="/api/workspace/%s/%s/modal/delete-recursive?path=%s" hx-target="#%s" hx-swap="innerHTML">Confirm Recursive Delete</button>
                """.formatted(preflight.candidateCount(), urlPath(type), urlPath(key), url(path), MODAL_ID);
        }
        return "<p>Delete is unavailable for this path.</p>";
    }

    private static String textEditor(String type, String key, WorkspaceExplorerService.FilePreview preview, boolean markdown) {
        String tabs = markdown
            ? "<button type=\"button\" class=\"button button-secondary small\" hx-get=\"/api/workspace/" + urlPath(type) + "/" + urlPath(key)
                + "/viewer/text?path=" + url(preview.path()) + "&tab=rendered\" hx-target=\"#" + MODAL_ID
                + "\" hx-swap=\"innerHTML\">Rendered</button><span class=\"workspace-file-tab-active\">Text</span>"
            : "<span class=\"workspace-file-tab-active\">Text</span>";
        return """
            <div class="workspace-file-viewer" data-viewer-kind="%s" data-active-tab="text">
              <div class="workspace-file-tabs">%s</div>
              <form class="workspace-file-stack-form" hx-put="/api/workspace/%s/%s/files/text?path=%s" hx-target="#%s" hx-swap="innerHTML">
                <textarea name="content" rows="14">%s</textarea>
                <button type="submit" class="button">Save File</button>
              </form>
            </div>
            """.formatted(markdown ? "markdown" : "text", tabs, urlPath(type), urlPath(key), url(preview.path()), MODAL_ID, escape(preview.content() == null ? "" : preview.content()));
    }

    private static String unsupportedViewerBody(WorkspaceExplorerService.FilePreview preview) {
        return """
            <div class="workspace-file-status">Viewer unavailable for this file type or size.</div>
            <dl class="workspace-file-viewer-meta">
              <dt>Path</dt><dd>%s</dd>
              <dt>Size</dt><dd>%s</dd>
              <dt>Reason</dt><dd>%s</dd>
            </dl>
            """.formatted(
            escape(preview.path()),
            escape(sizeLabel(preview.size())),
            escape(unsupportedReason(preview.kind()))
        );
    }

    private static String modal(String type, String key, String title, String body, boolean clear) {
        if (clear) {
            return "<div id=\"" + MODAL_ID + "\" hx-swap-oob=\"true\"></div>";
        }
        return """
            <div class="workspace-file-modal" role="dialog" aria-modal="true">
              <div class="workspace-file-modal-panel">
                <div class="workspace-file-modal-header"><h2>%s</h2><button type="button" class="button button-secondary small" hx-get="/api/workspace/%s/%s/modal/close" hx-target="#%s" hx-swap="innerHTML">Close</button></div>
                %s
              </div>
            </div>
            """.formatted(escape(title), urlPath(type), urlPath(key), MODAL_ID, body);
    }

    private static String upButton(WorkspaceExplorerService.DirectoryListing listing, String type, String key) {
        String path = pathOrRoot(listing.path());
        if (".".equals(path)) {
            return "";
        }
        return button("Up", "hx-get", "/api/workspace/" + urlPath(type) + "/" + urlPath(key)
            + "/explorer?path=" + url(parentPath(path)), "#" + SHELL_ID, "outerHTML");
    }

    private static String breadcrumbs(WorkspaceExplorerService.DirectoryListing listing, String type, String key) {
        String path = pathOrRoot(listing.path());
        StringBuilder out = new StringBuilder();
        out.append("<button type=\"button\" class=\"button button-link small\" hx-get=\"/api/workspace/")
            .append(urlPath(type))
            .append("/")
            .append(urlPath(key))
            .append("/explorer?path=.\" hx-target=\"#")
            .append(SHELL_ID)
            .append("\" hx-swap=\"outerHTML\">.</button>");
        if (".".equals(path)) {
            return out.toString();
        }
        StringBuilder current = new StringBuilder();
        for (String part : path.split("/")) {
            if (part.isBlank()) {
                continue;
            }
            if (!current.isEmpty()) {
                current.append('/');
            }
            current.append(part);
            out.append(" / <button type=\"button\" class=\"button button-link small\" hx-get=\"/api/workspace/")
                .append(urlPath(type))
                .append("/")
                .append(urlPath(key))
                .append("/explorer?path=")
                .append(url(current.toString()))
                .append("\" hx-target=\"#")
                .append(SHELL_ID)
                .append("\" hx-swap=\"outerHTML\">")
                .append(escape(part))
                .append("</button>");
        }
        return out.toString();
    }

    private static String button(String label, String hxVerb, String route, String target, String swap) {
        return """
            <button type="button" class="button button-secondary small" %s="%s" hx-target="%s" hx-swap="%s">%s</button>
            """.formatted(hxVerb, route, target, swap, escape(label));
    }

    private static String modalButton(String label, String type, String key, String action, String path) {
        return button(label, "hx-get", "/api/workspace/" + urlPath(type) + "/" + urlPath(key) + "/modal/" + urlPath(action)
            + "?path=" + url(path), "#" + MODAL_ID, "innerHTML");
    }

    private static String tags(String type, String key, String path, List<WorkspaceFileLabel> tags, boolean removable) {
        if (tags == null || tags.isEmpty()) {
            return "<span class=\"tag tag-muted\">No tags</span>";
        }
        StringBuilder out = new StringBuilder();
        int visible = removable ? tags.size() : Math.min(tags.size(), 3);
        for (WorkspaceFileLabel tag : tags.stream().limit(visible).toList()) {
            out.append("<span class=\"tag\">").append(escape(tag.slug()));
            if (removable && type != null) {
                out.append(" <button type=\"button\" class=\"button button-link small\" hx-delete=\"/api/workspace/")
                    .append(urlPath(type))
                    .append("/")
                    .append(urlPath(key))
                    .append("/files/labels?path=")
                    .append(url(path))
                    .append("&amp;label=")
                    .append(url(tag.slug()))
                    .append("\" hx-target=\"#")
                    .append(MODAL_ID)
                    .append("\" hx-swap=\"innerHTML\">x</button>");
            }
            out.append("</span>");
        }
        if (!removable && tags.size() > visible) {
            out.append("<span class=\"tag tag-muted\">+").append(tags.size() - visible).append("</span>");
        }
        return out.toString();
    }

    static String renderedMarkdownForTest(String content, Function<String, String> renderer) {
        return renderMarkdown(content, renderer);
    }

    private static String safeMarkdown(String content) {
        return renderMarkdown(content, value -> new Markdown(value).render());
    }

    private static String renderMarkdown(String content, Function<String, String> renderer) {
        try {
            return renderer.apply(content == null ? "" : content);
        } catch (RuntimeException exception) {
            return """
                <div class="workspace-file-render-fallback">%s</div>
                <div class="workspace-file-status-error workspace-file-render-error">Markdown render failed. Raw text is still available.</div>
                """.formatted(escape(content == null ? "" : content));
        }
    }

    private static String unsupportedReason(String kind) {
        return switch (kind == null ? "" : kind) {
            case "too_large" -> "File is too large for the browser viewer.";
            case "invalid_utf8" -> "File is not valid UTF-8 text.";
            case "unsupported" -> "File type is not supported by the viewer.";
            default -> "Viewer is not available for this entry.";
        };
    }

    private static String sizeLabel(long size) {
        if (size < 1024) {
            return size + " B";
        }
        if (size < 1024 * 1024) {
            return String.format(Locale.ROOT, "%.1f KB", size / 1024.0);
        }
        if (size < 1024 * 1024 * 1024) {
            return String.format(Locale.ROOT, "%.1f MB", size / (1024.0 * 1024.0));
        }
        return String.format(Locale.ROOT, "%.1f GB", size / (1024.0 * 1024.0 * 1024.0));
    }

    private static String status(String message, boolean error) {
        if (message == null || message.isBlank()) {
            return "";
        }
        return "<div class=\"" + (error ? "workspace-file-status-error" : "workspace-file-status") + "\">" + escape(message) + "</div>";
    }

    private static String title(String action) {
        return switch (action) {
            case "rename" -> "Rename";
            case "copy" -> "Copy";
            case "move" -> "Move";
            case "tag" -> "Tags";
            case "create-folder" -> "Create Folder";
            case "create-text" -> "Create Text File";
            case "create-markdown" -> "Create Markdown File";
            case "delete", "delete-recursive" -> "Delete";
            default -> "File Action";
        };
    }

    private static String time(java.time.Instant instant) {
        return instant == null ? "Unknown" : TIME_FORMAT.format(instant);
    }

    private static String fileName(String path) {
        String normalized = pathOrRoot(path);
        int slash = normalized.lastIndexOf('/');
        return slash >= 0 ? normalized.substring(slash + 1) : normalized;
    }

    private static String parentPath(String path) {
        String normalized = pathOrRoot(path);
        int slash = normalized.lastIndexOf('/');
        if (slash <= 0) {
            return ".";
        }
        return normalized.substring(0, slash);
    }

    private static String pathOrRoot(String path) {
        return path == null || path.isBlank() ? "." : path;
    }

    private static String url(String value) {
        return URLEncoder.encode(pathOrRoot(value), StandardCharsets.UTF_8);
    }

    private static String urlPath(String value) {
        return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8);
    }

    private static String escape(String value) {
        if (value == null) {
            return "";
        }
        return value
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&#39;");
    }

    private static String escapeAttribute(String value) {
        return escape(value);
    }

    // ── Agent-console Files tab fragments ──

    private static String agentFilesRoute(String encodedAgentId, String suffix) {
        return AGENT_FILES_PREFIX.formatted(encodedAgentId) + suffix;
    }

    /** Render the complete Files tab shell for the agent console. */
    static String agentShell(
        String encodedAgentId,
        WorkspaceExplorerService.DirectoryListing listing,
        WorkspaceExplorerService.Entry inspected,
        String selectedPath
    ) {
        String currentPath = pathOrRoot(listing.path());
        String selected = selectedPath == null || selectedPath.isBlank()
            ? inspected == null ? null : inspected.path() : selectedPath;
        String base = agentFilesRoute(encodedAgentId, "");
        String createActions = isReadOnlyRuntimePath(currentPath) ? "" : """
            <button type="button" class="button button-secondary small" hx-get="%s/modal/create-folder?path=%s" hx-target="#%s" hx-swap="innerHTML">New Folder</button>
            <button type="button" class="button button-secondary small" hx-get="%s/modal/create-text?path=%s" hx-target="#%s" hx-swap="innerHTML">New Text</button>
            <button type="button" class="button button-secondary small" hx-get="%s/modal/create-markdown?path=%s" hx-target="#%s" hx-swap="innerHTML">New Markdown</button>
            """.formatted(
            base, url(currentPath), AGENT_MODAL_ID,
            base, url(currentPath), AGENT_MODAL_ID,
            base, url(currentPath), AGENT_MODAL_ID);
        return """
            <div id="%s" class="workspace-file-explorer-shell agent-files-operations-shell">
              <div class="agent-files-operations-layout">
                <section class="agent-files-browser-panel" aria-label="Workspace files">
                  <div class="workspace-file-explorer-toolbar">
                    <button type="button" class="button button-secondary small" disabled aria-disabled="true">Back</button>
                    <button type="button" class="button button-secondary small" disabled aria-disabled="true">Forward</button>
                    %s
                    <button type="button" class="button button-secondary small" hx-get="%s/list?path=%s%s" hx-target="#%s" hx-swap="outerHTML">Refresh</button>
                    %s
                    <button type="button" class="button button-secondary small" hx-get="%s/actions" hx-target="#%s" hx-swap="innerHTML">Recent Actions</button>
                  </div>
                  <div class="workspace-file-pathbar">%s</div>
                  <div class="workspace-file-explorer-layout">
                    %s
                    %s
                  </div>
                  <div id="%s"></div>
                </section>
                <aside id="%s" class="run-operations-panel run-state-loading"
                       hx-get="/agents/_detail/%s/runs?limit=25" hx-trigger="load" hx-swap="outerHTML"
                       aria-label="Task run operations">
                  <p>Loading task run metadata…</p>
                </aside>
              </div>
            </div>
            """.formatted(
            AGENT_SHELL_ID,
            agentUpButton(listing, encodedAgentId),
            base, url(currentPath),
            selected == null ? "" : "&selected=" + url(selected),
            AGENT_SHELL_ID,
            createActions,
            base, AGENT_MODAL_ID,
            agentBreadcrumbs(listing, encodedAgentId),
            agentList(encodedAgentId, listing, selected, false),
            agentInspector(encodedAgentId, inspected, null, false),
            AGENT_MODAL_ID,
            RunOperationsFragments.GROUPS_ID,
            encodedAgentId
        );
    }

    static String agentList(
        String encodedAgentId,
        WorkspaceExplorerService.DirectoryListing listing,
        String selectedPath,
        boolean oob
    ) {
        StringBuilder rows = new StringBuilder();
        String currentPath = pathOrRoot(listing.path());
        String base = agentFilesRoute(encodedAgentId, "");
        if (!".".equals(currentPath)) {
            rows.append("""
                <tr data-workspace-file-path="%s">
                  <td><button type="button" class="button button-link small" hx-get="%s/list?path=%s" hx-target="#%s" hx-swap="outerHTML">..</button></td>
                  <td>Folder</td><td>-</td><td>Unknown</td><td>Unknown</td><td></td><td></td>
                </tr>
                """.formatted(
                escape(parentPath(currentPath)),
                base, url(parentPath(currentPath)),
                AGENT_SHELL_ID
            ));
        }
        for (WorkspaceExplorerService.Entry entry : listing.entries()) {
            rows.append(agentRow(currentPath, entry, selectedPath, encodedAgentId));
        }
        if (listing.entries().isEmpty()) {
            rows.append("<tr><td colspan=\"7\">No entries available.</td></tr>");
        }
        return """
            <div id="%s" class="workspace-file-table-region"%s>
              <table class="workspace-file-table">
                <thead><tr><th>Name</th><th>File Type</th><th>Size</th><th>Created</th><th>Last Modified</th><th>Tags</th><th>Actions</th></tr></thead>
                <tbody>%s</tbody>
              </table>
            </div>
            """.formatted(AGENT_LIST_ID, oob ? " hx-swap-oob=\"true\"" : "", rows);
    }

    static String agentInspector(
        String encodedAgentId,
        WorkspaceExplorerService.Entry entry,
        String message,
        boolean oob
    ) {
        if (entry == null) {
            return """
                <aside id="%s" class="workspace-file-inspector-pane"%s>
                  <h4>Inspect</h4>
                  <p>Select a file or directory.</p>
                  %s
                </aside>
                """.formatted(AGENT_INSPECTOR_ID, oob ? " hx-swap-oob=\"true\"" : "", status(message, false));
        }
        String viewAction = entry.canView()
            ? agentButton("View", "hx-get", agentFilesRoute(encodedAgentId, "/viewer?path=" + url(entry.path())), "#" + AGENT_MODAL_ID, "innerHTML")
            : "<span class=\"workspace-file-muted\">Viewer unavailable</span>";
        boolean mutable = !".".equals(entry.path()) && !isReadOnlyRuntimePath(entry.path());
        String tagControls = mutable ? """
              <div class="workspace-file-entry-tags">%s</div>
              <form class="workspace-file-inline-form" hx-post="%s/labels" hx-target="#%s" hx-swap="outerHTML">
                <input type="hidden" name="path" value="%s">
                <input type="text" name="label" placeholder="Tag">
                <button type="submit" class="button small">Add Tag</button>
              </form>
            """.formatted(
            agentTags(encodedAgentId, entry.path(), entry.tags(), true),
            agentFilesRoute(encodedAgentId, ""), AGENT_INSPECTOR_ID, escapeAttribute(entry.path()))
            : "<div class=\"workspace-file-entry-tags\">Read-only runtime path</div>";
        String mutationActions = mutable
            ? agentModalButton("Rename", encodedAgentId, "rename", entry.path())
                + agentModalButton("Delete", encodedAgentId, "delete", entry.path())
                + "<div class=\"workspace-file-operation-stack\">"
                + agentInlineCopyMoveForm(encodedAgentId, "copy", entry.path())
                + agentInlineCopyMoveForm(encodedAgentId, "move", entry.path()) + "</div>"
            : "";
        return """
            <aside id="%s" class="workspace-file-inspector-pane"%s>
              <h4>%s</h4>
              <p>%s</p>
              <dl>
                <dt>Type</dt><dd>%s</dd>
                <dt>Size</dt><dd>%s</dd>
                <dt>Created</dt><dd>%s</dd>
                <dt>Last Modified</dt><dd>%s</dd>
              </dl>
              %s
              <div class="workspace-file-row-actions">%s%s</div>
              %s
            </aside>
            """.formatted(
            AGENT_INSPECTOR_ID,
            oob ? " hx-swap-oob=\"true\"" : "",
            escape(entry.name()),
            escape(entry.path()),
            escape(entry.fileType()),
            escape(entry.sizeLabel()),
            time(entry.createdAt()),
            time(entry.modifiedAt()),
            tagControls,
            viewAction,
            mutationActions,
            status(message, false)
        );
    }

    static String agentViewer(String encodedAgentId, WorkspaceExplorerService.FilePreview preview) {
        String title = "View " + fileName(preview.path());
        String apiPrefix = "/api/workspace/agent/" + encodedAgentId;
        String body;
        if ("image".equals(preview.kind())) {
            body = """
                <div class="workspace-file-image-frame">
                  <img class="workspace-file-image" src="%s/files/view?path=%s" alt="%s">
                </div>
                <dl class="workspace-file-viewer-meta">
                  <dt>Path</dt><dd>%s</dd>
                  <dt>Size</dt><dd>%s</dd>
                </dl>
                <a class="button button-secondary small" href="%s/files/download?path=%s">Download</a>
                """.formatted(
                apiPrefix, url(preview.path()),
                escapeAttribute(fileName(preview.path())),
                escape(preview.path()),
                escape(sizeLabel(preview.size())),
                apiPrefix, url(preview.path())
            );
        } else if ("markdown".equals(preview.kind()) && preview.text()) {
            body = """
                <div class="workspace-file-viewer" data-viewer-kind="markdown" data-active-tab="rendered">
                  <div class="workspace-file-tabs"><span class="workspace-file-tab-active">Rendered</span><button type="button" class="button button-secondary small" hx-get="%s/text?path=%s&tab=text" hx-target="#%s" hx-swap="innerHTML">Text</button></div>
                  <div class="workspace-file-rendered">%s</div>
                </div>
                """.formatted(agentFilesRoute(encodedAgentId, ""), url(preview.path()), AGENT_MODAL_ID, safeMarkdown(preview.content()))
                + agentDownloadLink(apiPrefix, preview.path());
        } else if ("text".equals(preview.kind()) && preview.text()) {
            body = (isReadOnlyRuntimePath(preview.path())
                ? agentReadOnlyText(preview)
                : agentTextEditor(encodedAgentId, preview, false))
                + agentDownloadLink(apiPrefix, preview.path());
        } else {
            body = unsupportedViewerBody(preview) + agentDownloadLink(apiPrefix, preview.path());
        }
        return agentModal(encodedAgentId, title, body, false);
    }

    static String agentTextViewer(String encodedAgentId, WorkspaceExplorerService.FilePreview preview, String tab) {
        if (!preview.text()) {
            return agentModal(encodedAgentId, "Viewer unavailable", "<p>This file cannot be opened as text.</p>", false);
        }
        boolean markdown = "markdown".equals(preview.kind());
        boolean rendered = markdown && !"text".equalsIgnoreCase(tab);
        String body;
        if (rendered) {
            body = """
                <div class="workspace-file-viewer" data-viewer-kind="markdown" data-active-tab="rendered">
                  <div class="workspace-file-tabs"><span class="workspace-file-tab-active">Rendered</span><button type="button" class="button button-secondary small" hx-get="%s/text?path=%s&tab=text" hx-target="#%s" hx-swap="innerHTML">Text</button></div>
                  <div class="workspace-file-rendered">%s</div>
                </div>
                """.formatted(agentFilesRoute(encodedAgentId, ""), url(preview.path()), AGENT_MODAL_ID, safeMarkdown(preview.content()));
        } else {
            body = isReadOnlyRuntimePath(preview.path())
                ? agentReadOnlyText(preview)
                : agentTextEditor(encodedAgentId, preview, markdown);
        }
        return agentModal(encodedAgentId, "View " + fileName(preview.path()), body, false);
    }

    // ── Agent mutation responses ──

    static String agentMutationResponse(
        String encodedAgentId,
        WorkspaceExplorerService.DirectoryListing listing,
        WorkspaceExplorerService.Entry inspected,
        String selectedPath,
        String message
    ) {
        return "<span hidden data-workspace-file-modal-closed=\"true\"></span>"
            + agentList(encodedAgentId, listing, selectedPath, true)
            + agentInspector(encodedAgentId, inspected, message, true);
    }

    static String agentTextSaveResponse(
        String encodedAgentId,
        WorkspaceExplorerService.FilePreview preview,
        WorkspaceExplorerService.DirectoryListing listing,
        WorkspaceExplorerService.Entry inspected,
        String selectedPath,
        String message
    ) {
        return agentTextViewer(encodedAgentId, preview, "rendered")
            + agentList(encodedAgentId, listing, selectedPath, true)
            + agentInspector(encodedAgentId, inspected, message, true);
    }

    static String agentTextCreateResponse(
        String encodedAgentId,
        WorkspaceExplorerService.FilePreview preview,
        WorkspaceExplorerService.DirectoryListing listing,
        WorkspaceExplorerService.Entry inspected,
        String selectedPath,
        String message
    ) {
        return agentTextViewer(encodedAgentId, preview, "text")
            + agentList(encodedAgentId, listing, selectedPath, true)
            + agentInspector(encodedAgentId, inspected, message, true);
    }

    static String agentActionModal(
        String encodedAgentId,
        String action,
        String path,
        WorkspaceExplorerService.DeletePreflight preflight
    ) {
        String body = switch (action) {
            case "create-folder" -> agentCreateFolderForm(encodedAgentId, path);
            case "create-text", "create-markdown" -> agentCreateTextForm(encodedAgentId, action, path);
            case "rename" -> agentForm(encodedAgentId, "/rename", path, "name", "New name", "Rename");
            case "copy", "move" -> agentCopyMoveForm(encodedAgentId, action, path);
            case "tag" -> agentForm(encodedAgentId, "/labels", path, "label", "Tag", "Add Tag");
            case "delete", "delete-recursive" -> agentDeleteForm(encodedAgentId, action, path, preflight);
            default -> "<p>Unknown file action.</p>";
        };
        return agentModal(encodedAgentId, title(action), body, false);
    }

    static String agentModalError(String encodedAgentId, String title, String message) {
        return agentModal(encodedAgentId, title, "<div class=\"workspace-file-status-error\">" + escape(message) + "</div>", false);
    }

    static String agentModalMessage(String encodedAgentId, String title, String message) {
        return agentModal(encodedAgentId, title, "<div class=\"workspace-file-status\">" + escape(message) + "</div>", false);
    }

    static String agentRecentActions(String encodedAgentId, List<WorkspaceFileActionRecord> actions) {
        StringBuilder rows = new StringBuilder();
        for (WorkspaceFileActionRecord action : actions) {
            rows.append("<tr><td>").append(escape(action.actionType().name()))
                .append("</td><td>").append(escape(action.sourceRelativePath()))
                .append("</td><td>").append(escape(action.targetRelativePath()))
                .append("</td><td>").append(time(action.createdAt())).append("</td></tr>");
        }
        if (actions.isEmpty()) {
            rows.append("<tr><td colspan=\"4\">No recent file actions.</td></tr>");
        }
        String body = "<div class=\"workspace-file-table-region\"><table class=\"workspace-file-table\">"
            + "<thead><tr><th>Action</th><th>Source</th><th>Target</th><th>Time</th></tr></thead><tbody>"
            + rows + "</tbody></table></div>";
        return agentModal(encodedAgentId, "Recent Actions", body, false);
    }

    static String agentInspectorError(String message) {
        return agentInspector(null, null, message, false);
    }

    static String agentListError(String message) {
        return """
            <div id="%s" class="workspace-file-table-region">
              <div class="workspace-file-status-error">%s</div>
            </div>
            """.formatted(AGENT_LIST_ID, escape(message));
    }

    static String agentEmptyRoot(String encodedAgentId) {
        return """
            <div id="%s" class="workspace-file-explorer-shell agent-files-operations-shell">
              <div class="agent-files-operations-layout">
                <section class="agent-files-browser-panel" aria-label="Workspace files">
                  <div class="workspace-file-explorer-toolbar">
                    <button type="button" class="button button-secondary small" hx-get="%s/list?path=." hx-target="#%s" hx-swap="outerHTML">Refresh</button>
                  </div>
                  <div class="workspace-file-explorer-layout">
                    <div id="%s" class="workspace-file-table-region">
                      <p>Agent workspace root has not been created yet. Create a file or directory to get started.</p>
                    </div>
                    <aside id="%s" class="workspace-file-inspector-pane">
                      <h4>Empty Workspace</h4>
                      <p>This agent has no files yet.</p>
                    </aside>
                  </div>
                  <div id="%s"></div>
                </section>
                <aside id="%s" class="run-operations-panel run-state-loading"
                       hx-get="/agents/_detail/%s/runs?limit=25" hx-trigger="load" hx-swap="outerHTML"
                       aria-label="Task run operations">
                  <p>Loading task run metadata…</p>
                </aside>
              </div>
            </div>
            """.formatted(
            AGENT_SHELL_ID,
            agentFilesRoute(encodedAgentId, ""),
            AGENT_SHELL_ID,
            AGENT_LIST_ID,
            AGENT_INSPECTOR_ID,
            AGENT_MODAL_ID,
            RunOperationsFragments.GROUPS_ID,
            encodedAgentId
        );
    }

    // ── Agent-console private helpers ──

    private static String agentRow(String currentPath, WorkspaceExplorerService.Entry entry, String selectedPath, String encodedAgentId) {
        String selected = entry.path().equals(selectedPath) ? " selected" : "";
        String base = agentFilesRoute(encodedAgentId, "");
        String nameAction = entry.directory()
            ? agentButton("Open " + entry.name(), "hx-get", base + "/list?path=" + url(entry.path()), "#" + AGENT_SHELL_ID, "outerHTML")
            : agentButton(entry.name(), "hx-get", base + "/list?path=" + url(currentPath)
                + "&selected=" + url(entry.path()), "#" + AGENT_SHELL_ID, "outerHTML");
        String open = entry.directory()
            ? agentButton("Open", "hx-get", base + "/list?path=" + url(entry.path()), "#" + AGENT_SHELL_ID, "outerHTML")
            : entry.canView()
                ? agentButton("View", "hx-get", base + "/viewer?path=" + url(entry.path()), "#" + AGENT_MODAL_ID, "innerHTML")
                : "";
        String mutations = isReadOnlyRuntimePath(entry.path()) ? ""
            : agentModalButton("Rename", encodedAgentId, "rename", entry.path())
                + agentModalButton("Delete", encodedAgentId, "delete", entry.path());
        return """
            <tr class="workspace-file-row%s" data-workspace-file-path="%s">
              <td class="workspace-file-name">%s</td>
              <td>%s</td><td>%s</td><td>%s</td><td>%s</td><td>%s</td>
              <td class="workspace-file-row-actions">%s%s%s</td>
            </tr>
            """.formatted(
            selected,
            escapeAttribute(entry.path()),
            nameAction,
            escape(entry.fileType()),
            escape(entry.sizeLabel()),
            time(entry.createdAt()),
            time(entry.modifiedAt()),
            agentTags(encodedAgentId, entry.path(), entry.tags(), false),
            open,
            mutations,
            ""
        );
    }

    private static String agentCreateFolderForm(String encodedAgentId, String path) {
        return """
            <form class="workspace-file-stack-form" hx-post="%s/create-folder" hx-target="#%s" hx-swap="innerHTML">
              <input type="hidden" name="path" value="%s">
              <input type="text" name="name" placeholder="Folder name">
              <button type="submit" class="button">Create Folder</button>
            </form>
            """.formatted(agentFilesRoute(encodedAgentId, ""), AGENT_MODAL_ID, escapeAttribute(path));
    }

    private static String agentCreateTextForm(String encodedAgentId, String action, String path) {
        String kind = "create-markdown".equals(action) ? "create-markdown" : "create-text";
        String label = "create-markdown".equals(action) ? "Create Markdown" : "Create Text File";
        return """
            <form class="workspace-file-stack-form" hx-post="%s/%s" hx-target="#%s" hx-swap="innerHTML">
              <input type="hidden" name="path" value="%s">
              <input type="text" name="fileName" placeholder="File name">
              <button type="submit" class="button">%s</button>
            </form>
            """.formatted(agentFilesRoute(encodedAgentId, ""), kind, AGENT_MODAL_ID, escapeAttribute(path), escape(label));
    }

    private static String agentForm(String encodedAgentId, String route, String path, String field, String placeholder, String label) {
        return """
            <form class="workspace-file-stack-form" hx-post="%s%s" hx-target="#%s" hx-swap="innerHTML">
              <input type="hidden" name="path" value="%s">
              <input type="text" name="%s" placeholder="%s">
              <button type="submit" class="button">%s</button>
            </form>
            """.formatted(agentFilesRoute(encodedAgentId, ""), route, AGENT_MODAL_ID, escapeAttribute(path), escapeAttribute(field), escapeAttribute(placeholder), escape(label));
    }

    private static String agentCopyMoveForm(String encodedAgentId, String action, String path) {
        String label = "copy".equals(action) ? "Copy" : "Move";
        String prefix = "modal-" + action;
        return """
            <form class="workspace-file-stack-form" data-file-action="%s" hx-post="%s/%s" hx-target="#%s" hx-swap="innerHTML">
              <input type="hidden" name="path" value="%s">
              <label for="%s-destination">%s destination directory</label>
              <input id="%s-destination" type="text" name="destination" placeholder="%s destination directory" aria-label="%s destination directory" required>
              <label for="%s-name">Optional new name</label>
              <input id="%s-name" type="text" name="name" placeholder="Optional new name" aria-label="%s optional new name">
              <button type="submit" class="button" data-file-action-submit="%s">%s</button>
            </form>
            """.formatted(
            escapeAttribute(action),
            agentFilesRoute(encodedAgentId, ""), action, AGENT_MODAL_ID,
            escapeAttribute(path),
            prefix, escape(label),
            prefix, escapeAttribute(label), escapeAttribute(label),
            prefix,
            prefix, escapeAttribute(label),
            escapeAttribute(action), escape(label)
        );
    }

    private static String agentInlineCopyMoveForm(String encodedAgentId, String action, String path) {
        String label = "copy".equals(action) ? "Copy" : "Move";
        String prefix = "inspect-" + action;
        return """
            <details class="workspace-file-operation-group" open>
              <summary>%s</summary>
              <form class="workspace-file-stack-form" data-file-action="%s" hx-post="%s/%s" hx-target="#%s" hx-swap="innerHTML">
                <input type="hidden" name="path" value="%s">
                <label for="%s-destination">%s destination directory</label>
                <input id="%s-destination" type="text" name="destination" placeholder="%s destination directory" aria-label="%s destination directory" required>
                <label for="%s-name">Optional new name</label>
                <input id="%s-name" type="text" name="name" placeholder="Optional new name" aria-label="%s optional new name">
                <button type="submit" class="button small" data-file-action-submit="%s">%s</button>
              </form>
            </details>
            """.formatted(
            escape(label),
            escapeAttribute(action),
            agentFilesRoute(encodedAgentId, ""), action, AGENT_MODAL_ID,
            escapeAttribute(path),
            prefix, escape(label),
            prefix, escapeAttribute(label), escapeAttribute(label),
            prefix,
            prefix, escapeAttribute(label),
            escapeAttribute(action), escape(label)
        );
    }

    private static String agentDeleteForm(String encodedAgentId, String action, String path, WorkspaceExplorerService.DeletePreflight preflight) {
        String base = agentFilesRoute(encodedAgentId, "");
        if ("delete-recursive".equals(action)
            || (preflight != null && preflight.requiredStep() == WorkspaceExplorerService.DeleteStep.FILE_CONFIRM)) {
            String step = "delete-recursive".equals(action) ? "DIRECTORY_RECURSIVE_CONFIRM" : "FILE_CONFIRM";
            return """
                <p>Delete %s?</p>
                <form class="workspace-file-stack-form" hx-post="%s/delete" hx-target="#%s" hx-swap="innerHTML">
                  <input type="hidden" name="path" value="%s">
                  <input type="hidden" name="step" value="%s">
                  <button type="submit" class="button">Confirm Delete</button>
                </form>
                """.formatted(escape(fileName(path)), base, AGENT_MODAL_ID, escapeAttribute(path), step);
        }
        if (preflight != null && preflight.requiredStep() == WorkspaceExplorerService.DeleteStep.DIRECTORY_RECURSIVE_CONFIRM) {
            return """
                <p>This directory contains %d entries.</p>
                <button type="button" class="button" hx-get="%s/modal/delete-recursive?path=%s" hx-target="#%s" hx-swap="innerHTML">Confirm Recursive Delete</button>
                """.formatted(preflight.candidateCount(), base, url(path), AGENT_MODAL_ID);
        }
        return "<p>Delete is unavailable for this path.</p>";
    }

    private static String agentTextEditor(String encodedAgentId, WorkspaceExplorerService.FilePreview preview, boolean markdown) {
        String base = agentFilesRoute(encodedAgentId, "");
        String tabs = markdown
            ? "<button type=\"button\" class=\"button button-secondary small\" hx-get=\"" + base + "/text?path=" + url(preview.path())
                + "&tab=rendered\" hx-target=\"#" + AGENT_MODAL_ID
                + "\" hx-swap=\"innerHTML\">Rendered</button><span class=\"workspace-file-tab-active\">Text</span>"
            : "<span class=\"workspace-file-tab-active\">Text</span>";
        return """
            <div class="workspace-file-viewer" data-viewer-kind="%s" data-active-tab="text">
              <div class="workspace-file-tabs">%s</div>
              <form class="workspace-file-stack-form" hx-put="%s/save-text" hx-target="#%s" hx-swap="innerHTML">
                <input type="hidden" name="path" value="%s">
                <textarea name="content" rows="14">%s</textarea>
                <button type="submit" class="button">Save File</button>
              </form>
            </div>
            """.formatted(markdown ? "markdown" : "text", tabs, base, AGENT_MODAL_ID,
            escapeAttribute(preview.path()), escape(preview.content() == null ? "" : preview.content()));
    }

    private static String agentModal(String encodedAgentId, String title, String body, boolean clear) {
        if (clear) {
            return "<div id=\"" + AGENT_MODAL_ID + "\" hx-swap-oob=\"true\"></div>";
        }
        String closeUrl = agentFilesRoute(encodedAgentId, "/modal/close");
        return """
            <div class="workspace-file-modal" role="dialog" aria-modal="true">
              <div class="workspace-file-modal-panel">
                <div class="workspace-file-modal-header"><h2>%s</h2><button type="button" class="button button-secondary small" hx-get="%s" hx-target="#%s" hx-swap="innerHTML">Close</button></div>
                %s
              </div>
            </div>
            """.formatted(escape(title), closeUrl, AGENT_MODAL_ID, body);
    }

    private static String agentUpButton(WorkspaceExplorerService.DirectoryListing listing, String encodedAgentId) {
        String path = pathOrRoot(listing.path());
        if (".".equals(path)) {
            return "";
        }
        return agentButton("Up", "hx-get", agentFilesRoute(encodedAgentId, "/list?path=" + url(parentPath(path))), "#" + AGENT_SHELL_ID, "outerHTML");
    }

    private static String agentBreadcrumbs(WorkspaceExplorerService.DirectoryListing listing, String encodedAgentId) {
        String path = pathOrRoot(listing.path());
        StringBuilder out = new StringBuilder();
        String base = agentFilesRoute(encodedAgentId, "");
        out.append("<button type=\"button\" class=\"button button-link small\" hx-get=\"")
            .append(base)
            .append("/list?path=.\" hx-target=\"#")
            .append(AGENT_SHELL_ID)
            .append("\" hx-swap=\"outerHTML\">.</button>");
        if (".".equals(path)) {
            return out.toString();
        }
        StringBuilder current = new StringBuilder();
        for (String part : path.split("/")) {
            if (part.isBlank()) {
                continue;
            }
            if (!current.isEmpty()) {
                current.append('/');
            }
            current.append(part);
            out.append(" / <button type=\"button\" class=\"button button-link small\" hx-get=\"")
                .append(base)
                .append("/list?path=")
                .append(url(current.toString()))
                .append("\" hx-target=\"#")
                .append(AGENT_SHELL_ID)
                .append("\" hx-swap=\"outerHTML\">")
                .append(escape(part))
                .append("</button>");
        }
        return out.toString();
    }

    private static String agentTags(String encodedAgentId, String path, List<WorkspaceFileLabel> tags, boolean removable) {
        if (tags == null || tags.isEmpty()) {
            return "<span class=\"tag tag-muted\">No tags</span>";
        }
        StringBuilder out = new StringBuilder();
        int visible = removable ? tags.size() : Math.min(tags.size(), 3);
        for (WorkspaceFileLabel tag : tags.stream().limit(visible).toList()) {
            out.append("<span class=\"tag\">").append(escape(tag.slug()));
            if (removable && encodedAgentId != null) {
                out.append(" <button type=\"button\" class=\"button button-link small\" hx-delete=\"")
                    .append(agentFilesRoute(encodedAgentId, "/labels?path=" + url(path) + "&label=" + url(tag.slug())))
                    .append("\" hx-target=\"#")
                    .append(AGENT_INSPECTOR_ID)
                    .append("\" hx-swap=\"outerHTML\">x</button>");
            }
            out.append("</span>");
        }
        if (!removable && tags.size() > visible) {
            out.append("<span class=\"tag tag-muted\">+").append(tags.size() - visible).append("</span>");
        }
        return out.toString();
    }

    private static String agentReadOnlyText(WorkspaceExplorerService.FilePreview preview) {
        return """
            <div class="workspace-file-viewer" data-viewer-kind="text" data-read-only="true">
              <div class="workspace-file-status">Runtime-managed task and output files are read-only.</div>
              <pre class="workspace-file-read-only-text">%s</pre>
            </div>
            """.formatted(escape(preview.content() == null ? "" : preview.content()));
    }

    private static String agentDownloadLink(String apiPrefix, String path) {
        return "<a class=\"button button-secondary small\" href=\"" + apiPrefix
            + "/files/download?path=" + url(path) + "\">Download</a>";
    }

    static boolean isReadOnlyRuntimePath(String path) {
        String normalized = pathOrRoot(path);
        return "outputs".equals(normalized) || normalized.startsWith("outputs/")
            || "tasks".equals(normalized) || normalized.startsWith("tasks/");
    }

    private static String agentButton(String label, String hxVerb, String route, String target, String swap) {
        return """
            <button type="button" class="button button-secondary small" %s="%s" hx-target="%s" hx-swap="%s">%s</button>
            """.formatted(hxVerb, route, target, swap, escape(label));
    }

    private static String agentModalButton(String label, String encodedAgentId, String action, String path) {
        return agentButton(label, "hx-get", agentFilesRoute(encodedAgentId, "/modal/" + action + "?path=" + url(path)), "#" + AGENT_MODAL_ID, "innerHTML");
    }
}
