package io.mindspice.magenta2.api.web;

import io.mindspice.simplypages.builders.TopNavBuilder;
import io.mindspice.simplypages.components.navigation.SideNav;
import io.mindspice.simplypages.core.Component;

final class AppNavigation {
    static final String OPERATIONAL_CSS = "/css/orchestration.css?v=15";

    private AppNavigation() {
    }

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
}
