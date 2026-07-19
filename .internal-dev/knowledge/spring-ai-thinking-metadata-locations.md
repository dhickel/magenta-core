# Topic
Spring AI thinking/reasoning representations across providers

# Source References
- Spring AI 2.0.0 `DeepSeekChatModel` and `DeepSeekAssistantMessage`
- Spring AI 2.0.0 `OllamaChatModel` generation metadata
- Spring AI DeepSeek reference: <https://docs.spring.io/spring-ai/reference/api/chat/deepseek-chat.html>
- `ChatService.thinkingText()` — Magenta's provider-normalization point

# Key Takeaways
- Spring AI 2.0.0 has a dedicated DeepSeek provider. Non-streaming DeepSeek responses use `DeepSeekAssistantMessage`, whose `getReasoningContent()` value is replayed as `reasoning_content` when that assistant message is included in a later request.
- Use the dedicated DeepSeek model for `EndpointType.DEEPSEEK`. The generic OpenAI-compatible model does not provide the same typed reasoning-message round trip and must not be substituted for DeepSeek tool loops.
- Reasoning still has no single provider-neutral representation. Magenta checks `DeepSeekAssistantMessage.getReasoningContent()`, Ollama generation metadata `thinking`, and legacy/generic assistant properties `reasoningContent`.
- Magenta persists normalized thinking under the `magenta.thinking` assistant-message property. `ChatService.thinkingText()` remains the canonical extraction point.
- Spring AI 2.0 raw `ChatModel.call()` and `stream()` no longer execute tools internally. Magenta's explicit, bounded tool loop remains responsible for execution and preserves DeepSeek assistant message subtypes between turns.
- The maintained `dhickel/spring-ai` DeepSeek extension supports request-side `thinking.type` and `reasoning_effort`. Magenta enables thinking for configured High/Max aliases while response extraction remains unchanged.

# Engine Relevance
- Keep DeepSeek assistant messages intact when appending a model response to the next tool-call prompt. Rebuilding them as plain `AssistantMessage` objects loses the typed reasoning field.
- Add any future provider-specific reasoning location only at the normalization boundary and cover it with a focused extraction test.
- The former local `spring-ai-openai:1.1.4-magenta1` patch is obsolete and must not be restored. The maintained fork extends the dedicated Spring AI 2 DeepSeek module instead.

# Open Questions
- Spring AI PR #5908 remains relevant to broader streaming and prompt-mutation preservation cases; reassess if Magenta changes from its synchronous explicit tool loop.
- Does a future provider-neutral reasoning content type supersede the current provider-specific extraction paths?
