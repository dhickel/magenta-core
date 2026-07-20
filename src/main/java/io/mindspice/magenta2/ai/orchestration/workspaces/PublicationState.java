package io.mindspice.magenta2.ai.orchestration.workspaces;

/** Closed publication state namespace. */
public enum PublicationState {
    RESERVED,
    STAGED,
    PUBLISHED,
    FAILED,
    INTEGRITY_FAILED
}
