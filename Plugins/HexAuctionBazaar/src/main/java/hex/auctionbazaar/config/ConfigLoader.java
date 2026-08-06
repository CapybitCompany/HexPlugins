package hex.auctionbazaar.config;

import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.logging.Logger;

public final class ConfigLoader {

    private ConfigLoader() {
    }

    public static PluginConfig load(File dataFolder, FileConfiguration main, Logger logger) {
        boolean enabled = main.getBoolean("enabled", true);
        boolean debug = main.getBoolean("debug", false);
        String prefix = main.getString("prefix", "");
        boolean economyRequired = main.getBoolean("economy.required", true);
        DatabaseConfig database = loadDatabase(main);

        // Prompt wpisywania wartości: hint na czacie po krótkiej chwili + timeout. Sekundy ograniczone
        // (boundedInputSeconds) tak, by mnożenie *20 (ticki) nie mogło przepełnić long-a (#8).
        long fallbackHintTicks = Math.max(20L,
                boundedInputSeconds(main, "input.fallback-hint-seconds", 4L, logger) * 20L);
        long timeoutTicks = Math.max(fallbackHintTicks + 20L,
                boundedInputSeconds(main, "input.timeout-seconds", 30L, logger) * 20L);

        AuctionConfig auction = loadAuction(main, logger);
        Map<String, BazaarItemConfig> items = loadBazaarItems(dataFolder, logger);
        BazaarConfig bazaar = loadBazaar(main, items, logger);
        MessagesConfig messages = loadMessages(dataFolder, logger);

        return new PluginConfig(enabled, debug, prefix, economyRequired, database,
                fallbackHintTicks, timeoutTicks, auction, bazaar, messages);
    }

    /**
     * Wczytuje sekcję database. Provider akceptuje wyłącznie HEXCORE - inna
     * wartość skutkuje polskim ostrzeżeniem i bezpiecznym fallbackiem na HEXCORE.
     * Żadnych host/port/username/password (dane są wyłącznie w HexCore/db.yml).
     */
    private static DatabaseConfig loadDatabase(FileConfiguration c) {
        String rawProvider = c.getString("database.provider", DatabaseConfig.PROVIDER_HEXCORE);
        String provider = DatabaseConfig.PROVIDER_HEXCORE;
        if (rawProvider == null || !rawProvider.trim().equalsIgnoreCase(DatabaseConfig.PROVIDER_HEXCORE)) {
            System.err.println("[HexAuctionBazaar] Nieznany database.provider '" + rawProvider
                    + "' - dozwolone jest tylko HEXCORE. Używam HEXCORE.");
        }
        boolean required = c.getBoolean("database.required", true);
        boolean healthCheck = c.getBoolean("database.health-check-on-startup", true);
        return new DatabaseConfig(provider, required, healthCheck);
    }

    /** Ścieżki 8 głównych slotów kontrolnych GUI aukcji (kolejność = indeks w tablicy). */
    private static final String[] CONTROL_SLOT_PATHS = {
            "auction.gui.slot-prev-page", "auction.gui.slot-next-page", "auction.gui.slot-refresh",
            "auction.gui.slot-my-listings", "auction.gui.slot-claims", "auction.gui.slot-sell-help",
            "auction.gui.slot-sort", "auction.gui.slot-empty-state"};
    /** Gwarantowanie kolizyjnie-wolny domyślny zestaw głównych slotów kontrolnych. */
    private static final int[] SAFE_CONTROL_SLOTS = {45, 53, 49, 47, 51, 48, 50, 22};

    private static AuctionConfig loadAuction(FileConfiguration c, Logger logger) {
        // Sloty kluczowych przyciskow. Walidowane w slot(...) - fallback do domyslnej wartosci
        // gdy podany slot lezy poza zakresem 0..53.
        int[] control = {
                slot(c, "auction.gui.slot-prev-page", 45, "poprzednia strona"),
                slot(c, "auction.gui.slot-next-page", 53, "następna strona"),
                slot(c, "auction.gui.slot-refresh", 49, "odświeżenie"),
                slot(c, "auction.gui.slot-my-listings", 47, "moje aukcje"),
                slot(c, "auction.gui.slot-claims", 51, "odbiory"),
                slot(c, "auction.gui.slot-sell-help", 48, "pomoc sprzedaży"),
                slot(c, "auction.gui.slot-sort", 50, "sortowanie"),
                slot(c, "auction.gui.slot-empty-state", 22, "pusty widok"),
        };
        // #11: przy JAKIEJKOLWIEK kolizji NIE zostawiamy nadpisujących się przycisków - podmieniamy CAŁY
        // zestaw na bezpieczny domyślny. Item-sloty walidujemy potem względem FINALNYCH slotów kontrolnych.
        control = resolveControlSlots(control, logger);
        int slotPrev = control[0], slotNext = control[1], slotRefresh = control[2], slotMy = control[3];
        int slotClaims = control[4], slotSellHelp = control[5], slotSort = control[6], slotEmpty = control[7];

        // Nawigacja widoków stronicowanych (Odbiór / Moje aukcje).
        int[] paged = loadPagedNavSlots(c);

        // Wspólna powierzchnia przedmiotów. Zwalidowana względem FINALNYCH slotów kontrolnych + nawigacji.
        java.util.Set<Integer> reserved = new java.util.HashSet<>(java.util.List.of(
                slotPrev, slotNext, slotRefresh, slotMy, slotClaims, slotSellHelp, slotSort, slotEmpty,
                paged[0], paged[1], paged[2], paged[3]));
        List<Integer> itemSlots = loadItemSlots(c, reserved);

        // Legacy fallback: gdy brak nowej sekcji listing-limits, uzyj starego
        // klucza max-active-listings-per-player.
        int legacyMax = Math.max(0, c.getInt("auction.max-active-listings-per-player", 10));
        int limitDefault = c.contains("auction.listing-limits.default")
                ? Math.max(0, c.getInt("auction.listing-limits.default", legacyMax))
                : legacyMax;
        List<ListingLimitTier> limitTiers = loadListingLimitTiers(c);

        BigDecimal saleFeePercent = clampPercent(bd(c.getString("auction.sale-fee-percent"), "10"),
                "auction.sale-fee-percent");
        List<SaleFeeTier> saleFeeTiers = loadSaleFeeTiers(c);

        // #7: Ceny aukcji znormalizowane (skala 2, HALF_UP) i zwalidowane wobec granic DECIMAL(19,2).
        BigDecimal minPrice = auctionMoney(bd(c.getString("auction.min-price"), "1"),
                new BigDecimal("1"), "auction.min-price", logger);
        if (minPrice.signum() <= 0) {
            logger.warning("config.yml: auction.min-price musi być większe od zera - ustawiam 0.01");
            minPrice = new BigDecimal("0.01");
        }
        BigDecimal maxPrice = auctionMoney(bd(c.getString("auction.max-price"), "1000000000"),
                new BigDecimal("1000000000"), "auction.max-price", logger);
        if (maxPrice.compareTo(minPrice) < 0) {
            logger.warning("config.yml: auction.max-price (" + maxPrice + ") jest mniejsze niż min-price ("
                    + minPrice + ") - ustawiam max-price = min-price");
            maxPrice = minPrice;
        }
        BigDecimal listingFee = auctionMoney(bd(c.getString("auction.listing-fee"), "0"),
                java.math.BigDecimal.ZERO, "auction.listing-fee", logger);
        if (listingFee.signum() < 0) {
            logger.warning("config.yml: auction.listing-fee nie może być ujemne - ustawiam 0");
            listingFee = hex.auctionbazaar.util.Money.normalize(java.math.BigDecimal.ZERO);
        }

        return new AuctionConfig(
                c.getBoolean("auction.enabled", true),
                boundedSeconds(c, "auction.default-duration-seconds", 86400L, 1L, logger),
                legacyMax,
                limitDefault,
                limitTiers,
                minPrice,
                maxPrice,
                listingFee,
                saleFeePercent,
                saleFeeTiers,
                boundedSeconds(c, "auction.reservation-ttl-seconds", 30L, 1L, logger),
                (int) Math.min(72000L, Math.max(200, c.getInt("auction.expiry-scan-interval-ticks", 1200))),
                c.getString("auction.gui.title", "&8&lDom Aukcyjny"),
                itemSlots,
                c.getString("auction.gui.my-listings-title", "&8Moje aukcje"),
                c.getString("auction.gui.claims-title", "&8Odbiór przedmiotów"),
                c.getString("auction.gui.confirm-title", "&8Potwierdź zakup"),
                c.getString("auction.gui.sell-title", "&8Wystaw przedmiot"),
                c.getString("auction.gui.frame-material", "BLACK_STAINED_GLASS_PANE"),
                slotPrev, slotNext, slotRefresh, slotMy, slotClaims,
                slotSellHelp, slotSort, slotEmpty,
                paged[0], paged[1], paged[2], paged[3],
                c.getString("auction.permissions.open", "hexauction.open"),
                c.getString("auction.permissions.sell", "hexauction.sell"),
                c.getString("auction.permissions.cancel-own", "hexauction.cancel"),
                c.getString("auction.permissions.admin", "hexauction.admin"),
                c.getString("auction.permissions.admin-audit", "hexauction.admin.audit")
        );
    }

    /**
     * Wczytuje i waliduje sloty nawigacji widoków stronicowanych.
     * Domyslnie: back=45, prev=48, next=50, page-info=49 (obszar przedmiotów to
     * sloty 0..44). Gdy layout jest niepoprawny (kolizja lub wejscie w obszar
     * przedmiotow) - polskie ostrzezenie i bezpieczne wartosci domyslne.
     */
    private static int[] loadPagedNavSlots(FileConfiguration c) {
        int back = c.getInt("auction.gui.paged-slot-back", 45);
        int prev = c.getInt("auction.gui.paged-slot-prev-page", 48);
        int next = c.getInt("auction.gui.paged-slot-next-page", 50);
        int info = c.getInt("auction.gui.paged-slot-page-info", 49);
        if (!hex.auctionbazaar.util.GuiSlots.navLayoutValid(54, 45, back, prev, next, info)) {
            System.err.println("[HexAuctionBazaar] Nieprawidłowy układ slotów nawigacji "
                    + "(auction.gui.paged-slot-*) - koliduje z obszarem przedmiotów lub innym slotem. "
                    + "Używam bezpiecznych wartości domyślnych (45/48/50/49).");
            return new int[]{45, 48, 50, 49};
        }
        return new int[]{back, prev, next, info};
    }

    /** Domyślna, symetryczna siatka przedmiotów (12 slotów, rzędy 1..3 z odstępami). */
    private static final List<Integer> DEFAULT_ITEM_SLOTS =
            List.of(10, 12, 14, 16, 19, 21, 23, 25, 28, 30, 32, 34);

    /**
     * Wczytuje i waliduje wspólną powierzchnię przedmiotów. Reguły: sloty 0..53,
     * bez duplikatów, bez kolizji z {@code reserved} (nawigacja/kontrolki), kolejność
     * zachowana. Nieprawidłowe -> polskie ostrzeżenie i bezpieczna siatka domyślna
     * (bez automatycznego przesuwania pojedynczych slotów).
     */
    private static List<Integer> loadItemSlots(FileConfiguration c, java.util.Set<Integer> reserved) {
        if (!c.contains("auction.gui.item-slots")) {
            return safeDefaultItemSlots(reserved);
        }
        List<?> raw = c.getList("auction.gui.item-slots");
        if (raw == null || raw.isEmpty()) {
            return safeDefaultItemSlots(reserved);
        }
        List<Integer> out = new ArrayList<>();
        java.util.Set<Integer> seen = new java.util.HashSet<>();
        boolean valid = true;
        for (Object o : raw) {
            int s;
            try {
                s = Integer.parseInt(String.valueOf(o).trim());
            } catch (NumberFormatException ex) {
                valid = false;
                break;
            }
            if (s < 0 || s > 53 || !seen.add(s) || reserved.contains(s)) {
                valid = false;
                break;
            }
            out.add(s);
        }
        if (!valid || out.isEmpty()) {
            System.err.println("[HexAuctionBazaar] Nieprawidłowe auction.gui.item-slots "
                    + "(poza zakresem 0..53, duplikat lub kolizja z nawigacją) - "
                    + "używam domyślnej, symetrycznej siatki.");
            return safeDefaultItemSlots(reserved);
        }
        return List.copyOf(out);
    }

    /**
     * Domyślna siatka przedmiotów przefiltrowana względem slotów kontrolnych - także fallback
     * NIE może kolidować z kontrolkami (punkt #11). Kolidujące sloty są pomijane z polskim
     * ostrzeżeniem, więc żaden przycisk nie zostaje po cichu przykryty przedmiotem.
     */
    private static List<Integer> safeDefaultItemSlots(java.util.Set<Integer> reserved) {
        List<Integer> out = new ArrayList<>();
        for (int s : DEFAULT_ITEM_SLOTS) {
            if (reserved.contains(s)) {
                System.err.println("[HexAuctionBazaar] Domyślny slot przedmiotu " + s
                        + " koliduje ze slotem kontrolnym - pomijam go w układzie domyślnym; popraw config.");
            } else {
                out.add(s);
            }
        }
        return List.copyOf(out);
    }

    private static List<ListingLimitTier> loadListingLimitTiers(FileConfiguration c) {
        List<ListingLimitTier> out = new ArrayList<>();
        ConfigurationSection s = c.getConfigurationSection("auction.listing-limits.tiers");
        if (s == null) return List.copyOf(out);
        for (String rawKey : s.getKeys(false)) {
            ConfigurationSection tier = s.getConfigurationSection(rawKey);
            if (tier == null) continue;
            String perm = tier.getString("permission");
            if (perm == null || perm.isBlank()) {
                System.err.println("[HexAuctionBazaar] Próg listing-limits '" + rawKey
                        + "' nie ma uprawnienia - pomijam.");
                continue;
            }
            int max = tier.getInt("max-active-listings", -1);
            if (max < 0) {
                System.err.println("[HexAuctionBazaar] Próg listing-limits '" + rawKey
                        + "' ma nieprawidłowe max-active-listings (" + max + ") - pomijam.");
                continue;
            }
            out.add(new ListingLimitTier(rawKey, perm, max));
        }
        return List.copyOf(out);
    }

    private static List<SaleFeeTier> loadSaleFeeTiers(FileConfiguration c) {
        List<SaleFeeTier> out = new ArrayList<>();
        ConfigurationSection s = c.getConfigurationSection("auction.sale-fee-tiers");
        if (s == null) return List.copyOf(out);
        for (String rawKey : s.getKeys(false)) {
            ConfigurationSection tier = s.getConfigurationSection(rawKey);
            if (tier == null) continue;
            String perm = tier.getString("permission");
            if (perm == null || perm.isBlank()) {
                System.err.println("[HexAuctionBazaar] Próg sale-fee-tiers '" + rawKey
                        + "' nie ma uprawnienia - pomijam.");
                continue;
            }
            BigDecimal pct = clampPercent(bd(tier.getString("sale-fee-percent"), "0"),
                    "sale-fee-tiers." + rawKey);
            out.add(new SaleFeeTier(rawKey, perm, pct));
        }
        return List.copyOf(out);
    }

    /** Waliduje procent do zakresu 0..100 z polskim ostrzezeniem. */
    private static BigDecimal clampPercent(BigDecimal value, String path) {
        BigDecimal clamped = hex.auctionbazaar.util.SaleTax.clampPercent(value);
        if (value != null && clamped.compareTo(value) != 0) {
            System.err.println("[HexAuctionBazaar] " + path + " (" + value
                    + ") poza zakresem 0..100 - używam " + clamped + ".");
        }
        return clamped;
    }

    /**
     * Bezpieczne odczytanie numeru slotu.
     * Zwraca {@code fallback} jesli wartosc nie jest w zakresie 0..53.
     */
    static int slot(FileConfiguration c, String path, int fallback, String label) {
        if (!c.contains(path)) return fallback;
        int raw = c.getInt(path, fallback);
        if (raw < 0 || raw > 53) {
            System.err.println("[HexAuctionBazaar] " + path + " (" + raw
                    + ") poza zakresem 0..53, używam wartości domyślnej " + fallback + " (" + label + ")");
            return fallback;
        }
        return raw;
    }

    /**
     * #11: przy JAKIEJKOLWIEK kolizji głównych slotów kontrolnych podmienia CAŁY zestaw na bezpieczny,
     * kolizyjnie-wolny domyślny (polskie ostrzeżenie). Zwraca finalny zestaw używany dalej do budowy
     * rezerwacji i walidacji item-slotów. Testowalne: sprawdza się FINALNE sloty, nie samą detekcję kolizji.
     */
    static int[] resolveControlSlots(int[] control, Logger logger) {
        Map<String, Integer> named = new LinkedHashMap<>();
        for (int i = 0; i < CONTROL_SLOT_PATHS.length; i++) {
            named.put(CONTROL_SLOT_PATHS[i], control[i]);
        }
        List<String> collisions = hex.auctionbazaar.util.GuiSlots.findControlCollisions(named);
        if (collisions.isEmpty()) {
            return control;
        }
        logger.warning("config.yml: kolizje głównych slotów kontrolnych GUI aukcji ("
                + String.join("; ", collisions)
                + ") - używam bezpiecznego, kolizyjnie-wolnego zestawu domyślnego "
                + java.util.Arrays.toString(SAFE_CONTROL_SLOTS) + ".");
        return SAFE_CONTROL_SLOTS.clone();
    }

    /**
     * #8: górna granica sekund dla terminów (expiresAt/reservedUntil) - ~10 lat. Chroni {@code now +
     * seconds*1000} przed przepełnieniem, które dałoby UJEMNY termin. Wartość spoza [min, MAX] -> polskie
     * ostrzeżenie i udokumentowany bezpieczny default (min lub MAX).
     */
    static final long MAX_DEADLINE_SECONDS = 315_360_000L;   // ~10 lat

    static long boundedSeconds(FileConfiguration c, String path, long def, long min, Logger logger) {
        long raw = c.getLong(path, def);
        if (raw < min) {
            logger.warning("config.yml: " + path + " (" + raw + ") poniżej minimum " + min
                    + " - ustawiam " + min + ".");
            return min;
        }
        if (raw > MAX_DEADLINE_SECONDS) {
            logger.warning("config.yml: " + path + " (" + raw + ") powyżej maksimum "
                    + MAX_DEADLINE_SECONDS + " (ochrona przed przepełnieniem terminu) - ustawiam "
                    + MAX_DEADLINE_SECONDS + ".");
            return MAX_DEADLINE_SECONDS;
        }
        return raw;
    }

    /** #8: sekundy wejścia (hint/timeout) mnożone przez 20 (ticki) - ograniczamy do 1h, by uniknąć przepełnienia. */
    static long boundedInputSeconds(FileConfiguration c, String path, long def, Logger logger) {
        long raw = c.getLong(path, def);
        long max = 3600L;   // 1 godzina - sensowny górny limit dla promptu wprowadzania
        if (raw < 0) {
            logger.warning("config.yml: " + path + " (" + raw + ") ujemne - ustawiam " + def + ".");
            return def;
        }
        if (raw > max) {
            logger.warning("config.yml: " + path + " (" + raw + ") powyżej " + max
                    + "s - ustawiam " + max + ".");
            return max;
        }
        return raw;
    }

    /**
     * Klemuje wartość cenową do sensownego zakresu [min, max] (max=null bez górnej granicy);
     * przy korekcie loguje polskie ostrzeżenie. Zapobiega bezsensownym parametrom pricingu
     * (np. ujemna elastyczność, zerowy reference-stock, spread &gt; 100%).
     */
    static BigDecimal clampPricing(BigDecimal value, BigDecimal min, BigDecimal max, String path) {
        if (value == null) {
            return min;
        }
        BigDecimal v = value;
        if (v.compareTo(min) < 0) {
            v = min;
        }
        if (max != null && v.compareTo(max) > 0) {
            v = max;
        }
        if (v.compareTo(value) != 0) {
            System.err.println("[HexAuctionBazaar] " + path + " (" + value.toPlainString()
                    + ") poza dozwolonym zakresem - używam " + v.toPlainString() + ".");
        }
        return v;
    }

    private static BazaarConfig loadBazaar(FileConfiguration c, Map<String, BazaarItemConfig> items,
                                           Logger logger) {
        // #7: kategorie i widoczność przedmiotów. GUI filtruje przedmioty ściśle po kluczu kategorii,
        // a przyciski kategorii pochodzą wyłącznie z tej mapy. Kroki:
        //  1) wczytaj kategorie (walidacja materiału; brak sekcji -> bezpieczna domyślna 'ogólne'),
        //  2) ogranicz do 5 WIDOCZNYCH (GUI ma 5 slotów),
        //  3) przypisz przedmioty spoza widocznych kategorii do pierwszej widocznej (dostępność).
        Map<String, BazaarConfig.CategoryConfig> categories =
                capVisibleCategories(loadCategories(c, logger), logger);
        items = ensureItemsReachable(items, categories, logger);
        BazaarConfig.Pricing pricing = new BazaarConfig.Pricing(
                clampPricing(bd(c.getString("bazaar.pricing.elasticity"), "0.5"),
                        BigDecimal.ZERO, null, "bazaar.pricing.elasticity"),
                clampPricing(bd(c.getString("bazaar.pricing.reference-stock"), "10000"),
                        BigDecimal.ONE, null, "bazaar.pricing.reference-stock"),
                clampPricing(bd(c.getString("bazaar.pricing.buy-sell-spread-percent"), "5"),
                        BigDecimal.ZERO, new BigDecimal("100"), "bazaar.pricing.buy-sell-spread-percent"),
                clampPricing(bd(c.getString("bazaar.pricing.max-step-per-transaction-percent"), "5"),
                        BigDecimal.ZERO, new BigDecimal("100"), "bazaar.pricing.max-step-per-transaction-percent")
        );
        return new BazaarConfig(
                c.getBoolean("bazaar.enabled", true),
                c.getBoolean("bazaar.require-plain-item", true),
                Math.max(1, c.getInt("bazaar.max-orders-per-player", 14)),
                boundedSeconds(c, "bazaar.order-expiry-seconds", 0L, 0L, logger),
                (int) Math.min(72000L, Math.max(200, c.getInt("bazaar.order-expiry-scan-interval-ticks", 6000))),
                pricing,
                c.getString("bazaar.gui.title", "&8&lRynek"),
                c.getString("bazaar.gui.item-title", "&8%display%"),
                c.getString("bazaar.gui.quantity-title", "&8Wybierz ilość"),
                c.getString("bazaar.gui.orders-title", "&8Moje zlecenia"),
                c.getString("bazaar.gui.order-create-title", "&8Twórz zlecenie"),
                c.getString("bazaar.gui.frame-material", "GRAY_STAINED_GLASS_PANE"),
                loadQuantityOptions(c),
                c.getBoolean("bazaar.gui.auto-refresh-enabled", false),
                Math.max(20, c.getInt("bazaar.gui.auto-refresh-interval-ticks", 60)),
                categories,
                c.getString("bazaar.permissions.open", "hexbazaar.open"),
                c.getString("bazaar.permissions.buy", "hexbazaar.buy"),
                c.getString("bazaar.permissions.sell", "hexbazaar.sell"),
                c.getString("bazaar.permissions.orders", "hexbazaar.orders"),
                c.getString("bazaar.permissions.order-buy", "hexbazaar.order.create.buy"),
                c.getString("bazaar.permissions.order-sell", "hexbazaar.order.create.sell"),
                c.getString("bazaar.permissions.order-cancel", "hexbazaar.order.cancel"),
                c.getString("bazaar.permissions.admin", "hexbazaar.admin"),
                items
        );
    }

    private static List<Long> loadQuantityOptions(FileConfiguration c) {
        List<Long> defaults = List.of(1L, 64L, 576L);
        if (!c.contains("bazaar.gui.quantity-options")) {
            return defaults;
        }
        List<?> raw = c.getList("bazaar.gui.quantity-options");
        if (raw == null || raw.isEmpty()) return defaults;
        List<Long> out = new ArrayList<>();
        for (Object o : raw) {
            try {
                long v = Long.parseLong(String.valueOf(o).trim());
                if (v > 0) out.add(v);
            } catch (NumberFormatException ignored) {
            }
        }
        return out.isEmpty() ? defaults : out;
    }

    /** GUI Bazaru ma dokładnie 5 slotów kategorii - tyle jest maksymalnie WIDOCZNYCH kategorii. */
    static final int MAX_VISIBLE_CATEGORIES = 5;

    private static Map<String, BazaarConfig.CategoryConfig> loadCategories(FileConfiguration c, Logger logger) {
        Map<String, BazaarConfig.CategoryConfig> out = new LinkedHashMap<>();
        ConfigurationSection s = c.getConfigurationSection("bazaar.categories");
        if (s != null) {
            for (String rawKey : s.getKeys(false)) {
                ConfigurationSection cat = s.getConfigurationSection(rawKey);
                if (cat == null) continue;
                String key = rawKey.toLowerCase(Locale.ROOT);
                out.put(key, new BazaarConfig.CategoryConfig(
                        key,
                        cat.getString("display-name", key),
                        validCategoryMaterial(cat.getString("material", "CHEST"), key, logger)
                ));
            }
        }
        if (out.isEmpty()) {
            // #7: bez kategorii żaden przedmiot nie ma przycisku - tworzymy bezpieczną domyślną,
            // by przedmioty pozostały osiągalne w GUI (zamiast być niewidoczne do naprawy configu).
            logger.warning("config.yml: brak sekcji bazaar.categories - tworzę domyślną kategorię "
                    + "'ogólne', aby przedmioty pozostały dostępne w GUI.");
            out.put("ogólne", new BazaarConfig.CategoryConfig("ogólne", "Ogólne", "CHEST"));
        }
        return out;
    }

    /** Waliduje materiał kategorii; nieznany -> bezpieczny CHEST z polskim ostrzeżeniem. */
    private static String validCategoryMaterial(String raw, String catKey, Logger logger) {
        String value = raw == null ? "CHEST" : raw;
        try {
            Material.valueOf(value.toUpperCase(Locale.ROOT));
            return value;
        } catch (IllegalArgumentException ex) {
            logger.warning("config.yml: nieznany materiał kategorii '" + raw + "' dla '" + catKey
                    + "' - ustawiam CHEST.");
            return "CHEST";
        }
    }

    /**
     * #7: GUI ma tylko {@link #MAX_VISIBLE_CATEGORIES} slotów kategorii. Ograniczamy widoczne kategorie
     * do pierwszych 5 (kolejność z configu). Przedmioty z pozostałych kategorii i tak pozostają dostępne,
     * bo {@link #ensureItemsReachable} przypisze je do pierwszej WIDOCZNEJ kategorii.
     */
    static Map<String, BazaarConfig.CategoryConfig> capVisibleCategories(
            Map<String, BazaarConfig.CategoryConfig> all, Logger logger) {
        if (all.size() <= MAX_VISIBLE_CATEGORIES) {
            return all;
        }
        logger.warning("config.yml: zdefiniowano " + all.size() + " kategorii, a GUI ma tylko "
                + MAX_VISIBLE_CATEGORIES + " slotów - widoczne będą pierwsze " + MAX_VISIBLE_CATEGORIES
                + ", a przedmioty pozostałych trafią do pierwszej widocznej kategorii.");
        Map<String, BazaarConfig.CategoryConfig> out = new LinkedHashMap<>();
        int i = 0;
        for (Map.Entry<String, BazaarConfig.CategoryConfig> e : all.entrySet()) {
            if (i++ >= MAX_VISIBLE_CATEGORIES) break;
            out.put(e.getKey(), e.getValue());
        }
        return out;
    }

    private static Map<String, BazaarItemConfig> loadBazaarItems(File dataFolder, Logger logger) {
        File file = new File(dataFolder, "bazaar-items.yml");
        YamlConfiguration yaml = loadYaml(file, "bazaar-items.yml", logger);
        ConfigurationSection items = yaml.getConfigurationSection("items");
        if (items == null) {
            return Map.of();
        }
        Map<String, BazaarItemConfig> out = new LinkedHashMap<>();
        for (String rawKey : items.getKeys(false)) {
            ConfigurationSection s = items.getConfigurationSection(rawKey);
            if (s == null) continue;
            String key = rawKey.toLowerCase(Locale.ROOT);
            if (key.length() > 64) {
                // item_key jest kluczem w DB (najmniejsza kolumna 64 znaki) - dłuższy zostałby
                // obcięty i rozspójniłby zlecenia/stany. Pomijamy wpis z ostrzeżeniem.
                logger.warning("bazaar-items.yml: klucz '" + key + "' przekracza 64 znaki "
                        + "(limit kolumny bazy) - pomijam wpis");
                continue;
            }
            String materialName = s.getString("material", "AIR");
            Material material;
            try {
                material = Material.valueOf(materialName.toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException ex) {
                logger.warning("bazaar-items.yml: nieznany materiał '" + materialName
                        + "' dla " + key + " - pomijam wpis");
                continue;
            }
            if (material == Material.AIR) {
                logger.warning("bazaar-items.yml: materiał AIR jest niedozwolony dla "
                        + key + " - pomijam wpis");
                continue;
            }
            BigDecimal minPrice = positiveBazaarPrice(
                    bd(s.getString("min-price"), "0.01"), new BigDecimal("0.01"),
                    key, "min-price", logger);
            BigDecimal maxPrice = positiveBazaarPrice(
                    bd(s.getString("max-price"), "1000000"), new BigDecimal("1000000"),
                    key, "max-price", logger);
            if (maxPrice.compareTo(minPrice) < 0) {
                logger.warning("bazaar-items.yml: max-price dla " + key
                        + " jest mniejsze niż min-price - ustawiam max-price na " + minPrice);
                maxPrice = minPrice;
            }
            BigDecimal basePrice = positiveBazaarPrice(
                    bd(s.getString("base-price"), "1"), minPrice,
                    key, "base-price", logger);
            if (basePrice.compareTo(minPrice) < 0 || basePrice.compareTo(maxPrice) > 0) {
                BigDecimal clamped = basePrice.max(minPrice).min(maxPrice);
                logger.warning("bazaar-items.yml: base-price dla " + key
                        + " jest poza zakresem min/max - ustawiam " + clamped);
                basePrice = clamped;
            }
            BazaarItemConfig item = new BazaarItemConfig(
                    key,
                    material,
                    s.getString("display-name", key),
                    s.getString("category", "default"),
                    basePrice,
                    minPrice,
                    maxPrice,
                    Math.max(0L, s.getLong("initial-stock", 0L)),
                    s.getBoolean("buy-enabled", true),
                    s.getBoolean("sell-enabled", true)
            );
            out.put(key, item);
        }
        return Map.copyOf(out);
    }

    /**
     * #7: cena Bazaru znormalizowana centralnie ({@link hex.auctionbazaar.util.Money#normalize}: skala 2,
     * HALF_UP) i sprawdzona wobec granic {@code DECIMAL(19,2)} ({@link hex.auctionbazaar.util.Money#fits}).
     * Wartość niedodatnia lub spoza zakresu -> polskie ostrzeżenie i (znormalizowany) bezpieczny fallback.
     * Dzięki temu żadna surowa cena z >2 miejscami po przecinku nie trafia do GUI/ekonomii/DB/audytu.
     */
    private static BigDecimal positiveBazaarPrice(BigDecimal value, BigDecimal fallback,
                                                   String itemKey, String field, Logger logger) {
        BigDecimal n = hex.auctionbazaar.util.Money.normalize(value);
        if (n != null && n.signum() > 0 && hex.auctionbazaar.util.Money.fits(n)) {
            return n;
        }
        BigDecimal fb = hex.auctionbazaar.util.Money.normalize(fallback);
        logger.warning("bazaar-items.yml: " + field + " dla " + itemKey + " (" + value
                + ") musi być > 0 i mieścić się w DECIMAL(19,2) - ustawiam " + fb + ".");
        return fb;
    }

    /**
     * #7: normalizuje kwotę do skali 2 (HALF_UP) i pilnuje granic DECIMAL(19,2).
     * Wartość spoza zakresu jest odrzucana na rzecz (znormalizowanego) fallbacku,
     * dzięki czemu ceny zapisane w bazie/ekonomii/audycie nigdy nie przepełnią kolumny.
     */
    static BigDecimal auctionMoney(BigDecimal value, BigDecimal fallback, String field, Logger logger) {
        BigDecimal n = hex.auctionbazaar.util.Money.normalize(value);
        if (n == null || !hex.auctionbazaar.util.Money.fits(n)) {
            BigDecimal fb = hex.auctionbazaar.util.Money.normalize(fallback);
            logger.warning("config.yml: " + field + " (" + value
                    + ") poza zakresem DECIMAL(19,2) - ustawiam " + fb);
            return fb;
        }
        return n;
    }

    /**
     * #7: Gwarantuje, że każdy przedmiot Bazaru jest OSIĄGALNY w GUI. Główne GUI pokazuje
     * wyłącznie przedmioty, których kategoria pasuje do istniejącego przycisku kategorii.
     * Przedmiot z nieznaną kategorią nie miałby przycisku i pozostałby niewidoczny/niekupny -
     * przypisujemy go do pierwszej (domyślnej) kategorii z ostrzeżeniem. Gdy nie ma żadnej
     * kategorii, ostrzegamy raz (żaden przedmiot nie jest wtedy dostępny bez naprawy configu).
     */
    static Map<String, BazaarItemConfig> ensureItemsReachable(
            Map<String, BazaarItemConfig> items,
            Map<String, BazaarConfig.CategoryConfig> categories, Logger logger) {
        if (items.isEmpty()) {
            return items;
        }
        if (categories.isEmpty()) {
            logger.warning("config.yml: brak zdefiniowanych kategorii Bazaru, a istnieją przedmioty ("
                    + items.size() + ") - żaden nie będzie dostępny w GUI, dopóki nie dodasz kategorii");
            return items;
        }
        java.util.Set<String> known = new java.util.HashSet<>();
        for (String k : categories.keySet()) {
            known.add(k.toLowerCase());
        }
        String fallback = categories.keySet().iterator().next();
        Map<String, BazaarItemConfig> out = new LinkedHashMap<>();
        for (Map.Entry<String, BazaarItemConfig> e : items.entrySet()) {
            BazaarItemConfig it = e.getValue();
            String cat = it.category();
            if (cat != null && known.contains(cat.toLowerCase())) {
                out.put(e.getKey(), it);
                continue;
            }
            logger.warning("bazaar-items.yml: przedmiot '" + it.key() + "' ma nieznaną kategorię '"
                    + cat + "' (brak przycisku) - przypisuję do '" + fallback + "', aby pozostał dostępny");
            out.put(e.getKey(), new BazaarItemConfig(it.key(), it.material(), it.displayName(),
                    fallback, it.basePrice(), it.minPrice(), it.maxPrice(), it.initialStock(),
                    it.buyEnabled(), it.sellEnabled()));
        }
        return Map.copyOf(out);
    }

    /**
     * Ładuje messages.yml z MERGE względem zasobu domyślnego z JAR:
     *  1) najpierw wypełniamy WSZYSTKIE klucze z gebündelten defaults,
     *  2) potem nadpisujemy wartościami użytkownika (użytkownik ma pierwszeństwo).
     * Dzięki temu stary/niekompletny plik gracza nadal rozwiązuje każdy klucz - żadnego
     * widocznego „missing message: …". Wartości użytkownika NIGDY nie są nadpisywane.
     */
    static MessagesConfig loadMessages(File dataFolder, Logger logger) {
        File file = new File(dataFolder, "messages.yml");
        YamlConfiguration yaml = loadYaml(file, "messages.yml", logger);
        Map<String, String> flat = new HashMap<>();
        flattenBundledDefaults("messages.yml", flat, logger);   // warstwa fallback
        flatten(yaml, "", flat);                                 // nadpisanie użytkownika
        return new MessagesConfig(flat);
    }

    /** Spłaszcza domyślne wiadomości z zasobu w JAR jako warstwę fallback (UTF-8). */
    private static void flattenBundledDefaults(String resource, Map<String, String> out, Logger logger) {
        try (InputStream in = ConfigLoader.class.getResourceAsStream("/" + resource)) {
            if (in == null) {
                return;
            }
            YamlConfiguration def = new YamlConfiguration();
            try (InputStreamReader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
                def.load(reader);
            }
            flatten(def, "", out);
        } catch (Exception ex) {
            logger.warning("Nie udało się wczytać domyślnych wiadomości z JAR: " + ex.getMessage());
        }
    }

    private static void flatten(ConfigurationSection section, String prefix, Map<String, String> out) {
        for (String key : section.getKeys(false)) {
            String path = prefix.isEmpty() ? key : prefix + "." + key;
            Object value = section.get(key);
            if (value instanceof ConfigurationSection nested) {
                flatten(nested, path, out);
            } else if (value != null) {
                out.put(path, value.toString());
            }
        }
    }

    private static YamlConfiguration loadYaml(File file, String defaultResource, Logger logger) {
        if (!file.exists()) {
            // Try to extract default from jar resources.
            try (InputStream in = ConfigLoader.class.getResourceAsStream("/" + defaultResource)) {
                if (in != null) {
                    file.getParentFile().mkdirs();
                    java.nio.file.Files.copy(in, file.toPath());
                } else {
                    logger.warning("Nie znaleziono zasobu " + defaultResource + " w pliku JAR.");
                }
            } catch (IOException ex) {
                logger.warning("Nie udało się wypakować " + defaultResource + ": " + ex.getMessage());
            }
        }
        YamlConfiguration yaml = new YamlConfiguration();
        try (InputStreamReader reader = new InputStreamReader(
                new java.io.FileInputStream(file), StandardCharsets.UTF_8)) {
            yaml.load(reader);
        } catch (Exception ex) {
            logger.warning("Nie udało się wczytać " + file.getName() + ": " + ex.getMessage());
        }
        return yaml;
    }

    private static BigDecimal bd(String raw, String fallback) {
        if (raw == null || raw.isBlank()) {
            return new BigDecimal(fallback);
        }
        try {
            return new BigDecimal(raw);
        } catch (NumberFormatException ex) {
            return new BigDecimal(fallback);
        }
    }
}
