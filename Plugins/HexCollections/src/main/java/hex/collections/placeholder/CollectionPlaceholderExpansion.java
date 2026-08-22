package hex.collections.placeholder;

import hex.collections.config.CollectionRegistry;
import hex.collections.model.CollectionDefinition;
import hex.collections.api.TopCollectionEntry;
import hex.collections.service.CollectionProgressService;
import hex.towns.api.TownsApi;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.text.DecimalFormat;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.UUID;

public final class CollectionPlaceholderExpansion extends PlaceholderExpansion {
    private static final Pattern HEX_TAG = Pattern.compile("<#([A-Fa-f0-9]{6})>");
    private static final Pattern COLOR_HEX_TAG = Pattern.compile("<color:#([A-Fa-f0-9]{6})>");
    private static final Pattern CLOSING_TAG = Pattern.compile("</[^>]+>");
    private static final Pattern UNKNOWN_TAG = Pattern.compile("<[^>]+>");
    private final Plugin plugin;
    private final CollectionProgressService service;
    private final CollectionRegistry registry;
    private final TownsApi towns;
    private final DecimalFormat percentFormat = new DecimalFormat("0.00");

    public CollectionPlaceholderExpansion(Plugin plugin, CollectionProgressService service, CollectionRegistry registry, TownsApi towns) {
        this.plugin = plugin;
        this.service = service;
        this.registry = registry;
        this.towns = towns;
    }

    @Override public @NotNull String getIdentifier() { return "hexcollections"; }
    @Override public @NotNull String getAuthor() { return String.join(", ", plugin.getDescription().getAuthors()); }
    @Override public @NotNull String getVersion() { return plugin.getDescription().getVersion(); }
    @Override public boolean persist() { return true; }

    @Override
    public @Nullable String onPlaceholderRequest(Player player, @NotNull String params) {
        return resolve(player, params);
    }

    @Override
    public @Nullable String onRequest(OfflinePlayer player, @NotNull String params) {
        return resolve(player instanceof Player online ? online : null, params);
    }

    private @Nullable String resolve(Player player, @NotNull String params) {
        String normalized = normalizeIdentifier(params);
        String[] parts = normalized.split("_");
        if (parts.length < 2) return "";
        String op = parts[0].toLowerCase(Locale.ROOT);
        if (op.equals("top") && parts.length >= 4) return top(parts);
        if (player == null) return "";
        UUID townId = towns.townIdOf(player.getUniqueId()).orElse(null);
        if (townId == null) return "0";
        if (op.equals("progress") && parts.length >= 3 && parts[1].equals("percent")) return percentFormat.format(service.getProgressPercent(townId, join(parts, 2, parts.length)));
        if (op.equals("progress") && parts.length >= 3 && parts[1].equals("bar")) return bar(townId, join(parts, 2, parts.length));
        if (op.equals("gui") && parts.length >= 4) return gui(townId, parts);
        if (op.equals("amount")) return String.valueOf(service.getAmount(townId, join(parts, 1, parts.length)));
        if (op.equals("level")) return String.valueOf(service.getLevel(townId, join(parts, 1, parts.length)));
        if (op.equals("next") && parts.length >= 3 && parts[1].equals("level")) return nextLevel(townId, join(parts, 2, parts.length));
        if (op.equals("remaining")) return remaining(townId, join(parts, 1, parts.length));
        if (op.equals("required") && parts.length >= 3) return required(townId, join(parts, 1, parts.length - 1), parts[parts.length - 1]);
        if (op.equals("unlocked") && parts.length >= 3) return String.valueOf(service.hasUnlocked(townId, join(parts, 1, parts.length - 1), intValue(parts[parts.length - 1])));
        if (op.equals("reward") && parts.length >= 4 && parts[1].equals("claimed")) return String.valueOf(service.hasUnlocked(townId, join(parts, 2, parts.length - 1), intValue(parts[parts.length - 1])));
        if (op.equals("rank")) return "-";
        return "";
    }


    private String top(String[] parts) {
        String field = parts[1].toLowerCase(Locale.ROOT);
        int rank = intValue(parts[parts.length - 1]);
        if (rank <= 0) {
            return "-";
        }
        String id = join(parts, 2, parts.length - 1);
        CollectionDefinition def = definition(id);
        if (def == null) {
            return "-";
        }
        List<TopCollectionEntry> top = service.top(def.id(), Math.max(5, rank));
        if (top.size() < rank) {
            return switch (field) {
                case "line" -> "&8" + rank + ". Brak danych";
                case "amount" -> "0";
                case "level" -> "0";
                case "town", "name" -> "-";
                default -> "-";
            };
        }
        TopCollectionEntry entry = top.get(rank - 1);
        String townName = towns.findTown(entry.townId()).map(hex.towns.model.Town::name).orElse("Town " + rank);
        return switch (field) {
            case "line" -> "&e" + rank + ". &f" + townName + " &7- &a" + entry.amount();
            case "amount" -> String.valueOf(entry.amount());
            case "level" -> String.valueOf(entry.level());
            case "uuid" -> entry.townId().toString();
            case "town", "name" -> townName;
            default -> "-";
        };
    }

    private String gui(UUID townId, String[] parts) {
        String type = parts[1]; int level = intValue(parts[parts.length - 1]); String id = join(parts, 2, parts.length - 1);
        CollectionDefinition def = definition(id); if (def == null) return "";
        boolean unlocked = service.hasUnlocked(townId, def.id(), level);
        String state = unlocked ? "UNLOCKED" : (service.getLevel(townId, def.id()) + 1 == level ? "IN_PROGRESS" : "LOCKED");
        return switch (type.toLowerCase(Locale.ROOT)) {
            case "state" -> state;
            case "material" -> switch (state) {
                case "UNLOCKED" -> "LIME_STAINED_GLASS_PANE";
                case "IN_PROGRESS" -> "YELLOW_STAINED_GLASS_PANE";
                default -> "RED_STAINED_GLASS_PANE";
            };
            case "display" -> legacyColors(def.displayName()) + " " + level;
            case "lore" -> "Postęp: " + service.getAmount(townId, def.id()) + "/" + service.getRequirementForLevel(townId, def.id(), level);
            default -> "";
        };
    }

    private String remaining(UUID townId, String id) { CollectionDefinition def = definition(id); if (def == null) return "0"; long req = service.getNextLevelRequirement(townId, def.id()); return String.valueOf(Math.max(0L, req - service.getAmount(townId, def.id()))); }
    private String nextLevel(UUID townId, String id) { CollectionDefinition def = definition(id); if (def == null) return "0"; int current = service.getLevel(townId, def.id()); int max = def.levels().isEmpty() ? 0 : def.levels().get(def.levels().size() - 1).level(); return String.valueOf(Math.min(max, current + 1)); }
    private String required(UUID townId, String id, String level) { CollectionDefinition def = definition(id); return def == null ? "0" : String.valueOf(service.getRequirementForLevel(townId, def.id(), intValue(level))); }
    private String bar(UUID townId, String id) { CollectionDefinition def = definition(id); int len = def == null ? 20 : def.progressBarLength(); int filled = (int)Math.round(len * service.getProgressPercent(townId, id) / 100D); String full = def == null ? "■" : def.progressBarFilledChar(); String empty = def == null ? "□" : def.progressBarEmptyChar(); return full.repeat(Math.max(0, filled)) + empty.repeat(Math.max(0, len - filled)); }
    private static int intValue(String value) { try { return Integer.parseInt(value); } catch (Exception e) { return 0; } }
    private static String join(String[] parts, int from, int to) { StringBuilder b = new StringBuilder(); for (int i=from;i<to;i++) { if (i>from) b.append('_'); b.append(parts[i]); } return b.toString(); }

    private static String legacyColors(String input) {
        if (input == null || input.isBlank()) return "";
        String value = input;
        value = value.replace("<black>", "§0").replace("<dark_blue>", "§1").replace("<dark_green>", "§2")
                .replace("<dark_aqua>", "§3").replace("<dark_red>", "§4").replace("<dark_purple>", "§5")
                .replace("<gold>", "§6").replace("<gray>", "§7").replace("<grey>", "§7")
                .replace("<dark_gray>", "§8").replace("<dark_grey>", "§8").replace("<blue>", "§9")
                .replace("<green>", "§a").replace("<aqua>", "§b").replace("<red>", "§c")
                .replace("<light_purple>", "§d").replace("<yellow>", "§e").replace("<white>", "§f")
                .replace("<bold>", "§l").replace("<b>", "§l").replace("<italic>", "§o").replace("<i>", "§o")
                .replace("<underlined>", "§n").replace("<underline>", "§n").replace("<u>", "§n")
                .replace("<strikethrough>", "§m").replace("<st>", "§m").replace("<obfuscated>", "§k")
                .replace("<reset>", "§r");
        Matcher colorHex = COLOR_HEX_TAG.matcher(value);
        StringBuffer colorBuffer = new StringBuffer();
        while (colorHex.find()) colorHex.appendReplacement(colorBuffer, Matcher.quoteReplacement(legacyHex(colorHex.group(1))));
        colorHex.appendTail(colorBuffer);
        value = colorBuffer.toString();
        Matcher hex = HEX_TAG.matcher(value);
        StringBuffer hexBuffer = new StringBuffer();
        while (hex.find()) hex.appendReplacement(hexBuffer, Matcher.quoteReplacement(legacyHex(hex.group(1))));
        hex.appendTail(hexBuffer);
        value = CLOSING_TAG.matcher(value).replaceAll("");
        value = UNKNOWN_TAG.matcher(value).replaceAll("");
        return value;
    }

    private static String legacyHex(String hex) {
        if (hex == null || hex.length() != 6) return "";
        StringBuilder builder = new StringBuilder("§x");
        for (char c : hex.toCharArray()) builder.append('§').append(c);
        return builder.toString();
    }

    private String normalizeIdentifier(String identifier) { if (identifier == null || identifier.isBlank()) return ""; return identifier.startsWith(":") ? identifier.substring(1) : identifier; }
    private CollectionDefinition definition(String id) { return registry.find(id).orElse(null); }
}


