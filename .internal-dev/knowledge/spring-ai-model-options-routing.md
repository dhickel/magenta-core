# Topic
Endpoint-polymorphic model and options routing in Spring AI 2.0

# Source References
- `ChatModelRouter.java` — the single routing point for model construction and options
- Spring AI 2.0.0 `OllamaChatOptions`, `OpenAiChatOptions`, and `DeepSeekChatOptions`
- Spring AI DeepSeek reference: <https://docs.spring.io/spring-ai/reference/api/chat/deepseek-chat.html>

# Key Takeaways
1. `ToolCallingChatOptions` is the caller-facing polymorphic type used by all three configured providers. Callers should not import provider-specific option classes.
2. `EndpointType.DEEPSEEK` must build the dedicated `DeepSeekChatModel` and `DeepSeekChatOptions`. This preserves typed reasoning content across tool-call turns.
3. The maintained DeepSeek fork adds typed thinking controls. At the router boundary, Magenta maps `thinkLevel` 1-3 to enabled thinking with `high` reasoning effort and level 4 to enabled thinking with `max`; zero/unset leaves the DeepSeek API default unchanged.
4. `EndpointType.OPENAI_COMPATIBLE` uses `OpenAiChatModel`, while `OLLAMA` uses `OllamaChatModel`. Base URL, API key, timeout, and remote model name are resolved only at the router boundary.
5. Model map keys are Magenta aliases. `remoteModelName` is the provider model sent over the wire, so UI and API callers should prefer aliases while compatibility lookup may still accept remote names.
6. Spring AI 2.0 tool callbacks are supplied with immutable options mutation. Raw model calls do not internally execute tools; Magenta owns the bounded tool execution loop.
7. Persisted runtime settings can outlive alias renames. Reads accept a value that matches any configured `remoteModelName`, while settings writes continue to require a canonical alias.

# Engine Relevance
- Add each new endpoint variant to model construction and option construction in `ChatModelRouter`.
- Keep DeepSeek High/Max variants as separate aliases pointing to the same remote model name with different `thinkLevel` values.
- Resolve stale persisted remote names before rendering `/chat`; let `ChatService.modelSelectionKey()` choose the configured default alias when several aliases share that remote name.
- Keep provider SDK types out of chat, planning, and advisor callers.
- When upgrading Spring AI, regression-test provider routing, DeepSeek reasoning extraction, tool callback options, scalar-to-list tool argument coercion, and application startup.

# Open Questions
- Should provider-specific router helpers be package-private to enforce the boundary?
- Should a future Anthropic endpoint use its dedicated Spring AI provider once a concrete user workflow requires it?
