package io.mindspice.magenta.core.example;

import io.mindspice.magenta.core.Tool;
import io.mindspice.magenta.core.ToolParam;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Date/time tool for testing and demonstration.
 *
 * <p>Returns the current date and time in ISO-8601 format.
 * Accepts an optional IANA timezone identifier; defaults to
 * UTC when the parameter is null or blank. Not auto-registered —
 * embedding applications must instantiate and register this class
 * explicitly with a {@code ToolRegistry}.</p>
 */
public final class DateTimeTool {

    private static final DateTimeFormatter FORMATTER =
        DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssXXX");

    /**
     * Returns the current date and time.
     *
     * @param timezone optional IANA timezone (e.g. {@code "America/New_York"}),
     *                 defaults to UTC when null or blank
     * @return ISO-8601 formatted current date and time
     */
    @Tool(name = "get_current_time", description = "Returns the current date and time")
    public String getCurrentTime(
        @ToolParam(name = "timezone", description = "Optional IANA timezone", required = false) String timezone
    ) {
        ZoneId zone;
        if (timezone == null || timezone.isBlank()) {
            zone = ZoneId.of("UTC");
        } else {
            try {
                zone = ZoneId.of(timezone);
            } catch (Exception e) {
                zone = ZoneId.of("UTC");
            }
        }
        return ZonedDateTime.now(zone).format(FORMATTER);
    }
}
