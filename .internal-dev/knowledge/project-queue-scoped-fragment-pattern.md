# Topic

Project Queue scoped fragments and cursor controls

# Source References

- `.internal-dev/plans/20260729-163244-home-dhickel-code-java-magenta-internal-dev-ha/phase-02-project-queue-and-console-seams.md`
- `AssignmentQueryService`, `AssignmentControlService`, and `ProjectWorkController`

# Key Takeaways

- Project Queue pagination must preserve a bounded signed snapshot while a signed cursor position selects the next bounded page; replacing a list host with an unrelated status fragment breaks later HTMX updates.
- Every non-2xx Queue response targeted by HTMX must retain its stable host id and include the shared error marker.
- Project transcript rendering must use durable assignment-conversation links only; checkpoint, input, evidence, and legacy fallbacks are not valid project Queue linkage.
- Priority requires both service and SQL predicates for queued, nonterminal, unleased rows with the expected business version.
- A grouped-tab registry must preserve pre-existing functional project tabs mandated by `web.md` (Dashboard and Members); only genuinely deferred tabs should render the controlled unavailable state.

# Engine Relevance

This keeps project and agent Queue projections on the same canonical assignment authority without scope widening or raw runtime disclosure.

# Open Questions

None.
