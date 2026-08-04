package io.mindspice.magenta2.ai.chat.tool.web;

import java.util.Map;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

@Component
public class AgentWebTools {
    private final AgentWebToolService webToolService;
    private final ObjectMapper objectMapper;

    public AgentWebTools(AgentWebToolService webToolService, ObjectMapper objectMapper) {
        this.webToolService = webToolService;
        this.objectMapper = objectMapper;
    }

    @Tool(
        name = "web_search",
        description = "Search the public web through the configured SearXNG instance. Use this before web_fetch unless the user gives a specific URL. Results include titles, URLs, snippets, and source engines."
    )
    public String search(
        @ToolParam(description = "Search query. Include important names, dates, and terms.")
        String query,
        @ToolParam(required = false, description = "Maximum results to return. Defaults to 5 and is capped by the server.")
        Integer maxResults
    ) {
        try {
            return json(webToolService.search(query, maxResults));
        } catch (Exception exception) {
            return jsonError("web_search", exception);
        }
    }

    @Tool(
        name = "web_fetch",
        description = "Fetch and extract readable text from one public http or https URL. Use this on selected search results or user-provided URLs, then cite the URL in the answer."
    )
    public String fetch(
        @ToolParam(description = "Public http or https URL to fetch.")
        String url,
        @ToolParam(required = false, description = "Maximum extracted characters to return. Defaults to 12000 and is capped by the server.")
        Integer maxCharacters
    ) {
        try {
            return json(webToolService.fetch(url, maxCharacters));
        } catch (Exception exception) {
            return jsonError("web_fetch", exception);
        }
    }

    private String jsonError(String toolName, Exception exception) {
        String message = exception == null || exception.getMessage() == null
            ? "Tool failed"
            : exception.getMessage();
        return json(Map.of(
            "error", toolName + " failed",
            "message", message
        ));
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Failed to serialize web tool result", exception);
        }
    }
}
