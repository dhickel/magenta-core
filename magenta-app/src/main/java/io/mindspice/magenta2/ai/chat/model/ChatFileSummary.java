package io.mindspice.magenta2.ai.chat.model;

import java.time.Instant;

public record ChatFileSummary(
    String relativePath,
    String fileName,
    String extension,
    String formatLabel,
    long sizeBytes,
    Instant lastModified
) { }
