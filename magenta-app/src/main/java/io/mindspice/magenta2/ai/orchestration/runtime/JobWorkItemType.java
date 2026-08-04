package io.mindspice.magenta2.ai.orchestration.runtime;

/**
 * Discriminator for the kind of work a job item represents.
 */
public enum JobWorkItemType {
    /** Run a finalized plan/task definition. */
    PLAN,
    /** Run a workflow definition. */
    WORKFLOW
}
