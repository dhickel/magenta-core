# Provider Visibility Boundary Repair

## Date
2026-08-24

## Change Summary
Moved the package-private provider adapters, model ownership handle, HTTP client builder, and Spring AI tool callback adapter into the router's internal package so Java package access matches the intended visibility boundary.

## Files
- `src/main/java/io/mindspice/magenta/core/internal/DefaultModelRouter.java`
- `src/main/java/io/mindspice/magenta/core/internal/{DeepSeekProviderAdapter,OllamaProviderAdapter,OpenAiProviderAdapter,OwnedChatModel,ProviderHttpClientBuilders,SpringAiToolCallbackAdapter}.java`

## Behavioral Impact
No routing behavior or consumer API changed. Internal provider composition compiles while the concrete provider and Spring AI adaptation/resource types remain package-private.

## Specification Impact
Specification Impact: none. The repair enforces the existing branch contract that provider-specific Spring AI implementation types remain internal.

## Risks
Package-private access now covers the broader `io.mindspice.magenta.core.internal` package rather than a provider-only subpackage. Public internal router interfaces still expose Spring AI types and remain a separate boundary concern.

## Follow-up Items
None required for this compile repair.
