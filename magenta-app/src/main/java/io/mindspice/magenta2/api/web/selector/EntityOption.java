package io.mindspice.magenta2.api.web.selector;

public record EntityOption(
    String kind,
    String id,
    String label,
    String detail,
    String status,
    boolean available
) {}
