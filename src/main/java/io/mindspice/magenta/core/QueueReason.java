package io.mindspice.magenta.core;

/** Why a caller submission was placed in the durable session queue. */
public enum QueueReason {
    /** The caller explicitly requested queueing. */
    EXPLICIT,
    /** The target session already owns another execution. */
    SESSION_ACTIVE,
    /** The target session is waiting for answers to a pending interaction. */
    AWAITING_QUESTIONS
}
