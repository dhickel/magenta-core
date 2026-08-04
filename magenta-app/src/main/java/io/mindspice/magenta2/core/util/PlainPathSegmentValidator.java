package io.mindspice.magenta2.core.util;

import java.nio.file.Path;
import java.util.regex.Pattern;

import org.springframework.util.StringUtils;

/**
 * Validates caller-controlled ids before they are composed into filesystem paths.
 */
public final class PlainPathSegmentValidator {
    private static final Pattern WINDOWS_DRIVE_PREFIX = Pattern.compile("^[A-Za-z]:.*");

    private PlainPathSegmentValidator() {
    }

    public static String requirePlainSegment(String value, String label) {
        if (!StringUtils.hasText(value)) {
            throw new IllegalArgumentException(label + " is required");
        }
        if (!value.equals(value.trim())) {
            throw new IllegalArgumentException(label + " must be a plain path segment without surrounding whitespace");
        }
        if (isDotOnly(value)) {
            throw new IllegalArgumentException(label + " must not be a dot-only path segment");
        }
        if (WINDOWS_DRIVE_PREFIX.matcher(value).matches()) {
            throw new IllegalArgumentException(label + " must not use absolute path syntax");
        }
        if (Path.of(value).isAbsolute()) {
            throw new IllegalArgumentException(label + " must not use absolute path syntax");
        }
        if (value.indexOf('/') >= 0 || value.indexOf('\\') >= 0) {
            throw new IllegalArgumentException(label + " must not contain path separators");
        }
        if (value.indexOf('%') >= 0) {
            throw new IllegalArgumentException(label + " must not contain percent-encoded path syntax");
        }
        return value;
    }

    private static boolean isDotOnly(String value) {
        for (int i = 0; i < value.length(); i++) {
            if (value.charAt(i) != '.') {
                return false;
            }
        }
        return true;
    }
}
