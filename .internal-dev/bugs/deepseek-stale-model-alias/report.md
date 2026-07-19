# Summary
The `/chat` page fails with `Unknown configured model: deepseek-v4-pro` after DeepSeek model aliases are split into High and Max variants.

# Scope
Persisted runtime model references consumed by `RuntimeSettingsService` and the browser chat default-model path.

# Reproduction
1. Persist `deepseek-v4-pro` as the runtime default model under the previous configuration.
2. Replace the configured alias with `deepseek-v4-pro-high` and `deepseek-v4-pro-max`, both targeting remote model `deepseek-v4-pro`.
3. Restart Magenta and open `/chat`.

# Expected
The legacy remote-name value resolves through configured-model compatibility, and `/chat` selects the current configured default alias.

# Actual
`RuntimeSettingsService.remoteModelName()` performs only an exact alias lookup and throws before `ChatModelRouter` or the chat selector can map the remote name to a configured alias.

# Evidence
The browser receives JSON containing `Unknown configured model: deepseek-v4-pro`. The stored value matches a configured `remoteModelName` but no longer matches a model-map key.

Mirrored as GitHub issue [#40](https://github.com/dhickel/Magenta/issues/40).

# Impact
Existing installations can lose access to `/chat` immediately after model aliases are renamed even though the underlying provider model remains configured.

# Status
Active; fix in progress.

# Next Action
Accept configured remote model names when resolving persisted runtime settings, add regression coverage, and validate `/chat` startup/rendering.
