# Topic

Agent side-panel chat immutable binding ownership.

## Source References

- `src/main/java/io/mindspice/magenta2/ai/chat/service/RequestResolver.java`
- `src/main/java/io/mindspice/magenta2/ai/chat/service/ChatService.java`
- `src/test/java/io/mindspice/magenta2/ai/chat/service/RequestResolverAgentBindingTest.java`
- `.internal-dev/specifications/architecture.md` (`ARCH-20260727-02`)

## Key Takeaways

- A new agent side-panel conversation must capture its immutable context with the server-resolved route agent, not the global default agent.
- An existing side-panel conversation must verify that its durable conversation owner link resolves to a binding owned by the route agent; it must fail rather than retarget or reuse another agent's binding.
- The binding key remains a supplemental prompt-context fact. It does not replace the independently selected execution descriptor.

## Engine Relevance

Use `RequestResolver.resolveForAgent` for agent-scoped chat entry points. Normal `/chat` continues to use the default-agent capture path; neither path accepts a caller-supplied binding owner.

## Open Questions

- None.
