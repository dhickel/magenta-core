## Chat Rendering Package

This package owns rendering assistant/user-visible chat text for the web surface.

### Responsibilities
- Convert markdown to safe HTML for display.
- Keep sanitization behavior explicit and conservative.
- Keep rendering separate from chat generation and persistence.

### Change guidance
- Do not weaken HTML sanitization for convenience.
- Add rendering features only when the current UI or API needs them.
- Keep renderer behavior deterministic and easy to test.
- Keep this guide updated when supported markup, sanitization rules, or rendering ownership changes.

### Validation
- Add focused rendering tests for markdown, HTML, and sanitization changes.
- Include regression cases for unsafe or malformed input when relevant.
