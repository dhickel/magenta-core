# Topic
Reliable validation of Java JAR contents and manifest metadata

# Source References
- `jar tf target/magenta-core-0.2.0.jar`
- `META-INF/MANIFEST.MF` continuation-line rules
- `rg` default regular-expression engine behavior
- 2026-09-01 parallel tool-call guard protocol review

# Key Takeaways
- `jar tf` includes directory entries ending in `/`. Forbidden-package scans must ignore those entries before treating a parent package directory as a forbidden class/resource.
- Ripgrep's default engine does not support lookaround. A parse error inside a shell `if` condition can be mistaken for a clean no-match result because conditional command failures are exempt from ordinary `set -e` termination. Use simple supported expressions, opt into `--pcre2`, and distinguish exit status 1 (no match) from status 2 (error).
- JAR manifest attributes may be folded across continuation lines beginning with one space. Do not parse long hashes with one-line `awk`; unfold continuation lines or use `java.util.jar.Manifest`/an equivalent parser.
- A useful unshaded-library scan checks the expected public package, required resources, automatic module name, banned dependency coordinates, and forbidden packaged files independently.

# Engine Relevance
Apply these rules to Magenta Core release and pinned-consumer validation so a malformed scan cannot produce a false pass and long artifact hashes are compared exactly.

# Open Questions
None.
