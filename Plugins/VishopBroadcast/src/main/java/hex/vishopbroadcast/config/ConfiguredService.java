package hex.vishopbroadcast.config;

import java.util.List;

public record ConfiguredService(
        String key,
        boolean enabled,
        List<String> aliases,
        String displayName,
        boolean amountRequired,
        boolean priceRequired,
        boolean priceFromAmountWhenPriceMissing,
        String amountPartTemplate,
        String pricePartTemplate,
        String logInfoTemplate,
        ServiceDisplay display
) {
    public boolean matches(String input) {
        if (input == null) {
            return false;
        }
        if (key.equalsIgnoreCase(input)) {
            return true;
        }
        return aliases.stream().anyMatch(alias -> alias.equalsIgnoreCase(input));
    }
}

