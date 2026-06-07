package hex.collections.placeholder;

import hex.collections.config.CollectionRegistry;
import hex.collections.model.CollectionDefinition;
import hex.collections.service.CollectionProgressService;
import hex.towns.api.TownsApi;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.text.DecimalFormat;
import java.util.Locale;
import java.util.UUID;

public final class CollectionPlaceholderExpansion extends PlaceholderExpansion {
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
        if (player == null) return "";
        UUID townId = towns.townIdOf(player.getUniqueId()).orElse(null);
        if (townId == null) return "0";
        String normalized = normalizeIdentifier(params);
        String[] parts = normalized.split("_");
        if (parts.length < 2) return "";
        String op = parts[0].toLowerCase(Locale.ROOT);
        if (op.equals("progress") && parts.length >= 3 && parts[1].equals("percent")) return percentFormat.format(service.getProgressPercent(townId, join(parts, 2, parts.length)));
        if (op.equals("progress") && parts.length >= 3 && parts[1].equals("bar")) return bar(townId, join(parts, 2, parts.length));
        if (op.equals("gui") && parts.length >= 4) return gui(townId, parts);
        if (op.equals("amount")) return String.valueOf(service.getAmount(townId, join(parts, 1, parts.length)));
        if (op.equals("level")) return String.valueOf(service.getLevel(townId, join(parts, 1, parts.length)));
        if (op.equals("next") && parts.length >= 3 && parts[1].equals("level")) return nextLevel(townId, join(parts, 2, parts.length));
        if (op.equals("remaining")) return remaining(townId, join(parts, 1, parts.length));
        if (op.equals("required") && parts.length >= 3) return required(join(parts, 1, parts.length - 1), parts[parts.length - 1]);
        if (op.equals("unlocked") && parts.length >= 3) return String.valueOf(service.hasUnlocked(townId, join(parts, 1, parts.length - 1), intValue(parts[parts.length - 1])));
        if (op.equals("reward") && parts.length >= 4 && parts[1].equals("claimed")) return String.valueOf(service.hasUnlocked(townId, join(parts, 2, parts.length - 1), intValue(parts[parts.length - 1])));
        if (op.equals("rank")) return "-";
        return "";
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
            case "display" -> def.displayName() + " " + level;
            case "lore" -> "Postęp: " + service.getAmount(townId, def.id()) + "/" + def.requiredFor(level);
            default -> "";
        };
    }

    private String remaining(UUID townId, String id) { CollectionDefinition def = definition(id); if (def == null) return "0"; long req = def.nextRequired(service.getLevel(townId, def.id())); return String.valueOf(Math.max(0L, req - service.getAmount(townId, def.id()))); }
    private String nextLevel(UUID townId, String id) { CollectionDefinition def = definition(id); if (def == null) return "0"; int current = service.getLevel(townId, def.id()); int max = def.levels().isEmpty() ? 0 : def.levels().get(def.levels().size() - 1).level(); return String.valueOf(Math.min(max, current + 1)); }
    private String required(String id, String level) { CollectionDefinition def = definition(id); return def == null ? "0" : String.valueOf(def.requiredFor(intValue(level))); }
    private String bar(UUID townId, String id) { CollectionDefinition def = definition(id); int len = def == null ? 20 : def.progressBarLength(); int filled = (int)Math.round(len * service.getProgressPercent(townId, id) / 100D); String full = def == null ? "■" : def.progressBarFilledChar(); String empty = def == null ? "□" : def.progressBarEmptyChar(); return full.repeat(Math.max(0, filled)) + empty.repeat(Math.max(0, len - filled)); }
    private static int intValue(String value) { try { return Integer.parseInt(value); } catch (Exception e) { return 0; } }
    private static String join(String[] parts, int from, int to) { StringBuilder b = new StringBuilder(); for (int i=from;i<to;i++) { if (i>from) b.append('_'); b.append(parts[i]); } return b.toString(); }
    private String normalizeIdentifier(String identifier) { if (identifier == null || identifier.isBlank()) return ""; return identifier.startsWith(":") ? identifier.substring(1) : identifier; }
    private CollectionDefinition definition(String id) { return registry.find(id).orElse(null); }
}


