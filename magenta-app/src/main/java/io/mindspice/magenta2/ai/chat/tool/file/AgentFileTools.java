package io.mindspice.magenta2.ai.chat.tool.file;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

@Component
public class AgentFileTools {
    private final AgentFileToolService fileToolService;
    private final ObjectMapper objectMapper;

    public AgentFileTools(AgentFileToolService fileToolService, ObjectMapper objectMapper) {
        this.fileToolService = fileToolService;
        this.objectMapper = objectMapper;
    }

    @Tool(
        name = "file_list",
        description = "List files and directories in the active workspace. Use this before reading when exploring available files. Paths are workspace-relative; active task contexts may also use outputs/... and projects/<projectId>/... aliases."
    )
    public String list(
        @ToolParam(required = false, description = "Relative file or directory path. Use '.' for the root.")
        String path,
        @ToolParam(required = false, description = "Set true to recursively list child directories. Prefer false for a quick first look.")
        Boolean recursive,
        @ToolParam(required = false, description = "Maximum entries to return. Defaults to 200 and is capped by the server.")
        Integer maxEntries,
        @ToolParam(required = false, description = "Optional Java glob matched against workspace-relative paths, such as '*.md' or '**/*.java'.")
        String glob
    ) throws Exception {
        return json(fileToolService.list(path, Boolean.TRUE.equals(recursive), maxEntries, glob));
    }


    @Tool(
        name = "file_read",
        description = "Read a UTF-8 text file chunk in the active workspace. Large files must be read in chunks with startLine and nextStartLine. Output lines use lineNumber:hash|content anchors for later file_replace edits."
    )
    public String read(
        @ToolParam(description = "Relative file path to read.")
        String path,
        @ToolParam(required = false, description = "1-based first line to read. Defaults to 1. Use nextStartLine from the result to continue.")
        Integer startLine,
        @ToolParam(required = false, description = "Maximum lines to read. Defaults to 200 and is capped by the server.")
        Integer maxLines
    ) throws Exception {
        return json(fileToolService.read(path, startLine, maxLines));
    }

    @Tool(
        name = "file_search",
        description = "Search UTF-8 text files under a relative file or directory path, including large files. Results include matched line numbers, line hashes, and optional context lines."
    )
    public String search(
        @ToolParam(required = false, description = "Relative file or directory path. Use '.' for the root.")
        String path,
        @ToolParam(description = "Text or regex query to find.")
        String query,
        @ToolParam(required = false, description = "Treat query as a Java regular expression.")
        Boolean regex,
        @ToolParam(required = false, description = "Use case-sensitive matching.")
        Boolean caseSensitive,
        @ToolParam(required = false, description = "Number of context lines before and after each match. Defaults to 0 and is capped by the server.")
        Integer contextLines,
        @ToolParam(required = false, description = "Maximum matches to return. Defaults to 50 and is capped by the server.")
        Integer maxMatches
    ) throws Exception {
        return json(fileToolService.search(
            path,
            query,
            Boolean.TRUE.equals(regex),
            Boolean.TRUE.equals(caseSensitive),
            contextLines,
            maxMatches
        ));
    }

    @Tool(
        name = "file_write",
        description = "Create or overwrite a complete UTF-8 text file in the active workspace. Use file_replace for targeted edits to existing files."
    )
    public String write(
        @ToolParam(description = "Relative file path to write.")
        String path,
        @ToolParam(description = "Complete file content to write. Use an empty string only when intentionally creating or clearing a file.")
        String content,
        @ToolParam(required = false, description = "Set true to allow replacing an existing regular file. Defaults to false.")
        Boolean overwrite
    ) throws Exception {
        return json(fileToolService.write(path, content, Boolean.TRUE.equals(overwrite)));
    }

    @Tool(
        name = "file_append",
        description = "Append UTF-8 text to the end of a file in the active workspace. Use this for log, note, report, and outline accumulation instead of rewriting existing file content."
    )
    public String append(
        @ToolParam(description = "Relative file path to append to.")
        String path,
        @ToolParam(description = "Text to append exactly as provided. Include leading or trailing newlines when needed.")
        String content,
        @ToolParam(required = false, description = "Set true to create the file when it does not exist. Defaults to false.")
        Boolean create
    ) throws Exception {
        return json(fileToolService.append(path, content, Boolean.TRUE.equals(create)));
    }

    @Tool(
        name = "file_replace",
        description = "Replace an anchored line range in a UTF-8 text file. Use lineNumber:hash anchors returned by file_read or file_search to avoid stale edits."
    )
    public String replace(
        @ToolParam(description = "Relative file path to edit.")
        String path,
        @ToolParam(description = "Start anchor in lineNumber:hash format.")
        String startAnchor,
        @ToolParam(required = false, description = "End anchor in lineNumber:hash format. Omit for one line.")
        String endAnchor,
        @ToolParam(description = "Replacement text for the anchored range. Use an empty string only when intentionally deleting the range.")
        String replacement
    ) throws Exception {
        return json(fileToolService.replace(path, startAnchor, endAnchor, replacement));
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Failed to serialize file tool result", exception);
        }
    }
}
