package io.mindspice.magenta2.ai.chat.plan;

public interface SavedPlanModelClient {
    void runTurn(String planId, String model, String systemPrompt, String userMessage);
}
