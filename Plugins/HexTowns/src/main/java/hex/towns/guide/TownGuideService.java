package hex.towns.guide;

import hex.towns.gui.NativeTownMenuHolder;
import hex.towns.model.Town;
import hex.towns.service.TownsService;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.Plugin;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

/** Shared entry point used by /town guide, TownsApi and all HexTowns GUI entry points. */
public final class TownGuideService {
    private static final int BACK_SLOT = 45;
    private static final int CLOSE_SLOT = 49;
    private static final DecimalFormat NUMBER = new DecimalFormat("#,###", DecimalFormatSymbols.getInstance(Locale.forLanguageTag("pl-PL")));

    private final Plugin plugin;
    private final TownsService towns;
    private volatile YamlConfiguration config;
    private volatile Consumer<Player> townMenuOpener;

    public TownGuideService(Plugin plugin, TownsService towns) {
        this.plugin = plugin;
        this.towns = towns;
        reload();
    }

    public void setTownMenuOpener(Consumer<Player> townMenuOpener) {
        this.townMenuOpener = townMenuOpener;
    }

    public void reload() {
        File file = new File(plugin.getDataFolder(), "guide.yml");
        if (!file.exists()) plugin.saveResource("guide.yml", false);
        this.config = YamlConfiguration.loadConfiguration(file);
    }

    public void open(Player player) {
        if (player == null) return;
        Inventory inv = inventory(player, NativeTownMenuHolder.Page.GUIDE, 54, text("title", "&8Przewodnik miasta"));
        fill(inv);
        inv.setItem(11, cityGuideItem());
        inv.setItem(13, growthMainItem(player));
        inv.setItem(15, configuredItem("main.players", Material.PLAYER_HEAD));
        inv.setItem(29, configuredItem("main.collections", Material.BOOK));
        inv.setItem(31, configuredItem("main.minions", Material.IRON_INGOT));
        inv.setItem(33, configuredItem("main.claims", Material.GRASS_BLOCK));
        inv.setItem(BACK_SLOT, item(Material.ARROW, "&ePowrót", List.of("&7Wróć do menu miasta.")));
        inv.setItem(CLOSE_SLOT, item(Material.BARRIER, "&cZamknij", List.of()));
        player.openInventory(inv);
    }

    public void openGrowth(Player player) {
        if (player == null) return;
        Inventory inv = inventory(player, NativeTownMenuHolder.Page.GUIDE_GROWTH, 54, text("growth-title", "&8Punkty Miasta"));
        fill(inv);
        Town town = towns.townIdOf(player.getUniqueId()).flatMap(towns::findTown).orElse(null);
        List<String> summary = new ArrayList<>();
        if (town == null) {
            summary.addAll(lines("growth.no-town"));
        } else {
            summary.add("&7Miasto: &f" + town.name());
            summary.add("&7Aktualnie: &a" + town.growthPoints() + " Punktów Miasta");
            summary.add("");
            summary.add("&7Poniższe cele pochodzą bezpośrednio");
            summary.add("&7z aktywnych osiągnięć progresji miasta.");
        }
        inv.setItem(4, item(Material.EXPERIENCE_BOTTLE, text("growth.summary-name", "&aJak zdobywać Punkty Miasta?"), summary));

        if (town == null) {
            inv.setItem(20, item(Material.IRON_INGOT, categoryName("MINION_DEVELOPMENT"), List.of("&7Rozwijaj tiery i liczbę minionów.")));
            inv.setItem(22, item(Material.PLAYER_HEAD, categoryName("MINION_DIVERSITY"), List.of("&7Stawiaj różne typy minionów.")));
            inv.setItem(24, item(Material.BOOKSHELF, categoryName("COLLECTION_BREADTH"), List.of("&7Rozwijaj wiele kolekcji równolegle.")));
            inv.setItem(31, item(Material.ENCHANTED_BOOK, categoryName("COLLECTION_COMPLETION"), List.of("&7Osiągaj najwyższe poziomy kolekcji.")));
        } else {
            Map<String, List<Object>> grouped = growthProgress(town.id(), player);
            putCategory(inv, 20, Material.IRON_INGOT, "MINION_DEVELOPMENT", grouped.get("MINION_DEVELOPMENT"));
            putCategory(inv, 22, Material.PLAYER_HEAD, "MINION_DIVERSITY", grouped.get("MINION_DIVERSITY"));
            putCategory(inv, 24, Material.BOOKSHELF, "COLLECTION_BREADTH", grouped.get("COLLECTION_BREADTH"));
            putCategory(inv, 31, Material.ENCHANTED_BOOK, "COLLECTION_COMPLETION", grouped.get("COLLECTION_COMPLETION"));
            if (grouped.containsKey("OTHER")) putCategory(inv, 33, Material.NETHER_STAR, "OTHER", grouped.get("OTHER"));
        }
        inv.setItem(BACK_SLOT, item(Material.ARROW, "&ePowrót", List.of("&7Wróć do przewodnika.")));
        inv.setItem(CLOSE_SLOT, item(Material.BARRIER, "&cZamknij", List.of()));
        player.openInventory(inv);
    }

    public void openPlayers(Player player) {
        if (player == null) return;
        Inventory inv = inventory(player, NativeTownMenuHolder.Page.GUIDE_PLAYERS, 54, text("players-title", "&8Gracze i progresja"));
        fill(inv);
        List<String> lore = new ArrayList<>(lines("players.lore"));
        towns.townIdOf(player.getUniqueId()).flatMap(towns::findTown).ifPresent(town -> {
            lore.add("");
            lore.add("&7Gracze w twoim mieście: &f" + towns.membersOf(town).size());
        });
        inv.setItem(20, item(Material.PLAYER_HEAD, text("players.name", "&bWpływ liczby graczy na progresję"), lore));
        inv.setItem(24, item(Material.ANVIL, text("players.sticky-name", "&6Zwiększone wymaganie zostaje"), lines("players.sticky-lore")));
        inv.setItem(31, item(Material.BOOK, "&aNajważniejsza zasada", List.of(
                "&7Więcej graczy w mieście zwiększa wymagania",
                "&7kolekcji i koszt kolejnych ulepszeń minionów.",
                "",
                "&7W ekranie kolekcji nadal widzisz po prostu",
                "&7aktualne: zebrano / wymagane."
        )));
        inv.setItem(BACK_SLOT, item(Material.ARROW, "&ePowrót", List.of("&7Wróć do przewodnika.")));
        inv.setItem(CLOSE_SLOT, item(Material.BARRIER, "&cZamknij", List.of()));
        player.openInventory(inv);
    }

    public void handleClick(Player player, NativeTownMenuHolder.Page page, int slot) {
        if (slot == CLOSE_SLOT) { player.closeInventory(); return; }
        if (page == NativeTownMenuHolder.Page.GUIDE) {
            if (slot == 13) { openGrowth(player); return; }
            if (slot == 15) { openPlayers(player); return; }
            if (slot == BACK_SLOT) { openTownMenu(player); }
            return;
        }
        if ((page == NativeTownMenuHolder.Page.GUIDE_GROWTH || page == NativeTownMenuHolder.Page.GUIDE_PLAYERS) && slot == BACK_SLOT) {
            open(player);
        }
    }

    private void openTownMenu(Player player) {
        player.closeInventory();
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (!player.isOnline()) return;
            Consumer<Player> opener = townMenuOpener;
            if (opener != null) {
                // NativeTownMenu.openMain(...) resolves membership at the moment of return:
                // no town -> outsider/main view, town member -> member/manage view.
                opener.accept(player);
                return;
            }
            // Defensive fallback for unusual startup/integration paths.
            player.performCommand("townmenu");
        });
    }

    private void putCategory(Inventory inv, int slot, Material icon, String category, List<Object> raw) {
        List<Object> entries = raw == null ? List.of() : new ArrayList<>(raw);
        entries.sort(Comparator.comparing((Object view) -> bool(view, "completed")).thenComparingLong(view -> remaining(view)));
        long completed = entries.stream().filter(view -> bool(view, "completed")).count();
        List<String> lore = new ArrayList<>();
        lore.add("&7Zdobyto: &a" + completed + "&7/&f" + entries.size());
        if (entries.isEmpty()) {
            lore.add("&8Brak aktywnych celów w tej kategorii.");
        } else {
            lore.add("");
            int shown = 0;
            for (Object view : entries) {
                if (shown >= 6) break;
                boolean done = bool(view, "completed");
                long current = longValue(view, "current");
                long required = Math.max(1L, longValue(view, "required"));
                int points = (int) longValue(view, "growthPoints");
                String title = string(view, "title");
                lore.add((done ? "&a✓ " : "&e• ") + "&f" + trim(title, 28) + " &8(+" + points + ")");
                if (!done) lore.add("  &7Postęp: &f" + NUMBER.format(Math.min(current, required)) + "&7/&f" + NUMBER.format(required));
                shown++;
            }
            if (entries.size() > shown) lore.add("&8... i " + (entries.size() - shown) + " kolejnych celów.");
        }
        inv.setItem(slot, item(icon, categoryName(category), lore));
    }

    private Map<String, List<Object>> growthProgress(UUID townId, Player viewer) {
        Object api = service("hex.minions.api.MinionsApi");
        if (api == null) return Map.of();
        Object raw = invoke(api, "growthPointAdvancements", new Class<?>[]{UUID.class, Player.class}, townId, viewer);
        if (!(raw instanceof List<?> list)) return Map.of();
        Map<String, List<Object>> result = new LinkedHashMap<>();
        for (Object view : list) {
            if (view == null) continue;
            String category = string(view, "category");
            if (category.isBlank()) category = "OTHER";
            result.computeIfAbsent(category, ignored -> new ArrayList<>()).add(view);
        }
        return result;
    }

    private Object service(String className) {
        try {
            Class<?> type = Class.forName(className);
            var registration = Bukkit.getServicesManager().getRegistration(type);
            return registration == null ? null : registration.getProvider();
        } catch (Throwable ignored) { return null; }
    }

    private Object invoke(Object target, String method, Class<?>[] types, Object... args) {
        if (target == null) return null;
        try { return target.getClass().getMethod(method, types).invoke(target, args); }
        catch (Throwable ignored) { return null; }
    }

    private String string(Object target, String method) {
        Object value = invoke(target, method, new Class<?>[0]);
        return value == null ? "" : String.valueOf(value);
    }

    private long longValue(Object target, String method) {
        Object value = invoke(target, method, new Class<?>[0]);
        return value instanceof Number number ? number.longValue() : 0L;
    }

    private boolean bool(Object target, String method) {
        Object value = invoke(target, method, new Class<?>[0]);
        return value instanceof Boolean b && b;
    }

    private long remaining(Object view) {
        return Math.max(0L, longValue(view, "required") - longValue(view, "current"));
    }

    private String categoryName(String category) {
        return text("growth.categories." + category, switch (category) {
            case "MINION_DEVELOPMENT" -> "&eRozwój minionów";
            case "MINION_DIVERSITY" -> "&bRóżnorodność minionów";
            case "COLLECTION_BREADTH" -> "&6Rozwój wielu kolekcji";
            case "COLLECTION_COMPLETION" -> "&aUkończenie kolekcji";
            default -> "&fPozostały rozwój";
        });
    }

    private ItemStack growthMainItem(Player player) {
        List<String> lore = new ArrayList<>(lines("main.growth.lore"));
        towns.townIdOf(player.getUniqueId()).flatMap(towns::findTown).ifPresent(town -> {
            lore.add(0, "&7Aktualnie: &a" + town.growthPoints() + " Punktów Miasta");
            lore.add(1, "");
        });
        return item(Material.EXPERIENCE_BOTTLE, text("main.growth.name", "&aPunkty Miasta"), lore);
    }

    private ItemStack cityGuideItem() {
        List<String> lore = new ArrayList<>(lines("main.city.lore"));
        List<String> warning = lines("main.city.location-warning");
        if (warning.isEmpty()) {
            warning = List.of(
                    "",
                    "&cUwaga: &7położonego Serca Miasta",
                    "&7nie da się później przenieść.",
                    "&eMądrze wybierz lokalizację miasta przed postawieniem."
            );
        }
        lore.addAll(warning);
        return item(Material.RED_BED, text("main.city.name", "&cMiasto i rozwój"), lore);
    }

    private ItemStack configuredItem(String path, Material fallback) {
        String name = text(path + ".name", "&fInformacja");
        return item(fallback, name, lines(path + ".lore"));
    }

    private Inventory inventory(Player player, NativeTownMenuHolder.Page page, int size, String title) {
        return Bukkit.createInventory(new NativeTownMenuHolder(page, player.getUniqueId()), size, color(title));
    }

    private void fill(Inventory inv) {
        ItemStack filler = item(Material.BLACK_STAINED_GLASS_PANE, " ", List.of());
        ItemMeta meta = filler.getItemMeta();
        if (meta != null) {
            try { ItemMeta.class.getMethod("setHideTooltip", boolean.class).invoke(meta, true); } catch (Throwable ignored) { }
            filler.setItemMeta(meta);
        }
        for (int i = 0; i < inv.getSize(); i++) inv.setItem(i, filler);
    }

    private ItemStack item(Material material, String name, List<String> lore) {
        ItemStack stack = new ItemStack(material == null ? Material.BOOK : material);
        ItemMeta meta = stack.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(color(name));
            List<String> colored = lore == null ? List.of() : lore.stream().map(this::color).toList();
            if (!colored.isEmpty()) meta.setLore(colored);
            try { meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES); } catch (Throwable ignored) { }
            stack.setItemMeta(meta);
        }
        return stack;
    }

    private String text(String path, String fallback) {
        String value = config.getString(path);
        return value == null ? fallback : value;
    }

    private List<String> lines(String path) {
        List<String> values = config.getStringList(path);
        return values == null ? List.of() : values;
    }

    private String color(String value) {
        return value == null ? "" : value.replace('&', '§');
    }

    private String trim(String value, int max) {
        if (value == null) return "-";
        return value.length() <= max ? value : value.substring(0, Math.max(1, max - 1)) + "…";
    }
}
