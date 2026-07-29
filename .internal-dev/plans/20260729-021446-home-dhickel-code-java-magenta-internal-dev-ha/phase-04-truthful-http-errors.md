## Context

Agent-context lookup currently treats runtime failure as absence, chat-file handling treats I/O failure as absence, and global advice turns all illegal state into conflict. Correct those endpoint-local meanings without changing routes, payload fields, or JSON/HTMX fragment shapes.

## Goal

map absence, validation, conflicts, bounds, and failures truthfully

## In Scope

**Write Targets**: src/main/java/io/mindspice/magenta2/ai/orchestration/agents/AgentProfileService.java, src/main/java/io/mindspice/magenta2/ai/chat/service/ChatFileService.java, src/main/java/io/mindspice/magenta2/api/web/AgentContextController.java, src/main/java/io/mindspice/magenta2/api/web/ChatFileController.java, src/main/java/io/mindspice/magenta2/api/web/GlobalExceptionHandler.java, src/test/java/io/mindspice/magenta2/api/web/AgentContextControllerTest.java, src/test/java/io/mindspice/magenta2/api/web/ChatFileControllerTest.java, src/test/java/io/mindspice/magenta2/api/web/GlobalExceptionHandlerTest.java

## Out of Scope

- Route, DTO, payload-field, JSON-body, or HTMX-fragment shape changes.
- Unrelated controller exception taxonomy, JavaScript, UI work, schema work, and runtime workspace-scope API drift.
- Broad controller/package refactoring or a new global mapping for a Java base exception class.

## Dependencies

none

## Constraints

- Preserve existing response and HTMX fragment shapes; do not add routes or payload fields.
- Do not disclose paths, stack traces, SQL/provider messages, raw I/O details, or exception messages in responses, logs, or fixtures.
- Preserve workspace/path validation, confinement, and regular-file checks in `ChatFileService`.
- Known conflicts retain `409` only through a concrete typed or endpoint-local mapping.
- This independent phase must pass its validator gate before the Phase 06 and Phase 07 dependent edits begin.

## Required Guides

- AGENTS.md
- .internal-dev/AGENTS.md
- .internal-dev/specifications/AGENTS.md
- src/main/java/io/mindspice/magenta2/ai/AGENTS.md
- src/main/java/io/mindspice/magenta2/ai/chat/service/AGENTS.md
- src/main/java/io/mindspice/magenta2/ai/orchestration/AGENTS.md
- src/main/java/io/mindspice/magenta2/api/AGENTS.md
- src/main/java/io/mindspice/magenta2/api/web/AGENTS.md

## Invariants

- `400`, `404`, `409`, `413`, `500`, and `503` each express the documented endpoint-local meaning; exception text never determines meaning.
- Only a validated, proven normal absence is `404`. Failed repository, filesystem, decoding, confinement, or infrastructure operations do not become absence.
- Existing descriptor/path confinement and regular-file checks remain in `ChatFileService`; controllers do not repeat filesystem checks.
- Known domain conflicts alone retain `409`. Unexpected `IllegalStateException` follows the safe generic `500` path.
- Responses, fragments, logs, and test fixtures do not disclose paths, stack traces, SQL/provider messages, raw I/O details, or injected sensitive strings.

## Implementation Steps

1. In `AgentProfileService`, add `find(String)` using the existing normal identifier validation before one repository lookup. Return `Optional.empty()` only for a normal missing profile. Do not catch `RuntimeException` or repository failure and convert it to empty; let a failure propagate, retaining an existing narrow unavailable type where one already represents temporary infrastructure unavailability. Introduce a narrow source-local typed unavailable outcome only if the current boundary has no type capable of distinguishing that condition from an unexpected failure.

   ```java
   validateAgentId(agentId);
   return repository.findById(agentId); // missing is empty; failure propagates
   ```

2. Change both agent-context response paths to use `find`. Map only `Optional.empty()` to their existing `404` JSON/fragment response. Map the established typed temporary-unavailable outcome to the existing safe `503` JSON/fragment response. Do not catch broad failures as absence, and preserve each endpoint's current successful and error response construction.

3. Preserve validation as `400` and the existing concrete agent-context conflict as `409`. Replace any message-based or broad `IllegalStateException` conflict decision in these paths with the actual domain conflict type. If the source does not establish a concrete conflict, do not invent a text classifier; let it use the safe generic failure path.

4. In `ChatFileService`, retain current path validation, authority-root confinement, and regular-file semantics while making absence narrow: an absent selected root, `NoSuchFile`/absent target established by the existing checked access path, or a non-regular target is the service's absence outcome. Invalid path remains validation. Permission denial, malformed/invalid encoding where applicable, failed attribute/read operations, and other I/O remain distinct failures; remove any catch that turns generic `IOException` into absence.

5. In `ChatFileController`, map service outcomes in specificity order: validation to `400`, proven absence to `404`, the existing size-limit outcome to `413`, typed temporary unavailability to `503`, then generic I/O or unexpected failure to safe `500`. If the unavailable type is an I/O subtype, ensure the `503` mapping is selected before the generic I/O mapping. Reuse current safe bodies/fragments and do not expose exception messages.

6. In `GlobalExceptionHandler`, remove the blanket `IllegalStateException -> 409` handler. Retain only handlers for established concrete active-conflict types. Ensure unexpected illegal state reaches the generic safe `500` response path. Preserve `ResponseStatusException`'s status and use null-safe established safe reason handling; never substitute an arbitrary exception message or infrastructure detail into the response.

7. Add focused MockMvc regression coverage in the three ledger test targets. Cover agent-context JSON and relevant HTMX fragment success-adjacent failures: invalid input (`400`), normal absence (`404`), concrete conflict (`409`), typed unavailable/context or repository failure (`503`), and unexpected failure (`500`). Cover chat-file invalid path (`400`), missing/non-regular file (`404`), size bound (`413`), typed unavailable (`503`), and generic I/O (`500`). Cover global advice's concrete conflict, unexpected illegal state, and null-reason `ResponseStatusException`. Assert status, existing response/fragment shape, and that injected path, SQL/provider, or I/O-sensitive strings are absent from bodies.

## Technical Guidance

- Prefer existing typed outcomes. A new type is justified only when it supplies one current, endpoint-relevant meaning that cannot otherwise be represented without text classification.
- Keep file classification at the operation that has authoritative filesystem facts. Do not use `exists` alone as proof of absence when it can suppress access failure.
- Preserve `ResponseStatusException` status behavior without allowing arbitrary supplied reasons to bypass safe-response rules.
- Do not add routes, payload types, global broad exception taxonomy, or fallback logic that scans/retries another root after a failed selected-root operation.

## Validation

- Run `AgentContextControllerTest`, `ChatFileControllerTest`, and `GlobalExceptionHandlerTest` through MockMvc in their Spring test configuration.
- Confirm the assertions exercise all six statuses and both agent-context response forms where both are exposed.
- Search `GlobalExceptionHandler` and edited controllers for broad `IllegalStateException` conflict mapping, `catch (RuntimeException)`-to-absence conversion, and generic-I/O-to-absence conversion.
- Verify response bodies/fragments reject injected sensitive path, provider, SQL, and raw-I/O strings.
- Run the affected focused Maven test selection and resolve any failure caused by these edits before the validator review-and-repair gate.

## Exit Criteria

- Agent-context and chat-file paths distinguish validation, scoped absence, concrete conflict, size bound, generic failure, and typed temporary unavailability as safe `400`, `404`, `409`, `413`, `500`, and `503` outcomes.
- Repository and file-operation failures are not misreported as absence; only proven absence is `404`.
- Unexpected `IllegalStateException` is not globally mapped to `409`, while concrete conflict behavior and `ResponseStatusException` status preservation remain covered.
- Existing route, payload, JSON response, and HTMX fragment shapes are unchanged, and tested error bodies contain no sensitive operational detail.
- All focused validation passes and the post-phase validator review-and-repair gate reports PASS.
