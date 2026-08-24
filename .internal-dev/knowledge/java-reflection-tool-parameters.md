# Java reflection tool parameters

## Topic

Stable parameter names for reflection-based Magenta tool registration.

## Source References

- `src/main/java/io/mindspice/magenta/core/ToolParam.java`
- `src/main/java/io/mindspice/magenta/core/DefaultToolRegistry.java`
- `src/test/java/io/mindspice/magenta/core/MagentaToolRegistryTest.java`

## Key Takeaways

Java reflection returns synthetic names such as `arg0` when consumer classes are compiled without `-parameters`. A reflection-based public tool contract must not use `Parameter.getName()` unconditionally. Magenta uses `@ToolParam(name = "...")` as the stable schema and invocation name, falls back to reflection metadata only when it is actually present, and rejects registration clearly when neither source exists.

## Engine Relevance

Schema property names and invocation lookup must use the same resolved name. Tests should compile without parameter metadata and assert both the generated schema and callback invocation.

## Open Questions

None.
