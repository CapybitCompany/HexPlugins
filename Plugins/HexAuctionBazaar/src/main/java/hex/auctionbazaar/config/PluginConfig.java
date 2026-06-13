package hex.auctionbazaar.config;

public record PluginConfig(
        boolean enabled,
        boolean debug,
        String prefix,
        boolean economyRequired,
        AuctionConfig auction,
        BazaarConfig bazaar,
        MessagesConfig messages
) {
}
