# Date
2026-07-19

# Change Summary
Added High and Max thinking variants for DeepSeek V4 Pro and V4 Flash. Magenta now maps its existing `thinkLevel` configuration into the maintained Spring AI DeepSeek extension and includes a startup script that installs the forked module before launching the application.

# Files
- `ChatModelRouter.java` and `ChatModelRouterTest.java`
- `pom.xml`, `start.sh`, and `config/ai-config.deepseek-template.json`
- Local ignored `config/ai-config.example.json`
- DeepSeek configuration and chat documentation
- Chat model service specification and Spring AI knowledge references

# Behavioral Impact
Configured DeepSeek models with `thinkLevel` 1-3 send enabled thinking with `reasoning_effort=high`; level 4 sends enabled thinking with `reasoning_effort=max`. The local startup config exposes four distinct model aliases.

# Specification Impact
Updated `specifications/services.md` to define the DeepSeek `thinkLevel` mapping at the model-router boundary.

# Risks
The DeepSeek extension is a `2.0.1-SNAPSHOT` module installed from the maintained fork. Startup requires the fork checkout and Maven installation step until the change is released upstream or published as a stable artifact.

# Follow-up Items
- Replace the forked snapshot dependency with an official Spring AI release when equivalent request controls are available upstream.
