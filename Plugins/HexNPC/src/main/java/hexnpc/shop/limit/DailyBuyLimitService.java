package hexnpc.shop.limit;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.LongSupplier;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Śledzi dzienny limit kupna na gracza i przedmiot, z automatycznym
 * resetem o północy (dnia serwera) i trwałą persystencją w pliku YAML.
 *
 * <p>Semantyka: {@code max-buy-amount} to maksymalna liczba sztuk danego
 * przedmiotu, jaką gracz może kupić w ciągu jednej doby. Licznik resetuje
 * się, gdy zapisany dzień różni się od bieżącego (porównanie po epoch-day),
 * więc reset nie wymaga zadania w tle.
 *
 * <p>Limit dotyczy wyłącznie kupna — sprzedaż nigdy z niego nie korzysta.
 *
 * <p>Dostęp do stanu jest synchronizowany, więc okresowy zapis w tle jest
 * bezpieczny względem zapisu licznika z głównego wątku.
 */
public final class DailyBuyLimitService {

    /** Wartość zwracana jako „bez limitu". */
    public static final int UNLIMITED = Integer.MAX_VALUE;

    private final File file;
    private final Logger logger;
    private final LongSupplier todaySupplier;

    // uuid -> (key -> entry). key = shopId:itemId.
    private final Map<UUID, Map<String, Entry>> data = new HashMap<>();
    private boolean dirty = false;

    public DailyBuyLimitService(File file, Logger logger) {
        this(file, logger, () -> LocalDate.now(ZoneId.systemDefault()).toEpochDay());
    }

    /** Wariant z wstrzykiwanym „dzisiaj" (epoch-day) — dla testów resetu. */
    public DailyBuyLimitService(File file, Logger logger, LongSupplier todaySupplier) {
        this.file = file;
        this.logger = logger;
        this.todaySupplier = todaySupplier;
    }

    /** Kanoniczny klucz limitu dla pary sklep:przedmiot. */
    public static String key(String shopId, String itemId) {
        return (shopId == null ? "" : shopId.toLowerCase(Locale.ROOT))
                + ":" + (itemId == null ? "" : itemId.toLowerCase(Locale.ROOT));
    }

    /** Ile sztuk gracz kupił dziś dla tego klucza (0 po resecie dnia). */
    public synchronized int purchasedToday(UUID uuid, String key) {
        Map<String, Entry> byKey = data.get(uuid);
        if (byKey == null) {
            return 0;
        }
        Entry entry = byKey.get(key);
        if (entry == null || entry.day != today()) {
            return 0;
        }
        return entry.count;
    }

    /**
     * Ile sztuk gracz może jeszcze kupić dziś. Gdy {@code limit <= 0}, brak
     * limitu — zwraca {@link #UNLIMITED}.
     */
    public synchronized int remaining(UUID uuid, String key, int limit) {
        if (limit <= 0) {
            return UNLIMITED;
        }
        return Math.max(0, limit - purchasedToday(uuid, key));
    }

    /** Dopisuje zakup do dzisiejszego licznika (resetując przy zmianie dnia). */
    public synchronized void record(UUID uuid, String key, int quantity) {
        if (uuid == null || key == null || quantity <= 0) {
            return;
        }
        Map<String, Entry> byKey = data.computeIfAbsent(uuid, u -> new LinkedHashMap<>());
        long today = today();
        Entry entry = byKey.get(key);
        if (entry == null || entry.day != today) {
            entry = new Entry(today, 0);
            byKey.put(key, entry);
        }
        // Zabezpieczenie przed przepełnieniem int.
        long updated = (long) entry.count + quantity;
        entry.count = (int) Math.min(Integer.MAX_VALUE, updated);
        dirty = true;
    }

    private long today() {
        return todaySupplier.getAsLong();
    }

    // --- Persystencja ---

    public synchronized void load() {
        data.clear();
        dirty = false;
        if (file == null || !file.exists()) {
            return;
        }
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection players = yaml.getConfigurationSection("players");
        if (players == null) {
            return;
        }
        for (String uuidKey : players.getKeys(false)) {
            UUID uuid;
            try {
                uuid = UUID.fromString(uuidKey);
            } catch (IllegalArgumentException ex) {
                continue;
            }
            ConfigurationSection entries = players.getConfigurationSection(uuidKey);
            if (entries == null) {
                continue;
            }
            Map<String, Entry> byKey = new LinkedHashMap<>();
            for (String entryKey : entries.getKeys(false)) {
                String raw = entries.getString(entryKey);
                Entry parsed = Entry.parse(raw);
                if (parsed != null) {
                    byKey.put(entryKey, parsed);
                }
            }
            if (!byKey.isEmpty()) {
                data.put(uuid, byKey);
            }
        }
    }

    public synchronized void save() {
        if (file == null) {
            return;
        }
        YamlConfiguration yaml = new YamlConfiguration();
        long today = today();
        for (Map.Entry<UUID, Map<String, Entry>> playerEntry : data.entrySet()) {
            for (Map.Entry<String, Entry> e : playerEntry.getValue().entrySet()) {
                Entry entry = e.getValue();
                // Nie zapisujemy przestarzałych (nie-dzisiejszych) wpisów —
                // i tak zresetowałyby się przy odczycie; plik pozostaje mały.
                if (entry.day != today || entry.count <= 0) {
                    continue;
                }
                yaml.set("players." + playerEntry.getKey() + "." + e.getKey(), entry.serialize());
            }
        }
        try {
            File parent = file.getParentFile();
            if (parent != null && !parent.exists()) {
                parent.mkdirs();
            }
            yaml.save(file);
            dirty = false;
        } catch (Exception ex) {
            if (logger != null) {
                logger.log(Level.WARNING, "HexNPC: nie udało się zapisać limitów kupna: " + ex.getMessage());
            }
        }
    }

    public synchronized void flushIfDirty() {
        if (dirty) {
            save();
        }
    }

    private static final class Entry {
        long day;
        int count;

        Entry(long day, int count) {
            this.day = day;
            this.count = count;
        }

        String serialize() {
            return day + ":" + count;
        }

        static Entry parse(String raw) {
            if (raw == null) {
                return null;
            }
            int idx = raw.indexOf(':');
            if (idx <= 0) {
                return null;
            }
            try {
                long day = Long.parseLong(raw.substring(0, idx).trim());
                int count = Integer.parseInt(raw.substring(idx + 1).trim());
                return new Entry(day, count);
            } catch (NumberFormatException ex) {
                return null;
            }
        }
    }
}
