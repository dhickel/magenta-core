package io.mindspice.magenta2.ai.chat.plan;

final class PlanText {
    private static final String CDATA_START = "<![CDATA[";
    private static final String CDATA_END = "]]>";

    private PlanText() {
    }

    static String normalize(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        if (trimmed.startsWith(CDATA_START) && trimmed.endsWith(CDATA_END)) {
            trimmed = trimmed.substring(CDATA_START.length(), trimmed.length() - CDATA_END.length()).trim();
        }
        return trimmed.isEmpty() ? null : trimmed;
    }
}
