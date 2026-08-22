package hexnpc.shop.model;

import java.util.List;

/** Konfigurowalna nagroda zakupu. Brak sekcji reward = zwykły ITEM. */
public record ShopReward(
        ShopRewardType type,
        List<String> commands,
        String itemId,
        int amount,
        String verifyPermission
) {
    public ShopReward {
        type = type == null ? ShopRewardType.ITEM : type;
        commands = commands == null ? List.of() : List.copyOf(commands);
        itemId = itemId == null ? "" : itemId.trim();
        amount = Math.max(1, amount);
        verifyPermission = verifyPermission == null ? "" : verifyPermission.trim();

        if (type == ShopRewardType.CONSOLE_COMMANDS && commands.isEmpty()) {
            throw new IllegalArgumentException("CONSOLE_COMMANDS reward requires at least one command");
        }
        if (type == ShopRewardType.HEX_CUSTOM_ITEM && itemId.isEmpty()) {
            throw new IllegalArgumentException("HEX_CUSTOM_ITEM reward requires item-id");
        }
    }

    public static ShopReward item() {
        return new ShopReward(ShopRewardType.ITEM, List.of(), "", 1, "");
    }
}
