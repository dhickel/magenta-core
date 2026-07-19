# Magenta

Magenta is a Spring Boot and Spring AI assistant application for chat, planning, operational workflows, jobs, agents, projects, workspaces, and tool-assisted work. It is currently in alpha development and uses SQLite-backed persistence for local development.

## Documentation

- End-user docs: [docs/end-user/00-index.md](docs/end-user/00-index.md)
- Technical docs: [docs/technical/00-index.md](docs/technical/00-index.md)
- API docs: [docs/api/00-index.md](docs/api/00-index.md)
- Documentation contribution rules: [docs/AGENTS.md](docs/AGENTS.md)

Older Maestro design and planning material remains under `docs/maestro/`; it is not the alpha documentation entry point.

## Run

```bash
mvn spring-boot:run
```

This starts:
- the web API in the background
- the terminal REPL in the foreground

Type directly in the terminal to chat. Useful commands:
- `/help`
- `/new`
- `/use <conversation-id>`
- `/model` or `/model <name>` or `/model default`
- `/clear` or `/clear <conversation-id>`
- `/exit`

If you want API-only mode without the terminal loop:

```bash
mvn spring-boot:run -Dspring-boot.run.arguments=--app.repl.enabled=false
```

## Chat API Examples

Start a conversation:

```bash
curl -s http://localhost:8080/api/chat \
  -H 'Content-Type: application/json' \
  -d '{"message":"Hello. Keep answers short."}'
```

Continue the same conversation by reusing `conversationId` from the previous response:

```bash
curl -s http://localhost:8080/api/chat \
  -H 'Content-Type: application/json' \
  -d '{"conversationId":"<conversation-id>","message":"What did I just ask you?"}'
```

Override model per request:

```bash
curl -s http://localhost:8080/api/chat \
  -H 'Content-Type: application/json' \
  -d '{"conversationId":"<conversation-id>","model":"<model-name>","message":"Reply with one sentence."}'
```

Stream a response as server-sent events:

```bash
curl -N http://localhost:8080/api/chat/stream \
  -H 'Accept: text/event-stream' \
  -H 'Content-Type: application/json' \
  -d '{"conversationId":"<conversation-id>","message":"Reply gradually."}'
```

Clear a conversation from memory:

```bash
curl -i -X DELETE http://localhost:8080/api/chat/<conversation-id>
```

## Session And History Endpoints

List persisted conversation ids:

```bash
curl -s http://localhost:8080/api/chat/sessions
```

Load a conversation history:

```bash
curl -s http://localhost:8080/api/chat/<conversation-id>/history
```

Run command-style session actions:

```bash
curl -s http://localhost:8080/api/chat/commands \
  -H 'Content-Type: application/json' \
  -d '{"conversationId":"<active-id>","command":"/new"}'
```

Supported commands include:
- `/new`
- `/switch <uuid>`
- `/clear`
- `/clear <uuid>`
- `/plan`
- `/exit-plan`
- `/exec-plan`

Planning mode records the goal, deliverables, optional inputs and outputs, assumptions, notes, detailed steps, and validation criteria. `/exec-plan` streams execution from the approved plan and requires `plan_complete` validation before the plan is marked completed.

## Browser UI

Open `http://localhost:8080/chat` for the chat UI. Operational alpha surfaces, including dashboards, plans, workflows, jobs, agents, projects, workspaces, outputs, settings, and skills, are documented from the indexes under `docs/` as those sections are completed.
