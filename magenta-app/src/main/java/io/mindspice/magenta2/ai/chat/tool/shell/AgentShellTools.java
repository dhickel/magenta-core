package io.mindspice.magenta2.ai.chat.tool.shell;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

@Component
public class AgentShellTools {
    private final AgentShellToolService shellToolService;
    private final ObjectMapper objectMapper;

    public AgentShellTools(AgentShellToolService shellToolService, ObjectMapper objectMapper) {
        this.shellToolService = shellToolService;
        this.objectMapper = objectMapper;
    }

    @Tool(
        name = "shell_exec",
        description = "Run an allowed Linux command line inside the active assignment workspace. Use file tools for normal inspect/read/edit flows."
    )
    public String exec(
        @ToolParam(description = "Command line to run, such as 'ls -la notes'. The first token must be an allowed executable.")
        String command,
        @ToolParam(required = false, description = "Workspace-relative working directory. Defaults to the active workspace root.")
        String workingDirectory,
        @ToolParam(required = false, description = "Timeout in seconds. Defaults to 10 and is capped by the server.")
        Integer timeoutSeconds
    ) throws Exception {
        return json(shellToolService.exec(command, workingDirectory, timeoutSeconds));
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Failed to serialize shell tool result", exception);
        }
    }
}
