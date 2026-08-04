#!/usr/bin/env bash
set -euo pipefail

repo_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
spring_ai_dir="${SPRING_AI_FORK_DIR:-$(cd "$repo_dir/.." && pwd)/spring-ai-dhickel}"
required_commit="59a06918db6803ae1cb5dec0e5f375df3f8b8df0"
local_config="$repo_dir/config/ai-config.example.json"
config_template="$repo_dir/config/ai-config.deepseek-template.json"

if [[ ! -f "$local_config" ]]; then
    cp "$config_template" "$local_config"
    echo "Created $local_config from the credential-free template." >&2
    echo "Add your DeepSeek API key to all four model entries, then run ./start.sh again." >&2
    exit 1
fi

if [[ ! -x "$spring_ai_dir/mvnw" ]]; then
    echo "Spring AI fork not found at $spring_ai_dir" >&2
    echo "Clone https://github.com/dhickel/spring-ai there or set SPRING_AI_FORK_DIR." >&2
    exit 1
fi

if ! git -C "$spring_ai_dir" merge-base --is-ancestor "$required_commit" HEAD; then
    echo "Spring AI fork must contain commit $required_commit" >&2
    exit 1
fi

(cd "$spring_ai_dir" && ./mvnw -q -pl models/spring-ai-deepseek -am -DskipTests install)
cd "$repo_dir"
exec mvn -pl magenta-app spring-boot:run -Dspring-boot.run.workingDirectory="$repo_dir" "$@"
