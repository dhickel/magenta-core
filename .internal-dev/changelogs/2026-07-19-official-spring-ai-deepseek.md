# Date
2026-07-19

# Change Summary
Replaced the unavailable local `spring-ai-openai:1.1.4-magenta1` fork with released Spring AI 2.0.0 and its official dedicated DeepSeek provider. Updated Magenta for Spring Boot 4, Spring AI 2 immutable tool options and explicit tool execution, Jackson 3 web/tool JSON behavior, and the provider-specific DeepSeek reasoning message type.

# Files
- `pom.xml` — uses Spring Boot 4.0.7, Spring AI 2.0.0, official `spring-ai-deepseek`, and Boot 4 test starters.
- `src/main/java/io/mindspice/magenta2/ai/chat/` — dedicated DeepSeek routing/reasoning extraction and Spring AI 2 API adaptations.
- `src/main/java/io/mindspice/magenta2/core/config/ClassicJacksonConfiguration.java` — retains the existing Jackson 2 application mapper and configures compatible Jackson 3 primitive request binding.
- `src/main/java/io/mindspice/magenta2/ai/chat/tool/ToolArgumentCoercionModule.java` and service registration — preserves scalar-to-list tool argument coercion with Spring AI 2's Jackson 3 mapper.
- Tests, technical configuration docs, service/deferred/decision specifications, and Spring AI knowledge records were updated with the new contract.

# Behavioral Impact
- DeepSeek models now use the official `DeepSeekChatModel`; their reasoning content is extracted from `DeepSeekAssistantMessage` and replayed by Spring AI during subsequent tool-call requests.
- Builds no longer depend on an unpublished artifact in one developer's local Maven repository.
- The git-ignored local startup config contains only `deepseek-v4-pro` and `deepseek-v4-flash`; Pro is the default/planning model and Flash handles summaries/compaction. Its API-key placeholder is left for the operator to replace locally.
- Magenta continues to own its explicit, bounded tool execution loops, consistent with Spring AI 2 raw model-call behavior.
- Existing REST request payloads that omit optional primitive fields retain their prior binding behavior, and scalar tool arguments still coerce to one-item collections.

# Specification Impact
`services.md` now requires dedicated DeepSeek routing, `decisions.md` records the official-provider/Boot 4 baseline, and completed deferred item `DEFERRED-20260525-08` was removed from the accepted-future register.

# Risks
- The migration advances Spring Boot, Spring Framework, Spring AI, and web JSON processing together; full tests and application startup are required validation.
- Spring AI's broader streaming or prompt-mutation reasoning preservation may evolve independently. Magenta currently relies on the verified synchronous explicit tool-loop path.

# Validation
- `mvn test` — 890 tests, 0 failures, 0 errors, 0 skipped.
- `mvn dependency:tree -Dincludes=org.springframework.ai` — all Spring AI dependencies resolve to official 2.0.0 artifacts, including `spring-ai-deepseek`; no local fork remains.
- Focused DeepSeek request regression — official `DeepSeekChatModel` replays `DeepSeekAssistantMessage.reasoningContent` with assistant tool calls.
- Bounded `mvn spring-boot:run` with a credential-free temporary AI config — application and random-port Tomcat context started successfully and shut down cleanly at the timeout.

# Follow-up Items
- Reassess upstream DeepSeek reasoning behavior before changing Magenta to streaming tool execution or rebuilding provider assistant messages as generic messages.
- No bug, plan, or review artifact was created for this scoped dependency migration.
