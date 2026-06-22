package hexnpc.shop.model;

import org.bukkit.Material;

import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;

public record ShopItem(
        String id,
        Material material,
        int amount,
        int slot,
        String displayName,
        List<String> lore,
        BigDecimal buyPrice,
        BigDecimal sellPrice,
        boolean buyEnabled,
        boolean sellEnabled,
        SellMatch sellMatch
) {
    public ShopItem {
        id = Objects.requireNonNull(id, "id").trim();
        if (id.isEmpty()) {
            throw new IllegalArgumentException("shop item id is blank");
        }
        material = Objects.requireNonNull(material, "material");
        // Zabezpieczenie: w shopie mogą się znaleźć tylko materiały
        // reprezentujące realny przedmiot. Bloki techniczne (np. WATER,
        // LAVA, FIRE, NETHER_PORTAL) oraz AIR nie mają sensu jako shop
        // item — odrzucamy je wcześnie, żeby loader pominął wpis z
        // czytelnym komunikatem zamiast wybuchać dalej w GUI/Inventory.
        if (material.isAir()) {
            throw new IllegalArgumentException(
                    "material " + material + " is air and cannot back a shop item");
        }
        if (!material.isItem()) {
            throw new IllegalArgumentException(
                    "material " + material + " is not an obtainable item (block-only / technical)");
        }
        // Amount jest limitowany do pojedynczego stosu danego materiału.
        // Decyzja v1: brak transakcji wieloskałkowych. Wymuszamy to tutaj,
        // żeby buy-flow zawsze operował na pojedynczym ItemStacku.
        int maxStack = material.getMaxStackSize();
        if (amount < 1 || amount > maxStack) {
            throw new IllegalArgumentException(
                    "invalid amount " + amount + " for material " + material
                            + " (must be 1.." + maxStack + ")");
        }
        if (slot < 0) {
            throw new IllegalArgumentException("invalid slot: " + slot);
        }
        lore = lore == null ? List.of() : List.copyOf(lore);
        buyPrice = buyPrice == null ? BigDecimal.ZERO : buyPrice;
        sellPrice = sellPrice == null ? BigDecimal.ZERO : sellPrice;
        sellMatch = sellMatch == null ? SellMatch.PLAIN_MATERIAL : sellMatch;
    }

    public boolean hasBuyPrice() {
        return buyEnabled && buyPrice != null && buyPrice.signum() > 0;
    }

    public boolean hasSellPrice() {
        return sellEnabled && sellPrice != null && sellPrice.signum() > 0;
    }
}
