package io.mindspice.magenta2.ai.chat.model;

import java.util.List;

public record ChatFileListing(
    String conversationId,
    int count,
    boolean truncated,
    List<ChatFileSummary> files
) { }
