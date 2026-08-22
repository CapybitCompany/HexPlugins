package hexnpc.workflow.action;

import org.bukkit.Material;

import java.util.List;

public record AnvilInputAction(
        String id,
        String title,
        Material material,
        String itemName,
        String initialText,
        Validation validation,
        Messages messages,
        List<WorkflowAction> onCancel
) implements WorkflowAction {
    public record Validation(
            boolean required,
            int minLength,
            int maxLength,
            boolean trim,
            String allowedPattern,
            List<String> denyPatterns,
            boolean allowColors,
            boolean allowMiniMessage
    ) {
        public Validation {
            if (minLength < 0) minLength = 0;
            if (maxLength <= 0) maxLength = 64;
            if (maxLength < minLength) throw new IllegalArgumentException("anvil_input max-length < min-length");
            allowedPattern = allowedPattern == null ? "" : allowedPattern;
            denyPatterns = denyPatterns == null ? List.of() : List.copyOf(denyPatterns);
        }
    }

    public record Messages(String required, String tooShort, String tooLong, String invalid) {
        public Messages {
            required = required == null ? "&cWpisz wartość." : required;
            tooShort = tooShort == null ? "&cWpisana wartość jest za krótka." : tooShort;
            tooLong = tooLong == null ? "&cWpisana wartość jest za długa." : tooLong;
            invalid = invalid == null ? "&cTa wartość zawiera niedozwolone znaki." : invalid;
        }
    }

    public AnvilInputAction {
        id = id == null ? "" : id.trim();
        title = title == null ? "&0Wpisz wartość" : title;
        material = material == null ? Material.NAME_TAG : material;
        itemName = itemName == null ? "&eWpisz tekst" : itemName;
        initialText = initialText == null ? "" : initialText;
        validation = validation == null
                ? new Validation(true, 1, 24, true, "", List.of(), false, false)
                : validation;
        messages = messages == null ? new Messages(null, null, null, null) : messages;
        onCancel = onCancel == null ? List.of() : List.copyOf(onCancel);
        if (id.isEmpty()) throw new IllegalArgumentException("anvil_input requires id");
    }

    @Override public String type() { return "anvil_input"; }
}
