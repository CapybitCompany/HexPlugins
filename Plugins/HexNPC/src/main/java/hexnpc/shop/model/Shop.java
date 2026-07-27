package hexnpc.shop.model;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

public record Shop(
        String id,
        String title,
        ShopLayout layout,
        Map<String, ShopItem> items
) {
    public Shop {
        id = Objects.requireNonNull(id, "id").trim();
        if (id.isEmpty()) {
            throw new IllegalArgumentException("shop id is blank");
        }
        title = title == null ? "" : title;
        layout = layout == null ? ShopLayout.defaults(54) : layout;
        // Klucze normalizujemy do lower-case, żeby wielkość liter w YAML
        // nigdy nie wpływała na wynik lookupu. Kolejność jest zachowana.
        Map<String, ShopItem> canonical = new LinkedHashMap<>();
        if (items != null) {
            for (Map.Entry<String, ShopItem> entry : items.entrySet()) {
                String key = entry.getKey() == null ? null
                        : entry.getKey().toLowerCase(Locale.ROOT);
                if (key == null) {
                    continue;
                }
                canonical.put(key, entry.getValue());
            }
        }
        // Zachowujemy kolejność wstawiania (z shops.yml) — jest ona źródłem
        // prawdy dla paginacji i placement AUTO. Map.copyOf NIE gwarantuje
        // kolejności, więc używamy niemodyfikowalnego LinkedHashMap.
        items = Collections.unmodifiableMap(new LinkedHashMap<>(canonical));
    }

    /**
     * Konstruktor kompatybilny wstecz: buduje sklep z prostego rozmiaru
     * i slotu sprzedaży, tworząc domyślny układ o tym rozmiarze i
     * ustawiając w nim ten slot sprzedaży w widoku szczegółów.
     */
    public Shop(String id, String title, int size, int sellSlot, Map<String, ShopItem> items) {
        this(id, title, legacyLayout(size, sellSlot), items);
    }

    private static ShopLayout legacyLayout(int size, int sellSlot) {
        ShopLayout base = ShopLayout.defaults(size);
        int safeSell = (sellSlot >= 0 && sellSlot < base.size()) ? sellSlot : base.detailSellSlot();
        return new ShopLayout(
                base.size(), base.placement(), base.itemSlots(),
                base.previousSlot(), base.pageSlot(), base.nextSlot(),
                base.fillerMaterial(), base.fillerName(),
                base.detailPreviewSlot(), base.detailSelectedInfoSlot(),
                base.quantityPresetSlots(), base.detailCustomQuantitySlot(),
                base.detailBuySlot(), safeSell, base.detailSellAllSlot(), base.detailBackSlot()
        ).validated(null, "legacy-" + id(size, sellSlot));
    }

    private static String id(int size, int sellSlot) {
        return size + "/" + sellSlot;
    }

    public int size() {
        return layout.size();
    }

    /** Slot przycisku „Sprzedaj" w widoku szczegółów (kompatybilność wstecz). */
    public int sellSlot() {
        return layout.detailSellSlot();
    }

    public Optional<ShopItem> item(String itemId) {
        if (itemId == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(items.get(itemId.toLowerCase(Locale.ROOT)));
    }

    public Collection<ShopItem> itemValues() {
        return items.values();
    }

    /**
     * Zwraca itemy w kolejności z shops.yml jako listę — używane przez
     * paginację i placement AUTO.
     */
    public List<ShopItem> orderedItems() {
        return new ArrayList<>(items.values());
    }
}
