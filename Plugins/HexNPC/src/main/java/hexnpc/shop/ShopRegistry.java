package hexnpc.shop;

import hexnpc.shop.config.ShopConfig;
import hexnpc.shop.model.SellMatch;
import hexnpc.shop.model.Shop;
import hexnpc.shop.model.ShopItem;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
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
        int sellSlot = section.getInt("sell-slot", config.defaultSellSlot());
        if (size <= 0 || size % 9 != 0 || size > 54) {
            throw new IllegalArgumentException("invalid size: " + size);
        }
        if (sellSlot < 0 || sellSlot >= size) {
            sellSlot = Math.max(0, size - 5);
        }
        ConfigurationSection itemsSection = section.getConfigurationSection("items");
        Map<String, ShopItem> items = new LinkedHashMap<>();
        if (itemsSection != null) {
            for (String itemKey : itemsSection.getKeys(false)) {
                ConfigurationSection itemSection = itemsSection.getConfigurationSection(itemKey);
                if (itemSection == null) {
                    logger.warning("HexNPC: shop '" + key + "' item '" + itemKey + "' malformed, skipped.");
                    continue;
                }
                try {
                    ShopItem item = readItem(itemKey, itemSection, size);
                    items.put(itemKey.toLowerCase(Locale.ROOT), item);
                } catch (Exception ex) {
                    logger.warning("HexNPC: shop '" + key + "' item '" + itemKey + "' skipped: " + ex.getMessage());
                }
            }
        }
        return new Shop(key, title, size, sellSlot, items);
    }

    private ShopItem readItem(String key, ConfigurationSection section, int shopSize) {
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
        int slot = section.getInt("slot", -1);
        if (slot < 0 || slot >= shopSize) {
            throw new IllegalArgumentException("slot out of bounds: " + slot);
        }
        String displayName = section.getString("display-name", "");
        List<String> lore = new ArrayList<>(section.getStringList("lore"));
        BigDecimal buyPrice = readDecimal(section, "buy-price");
        BigDecimal sellPrice = readDecimal(section, "sell-price");
        boolean buyEnabled = section.getBoolean("buy-enabled", true);
        boolean sellEnabled = section.getBoolean("sell-enabled", true);
        SellMatch match = SellMatch.parse(section.getString("sell-match"), SellMatch.PLAIN_MATERIAL);
        return new ShopItem(key, material, amount, slot, displayName, lore,
                buyPrice, sellPrice, buyEnabled, sellEnabled, match);
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
