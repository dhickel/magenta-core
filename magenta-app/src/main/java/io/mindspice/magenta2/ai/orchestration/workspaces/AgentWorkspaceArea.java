package io.mindspice.magenta2.ai.orchestration.workspaces;

import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Closed set of eight lazy conceptual areas beneath an AGENT workspace root.
 *
 * <p>Personal content is limited to {@code home}, {@code scripts}, and
 * {@code apps}.  Scripts and apps are storage only — they grant no execution
 * authority.  All other areas are reserved to their existing or future owners;
 * this phase grants no materialization, repository, task, skill, or output
 * capability.
 */
public enum AgentWorkspaceArea {

    /** Reserved: skill discovery, trust, snapshot, and activation owner. */
    SKILLS(    ".skills",  Ownership.RESERVED),

    /** Personal: ordinary agent-writable scripts storage (no execution). */
    SCRIPTS(   "scripts",  Ownership.PERSONAL),

    /** Personal: ordinary agent-writable apps storage (no execution). */
    APPS(      "apps",     Ownership.PERSONAL),

    /** Personal: ordinary agent-writable home content. */
    HOME(      "home",     Ownership.PERSONAL),

    /** Reserved: managed-Git and repository-control owner. */
    REPOS(     "repos",    Ownership.RESERVED),

    /** Reserved: materialization owner. */
    PROJECTS(  "projects", Ownership.RESERVED),

    /** Reserved: task-run lifecycle and publication owner. */
    TASKS(     "tasks",    Ownership.RESERVED),

    /** Reserved: output-publication owner. */
    OUTPUTS(   "outputs",  Ownership.RESERVED);

    public enum Ownership { PERSONAL, RESERVED }

    private final String directoryName;
    private final Ownership ownership;

    private static final Map<String, AgentWorkspaceArea> BY_NAME =
        Stream.of(values()).collect(Collectors.toUnmodifiableMap(
            a -> a.directoryName, a -> a));

    AgentWorkspaceArea(String directoryName, Ownership ownership) {
        this.directoryName = directoryName;
        this.ownership = ownership;
    }

    /** Filesystem directory name (e.g. {@code "home"}). */
    public String directoryName() {
        return directoryName;
    }

    /** Whether this area accepts ordinary personal writes. */
    public Ownership ownership() {
        return ownership;
    }

    public boolean isPersonal() {
        return ownership == Ownership.PERSONAL;
    }

    public boolean isReserved() {
        return ownership == Ownership.RESERVED;
    }

    /**
     * Look up an area by its exact directory name.
     *
     * @return the area, or {@link Optional#empty()} when the name does not
     *         correspond to a canonical area.
     */
    public static Optional<AgentWorkspaceArea> fromDirectoryName(String name) {
        return Optional.ofNullable(BY_NAME.get(name));
    }
}
