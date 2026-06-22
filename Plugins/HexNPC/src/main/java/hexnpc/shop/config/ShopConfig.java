package hexnpc.shop.config;

import java.util.Objects;

public record ShopConfig(
        boolean enabled,
        boolean requireEconomy,
        String titleFormat,
        int defaultSize,
        int defaultSellSlot,
        boolean preventSellingCustomItems,
        ShopMessages messages
) {
    public ShopConfig {
        titleFormat = Objects.toString(titleFormat, "&8Shop: &6<shop>");
        if (defaultSize <= 0 || defaultSize % 9 != 0 || defaultSize > 54) {
            defaultSize = 54;
        }
        if (defaultSellSlot < 0 || defaultSellSlot >= defaultSize) {
            defaultSellSlot = Math.max(0, defaultSize - 5);
        }
        messages = messages == null ? ShopMessages.defaults() : messages;
    }

    public static ShopConfig defaults() {
        return new ShopConfig(true, true, "&8Sklep: &6<shop>", 54, 49, true, ShopMessages.defaults());
    }
}
