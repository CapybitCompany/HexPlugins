package hex.rankexpiry.config;

import hex.rankexpiry.model.RankDefinition;
import org.bukkit.ChatColor;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

public final class RankExpirySettings {
    private static final Pattern SAFE_TABLE_NAME = Pattern.compile("[A-Za-z0-9_]+(?:\\.[A-Za-z0-9_]+)?");

    private final String userPermissionsTable;
    private final List<String> userPermissionsTableCandidates;
    private final List<RankDefinition> ranks;
    private final long cacheTtlSeconds;
    private final boolean joinMessageEnabled;
    private final long joinMessageDelayTicks;
    private final List<String> joinMessageLines;
    private final String placeholderLoading;
    private final String placeholderNoRank;
    private final String placeholderExpired;
    private final String placeholderMessage;

    private RankExpirySettings(String userPermissionsTable,
                               List<String> userPermissionsTableCandidates,
                               List<RankDefinition> ranks,
                               long cacheTtlSeconds,
                               boolean joinMessageEnabled,
                               long joinMessageDelayTicks,
                               List<String> joinMessageLines,
                               String placeholderLoading,
                               String placeholderNoRank,
                               String placeholderExpired,
                               String placeholderMessage) {
        this.userPermissionsTable = userPermissionsTable;
        this.userPermissionsTableCandidates = List.copyOf(userPermissionsTableCandidates);
        this.ranks = List.copyOf(ranks);
        this.cacheTtlSeconds = cacheTtlSeconds;
        this.joinMessageEnabled = joinMessageEnabled;
        this.joinMessageDelayTicks = joinMessageDelayTicks;
        this.joinMessageLines = List.copyOf(joinMessageLines);
        this.placeholderLoading = color(placeholderLoading);
        this.placeholderNoRank = color(placeholderNoRank);
        this.placeholderExpired = color(placeholderExpired);
        this.placeholderMessage = color(placeholderMessage);
    }

    public static RankExpirySettings load(FileConfiguration config) {
        String table = config.getString("luckyperms.user-permissions-table", "luckperms_user_permissions");
        if (table == null || !SAFE_TABLE_NAME.matcher(table).matches()) {
            throw new IllegalArgumentException("Invalid LuckyPerms user permissions table name: " + table);
        }
        List<String> tableCandidates = tableCandidates(table);

        List<RankDefinition> ranks = new ArrayList<>();
        for (Map<?, ?> raw : config.getMapList("ranks")) {
            Object permission = raw.get("permission");
            if (permission == null || permission.toString().isBlank()) {
                continue;
            }
            Object display = raw.containsKey("display") ? raw.get("display") : permission.toString();
            ranks.add(new RankDefinition(permission.toString(), color(display.toString())));
        }
        if (ranks.isEmpty()) {
            ranks = List.of(
                    new RankDefinition("nte.elita", color("&dELITA")),
                    new RankDefinition("nte.svip", color("&bSVIP")),
                    new RankDefinition("nte.vip", color("&6VIP"))
            );
        }

        ConfigurationSection placeholders = config.getConfigurationSection("placeholders");
        return new RankExpirySettings(
                table,
                tableCandidates,
                ranks,
                Math.max(5L, config.getLong("cache.ttl-seconds", 60L)),
                config.getBoolean("join-message.enabled", true),
                Math.max(0L, config.getLong("join-message.delay-ticks", 40L)),
                config.getStringList("join-message.lines"),
                placeholders == null ? "..." : placeholders.getString("loading", "..."),
                placeholders == null ? "-" : placeholders.getString("no-rank", "-"),
                placeholders == null ? "0" : placeholders.getString("expired", "0"),
                placeholders == null ? "&7Ranga: {rank} &8| &7Wygasa za: &a{days} {day_word}" : placeholders.getString("message", "&7Ranga: {rank} &8| &7Wygasa za: &a{days} {day_word}")
        );
    }

    public String userPermissionsTable() {
        return userPermissionsTable;
    }

    public List<String> userPermissionsTableCandidates() {
        return userPermissionsTableCandidates;
    }

    public List<RankDefinition> ranks() {
        return ranks;
    }

    public long cacheTtlSeconds() {
        return cacheTtlSeconds;
    }

    public boolean joinMessageEnabled() {
        return joinMessageEnabled;
    }

    public long joinMessageDelayTicks() {
        return joinMessageDelayTicks;
    }

    public List<String> joinMessageLines() {
        return joinMessageLines;
    }

    public String placeholderLoading() {
        return placeholderLoading;
    }

    public String placeholderNoRank() {
        return placeholderNoRank;
    }

    public String placeholderExpired() {
        return placeholderExpired;
    }

    public String placeholderMessage() {
        return placeholderMessage;
    }

    public static String color(String text) {
        return ChatColor.translateAlternateColorCodes('&', text == null ? "" : text);
    }

    private static List<String> tableCandidates(String configuredTable) {
        List<String> candidates = new ArrayList<>();
        addCandidate(candidates, configuredTable);
        addCandidate(candidates, "luckperms_user_permissions");
        addCandidate(candidates, "luckyperms_user_permissions");
        return candidates;
    }

    private static void addCandidate(List<String> candidates, String table) {
        if (table == null || !SAFE_TABLE_NAME.matcher(table).matches()) {
            return;
        }
        if (candidates.stream().noneMatch(existing -> existing.equalsIgnoreCase(table))) {
            candidates.add(table);
        }
    }
}

