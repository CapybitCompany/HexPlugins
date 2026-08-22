package hex.vishopbroadcast.proxy.config;

import java.util.List;

public record ProxyService(
        String key,
        boolean enabled,
        List<String> aliases,
        String displayName,
        boolean amountRequired,
        boolean priceRequired,
        boolean priceFromAmountWhenPriceMissing,
        String amountPart,
        String pricePart,
        String logInfo
) {
    public boolean matches(String input) {
        if (key.equalsIgnoreCase(input)) {
            return true;
        }
        return aliases.stream().anyMatch(alias -> alias.equalsIgnoreCase(input));
    }
}
