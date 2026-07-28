# Summary

`ChatControllerTest.outputsChangedSideChannelIsNotPlanExecutionFinalMessage` fails consistently in the full Maven suite but passes when run alone.

# Scope

Out-of-scope test-isolation defect discovered while validating the project UI modernization.

# Reproduction

1. Run `mvn -q test`.
2. Observe the named `ChatControllerTest` failure.
3. Run `mvn -q -Dtest=ChatControllerTest#outputsChangedSideChannelIsNotPlanExecutionFinalMessage test`.

# Expected

The test has the same result in isolation and in the full suite.

# Actual

The full suite reports `Expecting actual not to be null` at `ChatControllerTest.java:252`; the isolated test passes.

# Evidence

Two consecutive full-suite runs executed 913 tests and failed only this test. The isolated test passed between those runs. The project UI focused tests and package build pass.

# Impact

The repository-wide test suite cannot currently provide a clean deterministic validation signal even though the changed project UI path passes focused tests.

# Status

Open; mirrored as GitHub Issue [#41](https://github.com/dhickel/Magenta/issues/41).

# Next Action

Identify shared static state, test order, asynchronous event timing, or mock reset behavior affecting the plan-execution `outputs_changed` side-channel assertion.
