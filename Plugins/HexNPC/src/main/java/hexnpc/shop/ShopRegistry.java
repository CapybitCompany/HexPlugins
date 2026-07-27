package hexnpc.shop;

import hexnpc.shop.config.ShopConfig;
import hexnpc.shop.config.ShopLayoutLoader;
import hexnpc.shop.model.PlacementMode;
import hexnpc.shop.model.SellMatch;
import hexnpc.shop.model.Shop;
import hexnpc.shop.model.ShopItem;
import hexnpc.shop.model.ShopLayout;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.logging.Logger;

/**
 * Wczytuje i udostępnia definicje shopów z shops.yml. Rejestr jest w
 * pełni wymienny — {@link #reload(File, ShopConfig)} odbudowuje
 * wewnętrzną mapę z dysku. Nieprawidłowe itemy są pomijane z wpisem w
 * logu; uszkodzony top-level daje pusty rejestr.
 */
public final class ShopRegistry {

    private final Logger logger;
    private volatile Map<String, Shop> shops = Map.of();

    public ShopRegistry(Logger logger) {
        this.logger = Objects.requireNonNull(logger, "logger");
    }

    public Optional<Shop> find(String id) {
        if (id == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(shops.get(id.toLowerCase(Locale.ROOT)));
    }

    public Collection<Shop> all() {
        return Collections.unmodifiableCollection(shops.values());
    }

    public int size() {
        return shops.size();
    }

    /**
     * Dodaje/nadpisuje pojedynczy sklep w rejestrze programowo (bez pliku).
     * Przydatne do sklepów budowanych w kodzie oraz w testach.
     */
    public void register(Shop shop) {
        Objects.requireNonNull(shop, "shop");
        Map<String, Shop> next = new LinkedHashMap<>(shops);
        next.put(shop.id().toLowerCase(Locale.ROOT), shop);
        this.shops = Map.copyOf(next);
    }

    /** Podmienia rejestr na podstawie pliku. Zwraca liczbę wczytanych shopów. */
    public int reload(File file, ShopConfig config) throws IOException {
        Objects.requireNonNull(file, "file");
        if (!file.exists()) {
            this.shops = Map.of();
            return 0;
        }
        try (Reader reader = Files.newBufferedReader(file.toPath(), StandardCharsets.UTF_8)) {
            return reloadFrom(reader, config);
        }
    }

    /** Wariant pod testy: ładuje z dowolnego źródła znakowego. */
    public int reloadFrom(Reader reader, ShopConfig config) throws IOException {
        Objects.requireNonNull(reader, "reader");
        ShopConfig effective = config == null ? ShopConfig.defaults() : config;
        YamlConfiguration yaml = new YamlConfiguration();
        try {
            yaml.loadFromString(readAll(reader));
        } catch (Exception ex) {
            throw new IOException("Failed to parse shops.yml: " + ex.getMessage(), ex);
        }
        return applyYaml(yaml, effective);
    }

    /** Wariant pod testy: wczytuje domyślny zasób z classpath pluginu. */
    public int reloadFromClasspath(String resourcePath, ShopConfig config) throws IOException {
        try (var stream = ShopRegistry.class.getClassLoader().getResourceAsStream(resourcePath)) {
            if (stream == null) {
                this.shops = Map.of();
                return 0;
            }
            try (Reader reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
                return reloadFrom(reader, config);
            }
        }
    }

    private int applyYaml(YamlConfiguration yaml, ShopConfig config) {
        ConfigurationSection root = yaml.getConfigurationSection("shops");
        if (root == null) {
            this.shops = Map.of();
            return 0;
        }
        Map<String, Shop> next = new LinkedHashMap<>();
        for (String key : root.getKeys(false)) {
            ConfigurationSection shopSection = root.getConfigurationSection(key);
            if (shopSection == null) {
                logger.warning("HexNPC: shop '" + key + "' is malformed (not a section), skipped.");
                continue;
            }
            try {
                Shop shop = readShop(key, shopSection, config);
                next.put(shop.id().toLowerCase(Locale.ROOT), shop);
            } catch (Exception ex) {
                logger.warning("HexNPC: shop '" + key + "' skipped: " + ex.getMessage());
            }
        }
        this.shops = Map.copyOf(next);
        return shops.size();
    }

    private Shop readShop(String key, ConfigurationSection section, ShopConfig config) {
        String title = section.getString("title", "&8" + key);
        int size = section.getInt("size", config.defaultSize());
        if (size <= 0 || size % 9 != 0 || size > 54) {
            throw new IllegalArgumentException("invalid size: " + size);
        }
        ConfigurationSection itemsSection = section.getConfigurationSection("items");

        PlacementMode placement = resolvePlacement(key, section, itemsSection, config);

        ShopLayout base = size == config.defaultSize()
                ? config.defaultLayout() : ShopLayout.defaults(size);
        ShopLayout layout = ShopLayoutLoader.load(section.getConfigurationSection("layout"),
                base, size, placement, logger, "shop " + key);
        // Kompatybilność wstecz: stary klucz sell-slot na poziomie sklepu.
        if (section.contains("sell-slot")) {
            int legacySell = section.getInt("sell-slot", layout.detailSellSlot());
            layout = layout.withDetailSellSlot(legacySell).validated(logger, "shop " + key + " sell-slot");
        }

        // Sloty zarezerwowane dla nawigacji (na każdej stronie) — w MANUAL
        // item nigdy nie może na nie trafić, bo zostałby niewidocznie
        // nadpisany przyciskiem nawigacji.
        Set<Integer> reservedNav = Set.of(layout.previousSlot(), layout.pageSlot(), layout.nextSlot());
        Set<String> usedPageSlots = new HashSet<>();

        Map<String, ShopItem> items = new LinkedHashMap<>();
        if (itemsSection != null) {
            for (String itemKey : itemsSection.getKeys(false)) {
                ConfigurationSection itemSection = itemsSection.getConfigurationSection(itemKey);
                if (itemSection == null) {
                    logger.warning("HexNPC: shop '" + key + "' item '" + itemKey + "' malformed, skipped.");
                    continue;
                }
                ShopItem item;
                try {
                    item = readItem(itemKey, itemSection, placement);
                } catch (Exception ex) {
                    logger.warning("HexNPC: shop '" + key + "' item '" + itemKey + "' skipped: " + ex.getMessage());
                    continue;
                }
                // Walidacja rozmieszczenia MANUAL (brak automatycznego
                // przesuwania — zachowujemy jawną konfigurację administratora).
                if (placement == PlacementMode.MANUAL
                        && !acceptManualSlot(key, itemKey, item, size, reservedNav, usedPageSlots)) {
                    continue;
                }
                items.put(itemKey.toLowerCase(Locale.ROOT), item);
            }
        }
        return new Shop(key, title, layout, items);
    }

    /**
     * Rozstrzyga tryb rozmieszczenia sklepu wg priorytetu:
     * <ol>
     *   <li>jawne {@code <shop>.placement},</li>
     *   <li>jawne {@code <shop>.layout.placement},</li>
     *   <li>obecność jawnego {@code slot} u itemu → MANUAL,</li>
     *   <li>globalny domyślny tryb z config.yml.</li>
     * </ol>
     * Gdy oba jawne wpisy istnieją i się różnią, wygrywa root {@code placement}
     * (z ostrzeżeniem). Nieprawidłowe wartości nie znikają po cichu — logujemy
     * ostrzeżenie i schodzimy do kolejnego prawidłowego fallbacku.
     */
    private PlacementMode resolvePlacement(String key, ConfigurationSection section,
                                           ConfigurationSection itemsSection, ShopConfig config) {
        PlacementMode root = parsePlacement(section.getString("placement"),
                "sklep '" + key + "' placement");
        ConfigurationSection layoutSection = section.getConfigurationSection("layout");
        PlacementMode fromLayout = parsePlacement(
                layoutSection == null ? null : layoutSection.getString("placement"),
                "sklep '" + key + "' layout.placement");
        if (root != null && fromLayout != null && root != fromLayout) {
            logger.warning("HexNPC: sklep '" + key + "': placement (" + root + ") i layout.placement ("
                    + fromLayout + ") różnią się — używam placement (" + root + ").");
        }
        if (root != null) {
            return root;
        }
        if (fromLayout != null) {
            return fromLayout;
        }
        if (anyItemHasExplicitSlot(itemsSection)) {
            return PlacementMode.MANUAL;
        }
        return config.defaultLayout().placement();
    }

    /** Zwraca tryb, {@code null} gdy brak wpisu; ostrzega gdy wartość nieprawidłowa. */
    private PlacementMode parsePlacement(String raw, String where) {
        if (raw == null) {
            return null;
        }
        String trimmed = raw.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        try {
            return PlacementMode.valueOf(trimmed.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            logger.warning("HexNPC: nieprawidłowy tryb rozmieszczenia '" + raw + "' w " + where
                    + " — pomijam ten wpis.");
            return null;
        }
    }

    /**
     * Sprawdza jawny slot itemu MANUAL względem zakresu GUI, slotów
     * nawigacji oraz duplikatów (page, slot). Zwraca false i loguje czytelne
     * ostrzeżenie (z ID sklepu, ID itemu, stroną i slotem), gdy item należy
     * pominąć. Ta sama liczba slotu na różnych stronach jest dozwolona.
     */
    private boolean acceptManualSlot(String shopId, String itemId, ShopItem item, int size,
                                     Set<Integer> reservedNav, Set<String> usedPageSlots) {
        int slot = item.slot();
        int page = item.page();
        if (slot == ShopItem.NO_SLOT) {
            logger.warning("HexNPC: sklep '" + shopId + "', przedmiot '" + itemId
                    + "' pominięty: tryb MANUAL wymaga jawnego slotu.");
            return false;
        }
        if (slot < 0 || slot >= size) {
            logger.warning("HexNPC: sklep '" + shopId + "', przedmiot '" + itemId + "' pominięty: slot "
                    + slot + " na stronie " + page + " jest poza zakresem GUI (0-" + (size - 1) + ").");
            return false;
        }
        if (reservedNav.contains(slot)) {
            logger.warning("HexNPC: sklep '" + shopId + "', przedmiot '" + itemId + "' pominięty: slot "
                    + slot + " na stronie " + page + " jest zarezerwowany dla nawigacji.");
            return false;
        }
        if (!usedPageSlots.add(page + ":" + slot)) {
            logger.warning("HexNPC: sklep '" + shopId + "', przedmiot '" + itemId + "' pominięty: slot "
                    + slot + " na stronie " + page + " jest już zajęty przez inny przedmiot.");
            return false;
        }
        return true;
    }

    private boolean anyItemHasExplicitSlot(ConfigurationSection itemsSection) {
        if (itemsSection == null) {
            return false;
        }
        for (String itemKey : itemsSection.getKeys(false)) {
            ConfigurationSection itemSection = itemsSection.getConfigurationSection(itemKey);
            if (itemSection != null && itemSection.contains("slot")) {
                return true;
            }
        }
        return false;
    }

    private ShopItem readItem(String key, ConfigurationSection section, PlacementMode placement) {
        String matName = section.getString("material");
        if (matName == null || matName.isBlank()) {
            throw new IllegalArgumentException("missing material");
        }
        Material material = Material.matchMaterial(matName);
        if (material == null) {
            throw new IllegalArgumentException("unknown material '" + matName + "'");
        }
        int amount = section.getInt("amount", 1);
        if (amount < 1) {
            throw new IllegalArgumentException("invalid amount: " + amount);
        }
        // Slot jest istotny tylko przy MANUAL (walidacja zakresu/kolizji odbywa
        // się w readShop). Przy AUTO slot oraz page są ignorowane.
        int page = placement == PlacementMode.MANUAL ? Math.max(0, section.getInt("page", 0)) : 0;
        int slot = placement == PlacementMode.MANUAL
                ? section.getInt("slot", ShopItem.NO_SLOT) : ShopItem.NO_SLOT;
        String displayName = section.getString("display-name", "");
        List<String> lore = new ArrayList<>(section.getStringList("lore"));
        BigDecimal buyPrice = readDecimal(section, "buy-price");
        BigDecimal sellPrice = readDecimal(section, "sell-price");
        boolean buyEnabled = section.getBoolean("buy-enabled", true);
        boolean sellEnabled = section.getBoolean("sell-enabled", true);
        SellMatch match = SellMatch.parse(section.getString("sell-match"), SellMatch.PLAIN_MATERIAL);
        int maxBuyAmount = Math.max(0, section.getInt("max-buy-amount", 0));
        return new ShopItem(key, material, amount, slot, page, displayName, lore,
                buyPrice, sellPrice, buyEnabled, sellEnabled, match, maxBuyAmount);
    }

    private BigDecimal readDecimal(ConfigurationSection section, String path) {
        Object raw = section.get(path);
        if (raw == null) {
            return BigDecimal.ZERO;
        }
        try {
            return new BigDecimal(raw.toString().trim()).stripTrailingZeros();
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException(path + " is not a number: " + raw);
        }
    }

    private static String readAll(Reader reader) throws IOException {
        StringBuilder sb = new StringBuilder(2048);
        char[] buf = new char[2048];
        int read;
        while ((read = reader.read(buf)) != -1) {
            sb.append(buf, 0, read);
        }
        return sb.toString();
    }
}
