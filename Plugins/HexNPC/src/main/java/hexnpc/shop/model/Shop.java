package hexnpc.shop.model;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

public record Shop(
        String id,
        String title,
        int size,
        int sellSlot,
        Map<String, ShopItem> items
) {
    public Shop {
        id = Objects.requireNonNull(id, "id").trim();
        if (id.isEmpty()) {
            throw new IllegalArgumentException("shop id is blank");
        }
        title = title == null ? "" : title;
        if (size <= 0 || size % 9 != 0 || size > 54) {
            throw new IllegalArgumentException("shop size must be a positive multiple of 9 up to 54, got " + size);
        }
        if (sellSlot < 0 || sellSlot >= size) {
            throw new IllegalArgumentException("sell-slot out of bounds: " + sellSlot);
        }
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
        items = Map.copyOf(canonical);
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
}
