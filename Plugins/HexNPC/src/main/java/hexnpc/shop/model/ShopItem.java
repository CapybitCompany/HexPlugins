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
        int page,
        String displayName,
        List<String> lore,
        BigDecimal buyPrice,
        BigDecimal sellPrice,
        boolean buyEnabled,
        boolean sellEnabled,
        SellMatch sellMatch,
        int maxBuyAmount
) {
    /** Wartość slotu oznaczająca „brak jawnego slotu" (placement AUTO). */
    public static final int NO_SLOT = -1;

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
        // Amount jest bazową jednostką ceny i ograniczony do pojedynczego
        // stosu danego materiału. Wielkość transakcji (wybrana ilość) jest
        // niezależna i może przekraczać maxStackSize — patrz ShopService.
        int maxStack = material.getMaxStackSize();
        if (amount < 1 || amount > maxStack) {
            throw new IllegalArgumentException(
                    "invalid amount " + amount + " for material " + material
                            + " (must be 1.." + maxStack + ")");
        }
        // slot == NO_SLOT (-1) oznacza brak jawnego slotu (placement AUTO).
        if (slot < NO_SLOT) {
            throw new IllegalArgumentException("invalid slot: " + slot);
        }
        if (page < 0) {
            page = 0;
        }
        lore = lore == null ? List.of() : List.copyOf(lore);
        buyPrice = buyPrice == null ? BigDecimal.ZERO : buyPrice;
        sellPrice = sellPrice == null ? BigDecimal.ZERO : sellPrice;
        sellMatch = sellMatch == null ? SellMatch.PLAIN_MATERIAL : sellMatch;
        // max-buy-amount < 0 nie ma sensu; 0 = brak limitu.
        if (maxBuyAmount < 0) {
            maxBuyAmount = 0;
        }
    }

    /**
     * Konstruktor kompatybilny wstecz (bez page i max-buy-amount).
     * Zachowuje sygnaturę używaną przez starszy kod i testy.
     */
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

    /** Czy item ma dzienny limit kupna (max-buy-amount > 0). */
    public boolean hasBuyLimit() {
        return maxBuyAmount > 0;
    }

    /** Czy item ma jawnie skonfigurowany slot (placement MANUAL). */
    public boolean hasExplicitSlot() {
        return slot != NO_SLOT;
    }
}
