package hex.minions.placeholder;

import hex.minions.api.MinionMenuData;
import hex.minions.api.MinionsApi;
import hex.minions.api.TownMinionMenuData;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Locale;
import java.util.Optional;

public final class MinionsPlaceholderExpansion extends PlaceholderExpansion {
    private final Plugin plugin;
    private final MinionsApi api;

    public MinionsPlaceholderExpansion(Plugin plugin, MinionsApi api) {
        this.plugin = plugin;
        this.api = api;
    }

    @Override public @NotNull String getIdentifier() { return "hexminions"; }
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

    private String resolve(Player player, String params) {
        if (player == null) return "";
        String key = normalize(params).toLowerCase(Locale.ROOT);
        TownMinionMenuData town = api.menuData(player);

        return switch (key) {
            case "town_uuid" -> town.townUuid() == null ? "" : town.townUuid().toString();
            case "town_name" -> town.townName();
            case "count" -> String.valueOf(town.minionCount());
            case "limit" -> String.valueOf(town.minionLimit());
            case "remaining" -> String.valueOf(Math.max(0, town.minionLimit() - town.minionCount()));
            case "percent" -> percent(town.minionCount(), town.minionLimit());
            case "has_town" -> String.valueOf(town.townUuid() != null);
            default -> selectedPlaceholder(player, key).orElseGet(() -> indexPlaceholder(player, key));
        };
    }

    private Optional<String> selectedPlaceholder(Player player, String key) {
        if (!key.startsWith("selected_")) return Optional.empty();
        String field = key.substring("selected_".length());
        Optional<MinionMenuData> data = api.selectedMenuData(player);
        if (field.equals("exists")) return Optional.of(String.valueOf(data.isPresent()));
        return Optional.of(data.map(minion -> field(minion, field)).orElse(defaultForMissing(field)));
    }

    private String indexPlaceholder(Player player, String key) {
        if (!key.startsWith("index_")) return "";
        String[] parts = key.split("_");
        if (parts.length < 3) return "";
        int index = parseInt(parts[1], -1);
        if (index <= 0) return "";
        String field = join(parts, 2);
        TownMinionMenuData town = api.menuData(player);
        Optional<MinionMenuData> data = api.menuDataByIndex(player, index);
        if (field.equals("exists")) return String.valueOf(data.isPresent());
        return data.map(minion -> field(minion, field)).orElse(defaultForMissingIndex(field, index, town.minionLimit()));
    }

    private String field(MinionMenuData minion, String field) {
        return switch (field) {
            case "id" -> minion.id().toString();
            case "short_id" -> minion.shortId();
            case "type", "type_id" -> minion.typeId();
            case "name", "display", "display_name" -> minion.displayName();
            case "tier" -> String.valueOf(minion.tier());
            case "max_tier" -> String.valueOf(minion.maxTier());
            case "world" -> minion.world();
            case "x" -> String.valueOf(minion.x());
            case "y" -> String.valueOf(minion.y());
            case "z" -> String.valueOf(minion.z());
            case "location" -> minion.world() + " " + minion.x() + "," + minion.y() + "," + minion.z();
            case "storage_used" -> String.valueOf(minion.storageUsed());
            case "storage_limit" -> String.valueOf(minion.storageLimit());
            case "storage_percent" -> String.valueOf(minion.storagePercent());
            case "storage_bar" -> bar(minion.storagePercent(), 20);
            case "action_time", "action_time_seconds" -> String.valueOf(minion.actionTimeSeconds());
            case "state" -> minion.state();
            case "can_upgrade" -> String.valueOf(minion.canUpgrade());
            case "requirements", "next_upgrade_requirements" -> minion.nextUpgradeRequirementsText();
            case "slot", "menu_slot" -> String.valueOf(minion.menuSlotHint());
            case "storage_slots", "storage_slots_unlocked" -> String.valueOf(minion.storageSlotsUnlocked());
            case "material" -> material(minion);
            case "head_material" -> minion.headMaterial();
            case "status_material" -> statusMaterial(minion);
            default -> "";
        };
    }

    private String material(MinionMenuData minion) {
        String type = minion.typeId().toLowerCase(Locale.ROOT);
        if (type.contains("iron")) return "IRON_INGOT";
        if (type.contains("cobble")) return "COBBLESTONE";
        if (type.contains("dirt")) return "DIRT";
        return "PLAYER_HEAD";
    }

    private String statusMaterial(MinionMenuData minion) {
        if (!minion.state().equalsIgnoreCase("ACTIVE")) return "RED_STAINED_GLASS_PANE";
        if (minion.storagePercent() >= 100) return "LIME_STAINED_GLASS_PANE";
        if (minion.storagePercent() >= 50) return "YELLOW_STAINED_GLASS_PANE";
        return "GRAY_STAINED_GLASS_PANE";
    }


    private static String defaultForMissingIndex(String field, int index, int townLimit) {
        boolean unlocked = townLimit > 0 && index <= townLimit;
        return switch (field) {
            case "exists", "can_upgrade" -> "false";
            case "tier", "max_tier", "x", "y", "z", "storage_used", "storage_limit", "storage_percent", "action_time", "action_time_seconds", "slot", "menu_slot", "storage_slots", "storage_slots_unlocked" -> "0";
            case "material", "head_material", "status_material" -> unlocked ? "WHITE_STAINED_GLASS_PANE" : "BLACK_STAINED_GLASS_PANE";
            case "name", "display", "display_name" -> unlocked ? "Wolny slot miniona" : "Zablokowany slot";
            case "state" -> unlocked ? "WOLNY" : "ZABLOKOWANY";
            case "location" -> unlocked ? "Slot dostępny" : "Poza limitem miasta";
            default -> "";
        };
    }

    private static String defaultForMissing(String field) {
        return switch (field) {
            case "exists", "can_upgrade" -> "false";
            case "tier", "max_tier", "x", "y", "z", "storage_used", "storage_limit", "storage_percent", "action_time", "action_time_seconds", "slot", "menu_slot", "storage_slots", "storage_slots_unlocked" -> "0";
            case "material", "head_material", "status_material" -> "BARRIER";
            default -> "";
        };
    }

    private static String percent(int value, int max) {
        if (max <= 0) return "0";
        return String.valueOf(Math.min(100, Math.round(value * 100.0F / max)));
    }

    private static String bar(int percent, int length) {
        int filled = Math.max(0, Math.min(length, Math.round(percent * length / 100.0F)));
        return "■".repeat(filled) + "□".repeat(Math.max(0, length - filled));
    }

    private static int parseInt(String raw, int def) {
        try { return Integer.parseInt(raw); } catch (NumberFormatException ignored) { return def; }
    }

    private static String join(String[] parts, int from) {
        StringBuilder builder = new StringBuilder();
        for (int i = from; i < parts.length; i++) {
            if (i > from) builder.append('_');
            builder.append(parts[i]);
        }
        return builder.toString();
    }

    private static String normalize(String identifier) {
        if (identifier == null || identifier.isBlank()) return "";
        return identifier.startsWith(":") ? identifier.substring(1) : identifier;
    }
}

