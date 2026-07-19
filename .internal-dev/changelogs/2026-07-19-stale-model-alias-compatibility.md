# Date
2026-07-19

# Change Summary
Restored `/chat` startup compatibility when persisted runtime settings contain an older model reference that now matches a configured remote model name rather than a current alias.

# Files
- `RuntimeSettingsService.java`
- `RuntimeSettingsServiceTest.java`
- Chat model service specification and model-routing knowledge
- Technical configuration documentation
- Archived bug report for GitHub issue #40

# Behavioral Impact
Existing settings such as `deepseek-v4-pro` remain readable after the configured aliases become `deepseek-v4-pro-high` and `deepseek-v4-pro-max`. New settings writes still require a canonical configured alias.

# Specification Impact
Updated `specifications/services.md` to cover persisted remote-name compatibility after alias renames.

# Risks
When multiple aliases share a remote model name, the browser selector intentionally prefers the configured default alias. Provider routing remains equivalent because the remote name is unchanged.

# Follow-up Items
None.
