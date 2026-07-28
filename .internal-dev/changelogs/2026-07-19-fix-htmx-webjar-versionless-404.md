# fix-htmx-webjar-versionless-404

## Date

2026-07-19



## Git Commit

2e42aed04d0742a52f741837d460f6871706836b



## Change Summary

Fixed the `/webjars/htmx.org/dist/htmx.min.js` 404 that broke all HTMX interactions in the browser. The versionless webjar path returned 404 while the versioned path (`/webjars/htmx.org/1.9.10/dist/htmx.min.js`) returned 200.

### Root Cause

Spring Boot 4.0.7 (Spring Framework 7.x) replaced `WebJarsResourceResolver` with `LiteWebJarsResourceResolver` but the auto-configuration no longer adds any webjar resolver to the resource chain. Versionless webjar resolution is completely missing from Spring Boot 4.0.7's `ResourceChainCustomizerConfiguration`.

### Fix

Placed `htmx.min.js` at the exact classpath location matching the versionless path: `src/main/resources/META-INF/resources/webjars/htmx.org/dist/htmx.min.js`. Spring Boot's default `PathResourceResolver` finds this directly without needing versionless resolution. The webjar dependency (`org.webjars.npm:htmx.org:1.9.10`) is retained for the versioned path and extensions.

## Files

- **Added**: `src/main/resources/META-INF/resources/webjars/htmx.org/dist/htmx.min.js` — htmx 1.9.10 minified JS placed at the versionless classpath location

## Behavioral Impact

- `/webjars/htmx.org/dist/htmx.min.js` now returns HTTP 200 with valid JavaScript (was 404)
- All HTMX interactions in the browser work correctly
- Versioned path `/webjars/htmx.org/1.9.10/dist/htmx.min.js` continues to work
- No code changes required; SimplyPages' hardcoded versionless webjar path resolves correctly

## Specification Impact

none — this is a static resource workaround for a Spring Boot 4.0.7 regression; no API, service, or architecture contracts changed

## Risks

- If SimplyPages later changes its htmx path, this static file would need to be moved
- If htmx.org is upgraded, this static file must be updated to match (currently pinned to 1.9.10)

## Follow-up Items

- Monitor Spring Boot 4.x releases for a fix to the `LiteWebJarsResourceResolver` auto-configuration gap; if/when fixed, this static file can be removed
- Consider contributing a fix upstream to Spring Boot 4.x to restore webjar versionless resolution in `ResourceChainCustomizerConfiguration`
