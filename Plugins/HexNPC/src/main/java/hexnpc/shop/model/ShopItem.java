package hexnpc.shop.model;

import org.bukkit.Material;

import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;

/**
 * Definicja pozycji sklepu. Pola dopisane po maxBuyAmount są opcjonalnym
 * rozszerzeniem premium; stare konstruktory zachowują dokładne legacy defaulty.
 */
public record ShopItem(
        String id,
        Material material,
        int amount,
        int slot,
        int page,
        String displayName,
        List<String> lore,
        BigDecimal buyPrice,
        BigDecimal sellPrice,
        boolean buyEnabled,
        boolean sellEnabled,
        SellMatch sellMatch,
        int maxBuyAmount,
        int customModelData,
        String iconCustomItemId,
        ShopItemAction action,
        ShopReward reward,
        OneTimePolicy oneTime,
        boolean singlePurchaseView,
        ShopItemAction ownedAction,
        ShopItemAction postPurchaseAction
) {
    /** Wartość slotu oznaczająca „brak jawnego slotu" (placement AUTO). */
    public static final int NO_SLOT = -1;

    public ShopItem {
        id = Objects.requireNonNull(id, "id").trim();
        if (id.isEmpty()) {
            throw new IllegalArgumentException("shop item id is blank");
        }
        material = Objects.requireNonNull(material, "material");
        if (material.isAir()) {
            throw new IllegalArgumentException("material " + material + " is air and cannot back a shop item");
        }
        if (!material.isItem()) {
            throw new IllegalArgumentException(
                    "material " + material + " is not an obtainable item (block-only / technical)");
        }
        int maxStack = material.getMaxStackSize();
        if (amount < 1 || amount > maxStack) {
            throw new IllegalArgumentException(
                    "invalid amount " + amount + " for material " + material + " (must be 1.." + maxStack + ")");
        }
        if (slot < NO_SLOT) {
            throw new IllegalArgumentException("invalid slot: " + slot);
        }
        if (page < 0) page = 0;
        lore = lore == null ? List.of() : List.copyOf(lore);
        buyPrice = buyPrice == null ? BigDecimal.ZERO : buyPrice;
        sellPrice = sellPrice == null ? BigDecimal.ZERO : sellPrice;
        sellMatch = sellMatch == null ? SellMatch.PLAIN_MATERIAL : sellMatch;
        if (maxBuyAmount < 0) maxBuyAmount = 0;
        if (customModelData < 0) customModelData = 0;
        iconCustomItemId = iconCustomItemId == null ? "" : iconCustomItemId.trim();
        action = action == null ? ShopItemAction.details() : action;
        reward = reward == null ? ShopReward.item() : reward;
        oneTime = oneTime == null ? OneTimePolicy.disabled() : oneTime;
        ownedAction = ownedAction == null ? ShopItemAction.none() : ownedAction;
        postPurchaseAction = postPurchaseAction == null ? ShopItemAction.none() : postPurchaseAction;

        // Usług/command rewards nie da się bezpiecznie „sprzedać z powrotem".
        if (reward.type() != ShopRewardType.ITEM && sellEnabled) {
            throw new IllegalArgumentException("sell-enabled=true is not supported for reward type " + reward.type());
        }
        if (action.type() != ShopItemActionType.DETAILS && (buyEnabled || sellEnabled)) {
            // Kafel akcji/nadchodzącej funkcji nie jest transakcją sklepową.
            buyEnabled = false;
            sellEnabled = false;
        }
    }

    /** Konstruktor zgodny z pełnym modelem HexNPC 1.3/1.4. */
    public ShopItem(String id, Material material, int amount, int slot, int page,
                    String displayName, List<String> lore,
                    BigDecimal buyPrice, BigDecimal sellPrice,
                    boolean buyEnabled, boolean sellEnabled, SellMatch sellMatch, int maxBuyAmount,
                    int customModelData, String iconCustomItemId, ShopItemAction action,
                    ShopReward reward, OneTimePolicy oneTime, boolean singlePurchaseView) {
        this(id, material, amount, slot, page, displayName, lore, buyPrice, sellPrice,
                buyEnabled, sellEnabled, sellMatch, maxBuyAmount, customModelData, iconCustomItemId,
                action, reward, oneTime, singlePurchaseView, ShopItemAction.none(), ShopItemAction.none());
    }

    /** Konstruktor kompatybilny z HexNPC 1.1/1.2. */
    public ShopItem(String id, Material material, int amount, int slot, int page,
                    String displayName, List<String> lore,
                    BigDecimal buyPrice, BigDecimal sellPrice,
                    boolean buyEnabled, boolean sellEnabled, SellMatch sellMatch, int maxBuyAmount) {
        this(id, material, amount, slot, page, displayName, lore,
                buyPrice, sellPrice, buyEnabled, sellEnabled, sellMatch, maxBuyAmount,
                0, "", ShopItemAction.details(), ShopReward.item(), OneTimePolicy.disabled(), false,
                ShopItemAction.none(), ShopItemAction.none());
    }

    /** Konstruktor kompatybilny wstecz (bez page i max-buy-amount). */
    public ShopItem(String id, Material material, int amount, int slot,
                    String displayName, List<String> lore,
                    BigDecimal buyPrice, BigDecimal sellPrice,
                    boolean buyEnabled, boolean sellEnabled, SellMatch sellMatch) {
        this(id, material, amount, slot, 0, displayName, lore,
                buyPrice, sellPrice, buyEnabled, sellEnabled, sellMatch, 0);
    }

    public boolean hasBuyPrice() {
        return buyEnabled && buyPrice != null && buyPrice.signum() > 0;
    }

    public boolean hasSellPrice() {
        return sellEnabled && sellPrice != null && sellPrice.signum() > 0;
    }

    public boolean hasBuyLimit() {
        return maxBuyAmount > 0;
    }

    public boolean hasExplicitSlot() {
        return slot != NO_SLOT;
    }

    public boolean hasCustomIconItem() {
        return iconCustomItemId != null && !iconCustomItemId.isBlank();
    }

    public boolean isOneTime() {
        return oneTime.enabled();
    }

    public boolean isDirectAction() {
        return action.type() != ShopItemActionType.DETAILS;
    }

    public boolean hasOwnedAction() {
        return ownedAction.type() != ShopItemActionType.NONE && ownedAction.type() != ShopItemActionType.DETAILS;
    }

    public boolean hasPostPurchaseAction() {
        return postPurchaseAction.type() != ShopItemActionType.NONE && postPurchaseAction.type() != ShopItemActionType.DETAILS;
    }
}
