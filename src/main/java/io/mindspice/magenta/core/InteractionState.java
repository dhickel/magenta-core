package io.mindspice.magenta.core;

/** Durable state reported when an interaction operation cannot be applied. */
public enum InteractionState {
    MISSING,
    PENDING,
    ANSWERED,
    CANCELLED
}
