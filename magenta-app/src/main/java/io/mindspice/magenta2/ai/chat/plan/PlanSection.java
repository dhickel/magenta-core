package io.mindspice.magenta2.ai.chat.plan;

public enum PlanSection {
    DELIVERABLE,
    INPUT,
    OUTPUT,
    ASSUMPTION,
    NOTE,
    STEP,
    VALIDATION_CRITERION;

    public static PlanSection fromToolName(String value) {
        if (value == null) {
            throw new IllegalArgumentException("section is required");
        }
        String normalized = value.trim()
            .replace('-', '_')
            .replace(' ', '_')
            .toUpperCase(java.util.Locale.ROOT);
        return switch (normalized) {
            case "DELIVERABLE", "DELIVERABLES" -> DELIVERABLE;
            case "INPUT", "INPUTS" -> INPUT;
            case "OUTPUT", "OUTPUTS" -> OUTPUT;
            case "ASSUMPTION", "ASSUMPTIONS" -> ASSUMPTION;
            case "NOTE", "NOTES" -> NOTE;
            case "STEP", "STEPS" -> STEP;
            case "VALIDATION", "VALIDATION_CRITERIA", "VALIDATION_CRITERION", "ACCEPTANCE_CRITERIA",
                "ACCEPTANCE_CRITERION" -> VALIDATION_CRITERION;
            default -> throw new IllegalArgumentException("Unknown plan section: " + value);
        };
    }
}
