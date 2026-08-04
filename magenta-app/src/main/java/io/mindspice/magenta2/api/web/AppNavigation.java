package io.mindspice.magenta2.api.web;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import io.mindspice.simplypages.builders.TopNavBuilder;
import io.mindspice.simplypages.components.navigation.SideNav;
import io.mindspice.simplypages.core.Component;

/**
 * Single-source application navigation: top-nav, side-nav, and the canonical
 * project console tab registry with grouped order, deep-link mapping, host
 * IDs, active state, and availability.
 */
final class AppNavigation {
    static final String OPERATIONAL_CSS = "/css/orchestration.css?v=15";

    private AppNavigation() {
    }

    // ════════════════════════════════════════════════════════════════
    //  Top and side navigation
    // ════════════════════════════════════════════════════════════════

    static Component primaryTopNav() {
        return TopNavBuilder.create()
            .withHtmxNavigation(false)
            .addPrimaryLink("Home", "/")
            .addPrimaryLink("Dashboard", "/dashboard")
            .addPrimaryLink("Chat", "/chat")
            .build();
    }

    static SideNav operationalSideNav(String activePath) {
        SideNav nav = SideNav.create();
        nav.addSection("Orchestration");
        nav.addItem("Dashboard", "/dashboard", isActivePath(activePath, "/dashboard"));
        nav.addItem("Plans", "/plans", isActivePath(activePath, "/plans"));
        nav.addItem("Workflows", "/workflows", isActivePath(activePath, "/workflows"));
        nav.addItem("Jobs", "/jobs", isActivePath(activePath, "/jobs"));
        nav.addItem("Projects", "/projects", isActivePath(activePath, "/projects"));
        nav.addSection("Communication");
        nav.addItem("Inbox", "/inbox", isActivePath(activePath, "/inbox"));
        nav.addItem("Agents", "/agents", isActivePath(activePath, "/agents"));
        nav.addSection("Tools");
        nav.addItem("Skills", "/skills", isActivePath(activePath, "/skills"));
        nav.addItem("Outputs", "/outputs", isActivePath(activePath, "/outputs"));
        nav.addItem("Settings", "/settings", isActivePath(activePath, "/settings"));
        return nav;
    }

    private static boolean isActivePath(String activePath, String navPath) {
        return activePath != null && (activePath.equals(navPath) || activePath.startsWith(navPath + "/"));
    }

    // ════════════════════════════════════════════════════════════════
    //  Canonical project console tab registry
    // ════════════════════════════════════════════════════════════════

    /**
     * Immutable tab descriptor: group, label, key, availability, and stable
     * host-target mapping. The key is the canonical deep-link token and the
     * route suffix; the host id is the single stable HTMX target for tab
     * content.
     */
    public record ProjectTab(String group, String label, String key, boolean available) {
        public String route(String projectId) {
            return "/projects/_detail/" + e(projectId) + "/" + key;
        }

        public String hostId() {
            return "project-tab-panel";
        }

        /** Deep-link path segment for browser-history coordination. */
        public String deepLinkSegment() {
            return key;
        }
    }

    /** Ordered registry: 5 groups, 11 tabs. Chat is a persistent accordion above the tabs and is excluded from tab navigation. */
    static final List<ProjectTab> TAB_REGISTRY = List.of(
        new ProjectTab("overview",   "Dashboard",  "dashboard",  true),
        new ProjectTab("work",       "Queue",      "queue",      true),
        new ProjectTab("work",       "Tasks",      "tasks",      true),
        new ProjectTab("work",       "Jobs",       "jobs",       true),
        new ProjectTab("work",       "Outputs",    "outputs",    true),
        new ProjectTab("context",    "Files",      "files",      true),
        new ProjectTab("context",    "Chat",       "chat",       true),
        new ProjectTab("automation", "Schedules",  "schedules",  true),
        new ProjectTab("automation", "Events",     "events",     true),
        new ProjectTab("admin",      "Members",    "members",    true),
        new ProjectTab("admin",      "Settings",   "settings",   true)
    );

    /** Tabs to render in the flat navigation bar (excludes Chat which is a persistent accordion). */
    static final java.util.Set<String> TAB_NAV_EXCLUDE = java.util.Set.of("chat");

    /** Ordered group keys for rendering. */
    static final List<String> GROUP_ORDER = List.of("overview", "work", "context", "automation", "admin");

    static final Map<String, String> GROUP_LABELS = Map.of(
        "overview",   "Overview",
        "work",       "Work",
        "context",    "Context",
        "automation", "Automation",
        "admin",      "Administration"
    );

    /** Return the ordered list of project-console tabs for the registry. */
    static List<ProjectTab> consoleTabs() {
        return TAB_REGISTRY;
    }

    /** Lookup a tab by its canonical key. */
    static Optional<ProjectTab> findTab(String key) {
        return TAB_REGISTRY.stream().filter(tab -> tab.key().equals(key)).findFirst();
    }

    /** Default active tab. */
    static ProjectTab defaultTab() {
        return findTab("dashboard").orElseThrow();
    }

    /**
     * Return tabs grouped by their owning group, in GROUP_ORDER. Each
     * entry maps a group key to its ordered tabs.
     */
    static Map<String, List<ProjectTab>> groupedTabs() {
        Map<String, List<ProjectTab>> groups = new LinkedHashMap<>();
        for (String group : GROUP_ORDER) {
            List<ProjectTab> tabs = TAB_REGISTRY.stream()
                .filter(t -> t.group().equals(group))
                .toList();
            if (!tabs.isEmpty()) groups.put(group, tabs);
        }
        return groups;
    }

    /** Deep-link URL for a project+tab pair (browser-history coordination). */
    static String deepLink(String projectId, String tabKey) {
        return "/projects/" + e(projectId) + "?tab=" + e(tabKey);
    }

    /** Resolve a raw tab parameter to a canonical key, falling back to default. */
    static String resolveTabKey(String raw) {
        return findTab(raw).map(ProjectTab::key).orElseGet(() -> defaultTab().key());
    }

    // ── Shared display helpers ──
    static String e(String value) {
        return URLEncoder.encode(value != null ? value : "", StandardCharsets.UTF_8).replace("+", "%20");
    }
}
