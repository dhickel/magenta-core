# Magenta tool parameter names

## Date

2026-08-24

## Change Summary

Added an explicit Magenta `@ToolParam(name = "...")` contract and made annotated-tool schema generation and invocation use the same stable resolved name when consumer bytecode lacks Java parameter metadata.

## Files

- `src/main/java/io/mindspice/magenta/core/ToolParam.java`
- `src/main/java/io/mindspice/magenta/core/DefaultToolRegistry.java`
- `src/main/java/io/mindspice/magenta/core/example/EchoTool.java`
- `src/main/java/io/mindspice/magenta/core/example/DateTimeTool.java`
- `src/test/java/io/mindspice/magenta/core/MagentaToolRegistryTest.java`
- `docs/api/tools-and-interactions.md`
- `.internal-dev/knowledge/java-reflection-tool-parameters.md`

## Behavioral Impact

Consumers can register Magenta-owned annotated tool beans without compiling with `-parameters` by declaring stable parameter names in `@ToolParam`. Registration now fails clearly instead of publishing synthetic `arg0` schema properties when neither an annotation name nor retained reflection name exists.

## Specification Impact

None. This repairs the implementation to satisfy the accepted Magenta-owned tool annotation and clean public-boundary contract.

## Risks

Existing annotated tool parameters compiled without `-parameters` must add `@ToolParam(name = "...")`; accepting synthetic names was not a usable contract.

## Follow-up Items

None.
