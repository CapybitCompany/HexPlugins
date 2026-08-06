package hex.auctionbazaar.config;

public record PluginConfig(
        boolean enabled,
        boolean debug,
        String prefix,
        boolean economyRequired,
        DatabaseConfig database,
        // Prompt wpisywania wartości (tabliczka / czat) - patrz SignPrompt.
        long inputFallbackHintTicks,
        long inputTimeoutTicks,
        AuctionConfig auction,
        BazaarConfig bazaar,
        MessagesConfig messages
) {
}
