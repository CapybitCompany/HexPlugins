package hexnpc.shop.config;

import hexnpc.shop.model.PlacementMode;
import hexnpc.shop.model.ShopLayout;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;

import java.util.List;
import java.util.logging.Logger;

/**
 * Wczytuje {@link ShopLayout} z sekcji konfiguracji. Ta sama logika obsługuje
 * globalny domyślny układ (config.yml -> shops.default-layout) oraz nadpisania
 * per-sklep (shops.yml -> &lt;sklep&gt;.layout). Każde pole ma fallback z
 * przekazanego układu bazowego, a wynik jest zawsze sanityzowany.
 */
public final class ShopLayoutLoader {

    private ShopLayoutLoader() {
    }

    /**
     * @param section   sekcja układu (może być null → używamy samych wartości bazowych)
     * @param base      układ bazowy o docelowym rozmiarze (dostarcza domyślne sloty)
     * @param size      docelowy rozmiar GUI
     * @param placement tryb rozmieszczenia dla wynikowego układu
     */
    public static ShopLayout load(ConfigurationSection section, ShopLayout base,
                                  int size, PlacementMode placement, Logger log, String ctx) {
        ShopLayout safeBase = base == null ? ShopLayout.defaults(size) : base;

        List<Integer> itemSlots = intList(section, "item-slots", safeBase.itemSlots());
        int prev = getInt(section, "navigation.previous-slot", safeBase.previousSlot());
        int page = getInt(section, "navigation.page-slot", safeBase.pageSlot());
        int next = getInt(section, "navigation.next-slot", safeBase.nextSlot());

        Material fillerMat = material(getString(section, "filler.material", null), safeBase.fillerMaterial());
        String fillerName = getString(section, "filler.name", safeBase.fillerName());

        int preview = getInt(section, "detail.preview-slot", safeBase.detailPreviewSlot());
        int selectedInfo = getInt(section, "detail.selected-info-slot", safeBase.detailSelectedInfoSlot());
        List<Integer> presetSlots = intList(section, "detail.quantity-slots", safeBase.quantityPresetSlots());
        int customQty = getInt(section, "detail.custom-quantity-slot", safeBase.detailCustomQuantitySlot());
        int buy = getInt(section, "detail.buy-slot", safeBase.detailBuySlot());
        int sell = getInt(section, "detail.sell-slot", safeBase.detailSellSlot());
        int sellAll = getInt(section, "detail.sell-all-slot", safeBase.detailSellAllSlot());
        int back = getInt(section, "detail.back-slot", safeBase.detailBackSlot());

        ShopLayout raw = new ShopLayout(size, placement, itemSlots, prev, page, next,
                fillerMat, fillerName, preview, selectedInfo, presetSlots,
                customQty, buy, sell, sellAll, back);
        return raw.validated(log, ctx);
    }

    private static List<Integer> intList(ConfigurationSection section, String path, List<Integer> fallback) {
        if (section == null || !section.contains(path)) {
            return fallback;
        }
        List<Integer> list = section.getIntegerList(path);
        return list == null || list.isEmpty() ? fallback : list;
    }

    private static int getInt(ConfigurationSection section, String path, int fallback) {
        if (section == null || !section.contains(path)) {
            return fallback;
        }
        return section.getInt(path, fallback);
    }

    private static String getString(ConfigurationSection section, String path, String fallback) {
        if (section == null || !section.contains(path)) {
            return fallback;
        }
        return section.getString(path, fallback);
    }

    private static Material material(String raw, Material fallback) {
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        Material parsed = Material.matchMaterial(raw.trim());
        return parsed == null ? fallback : parsed;
    }
}
