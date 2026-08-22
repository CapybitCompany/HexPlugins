package hexnpc.workflow.action;

public record ConditionDefinition(
        String type,
        String key,
        String value,
        String expected,
        String placeholder,
        String operator
) {
    public ConditionDefinition {
        type = type == null ? "" : type.trim().toLowerCase(java.util.Locale.ROOT).replace('-', '_');
        key = key == null ? "" : key.trim();
        value = value == null ? "" : value;
        expected = expected == null ? "" : expected;
        placeholder = placeholder == null ? "" : placeholder;
        operator = operator == null ? "==" : operator.trim();
        if (type.isEmpty()) throw new IllegalArgumentException("condition requires type");
    }
}
