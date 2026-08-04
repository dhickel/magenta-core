package io.mindspice.magenta2.api.web.selector;

public record EntityValidation(
    String kind,
    String id,
    boolean exists,
    String label,
    String message
) {}
