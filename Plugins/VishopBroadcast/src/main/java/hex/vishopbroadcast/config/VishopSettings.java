package hex.vishopbroadcast.config;

import java.time.format.DateTimeFormatter;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

public final class VishopSettings {
    private final int pollIntervalSeconds;
    private final int fetchLimit;
    private final boolean skipExistingLogsOnStartup;
    private final int minChatOnlyIntervalSeconds;
    private final boolean cleanupEnabled;
    private final int cleanupHour;
    private final int cleanupMinute;
    private final int retentionDays;
    private final boolean dedupeEnabled;
    private final int dedupeWindowSeconds;
    private final String playerTotalsTable;
    private final String purchaseLogsTable;
    private final String purchaseDedupeTable;
    private final Map<String, String> messages;
    private final DateTimeFormatter dateFormatter;
    private final int priceDecimals;
    private final Map<String, ConfiguredService> services;

    public VishopSettings(
            int pollIntervalSeconds,
            int fetchLimit,
            boolean skipExistingLogsOnStartup,
            int minChatOnlyIntervalSeconds,
            boolean cleanupEnabled,
            int cleanupHour,
            int cleanupMinute,
            int retentionDays,
            boolean dedupeEnabled,
            int dedupeWindowSeconds,
            String playerTotalsTable,
            String purchaseLogsTable,
            String purchaseDedupeTable,
            Map<String, String> messages,
            String datePattern,
            int priceDecimals,
            Map<String, ConfiguredService> services
    ) {
        this.pollIntervalSeconds = Math.max(1, pollIntervalSeconds);
        this.fetchLimit = Math.max(1, fetchLimit);
        this.skipExistingLogsOnStartup = skipExistingLogsOnStartup;
        this.minChatOnlyIntervalSeconds = Math.max(1, minChatOnlyIntervalSeconds);
        this.cleanupEnabled = cleanupEnabled;
        this.cleanupHour = Math.max(0, Math.min(23, cleanupHour));
        this.cleanupMinute = Math.max(0, Math.min(59, cleanupMinute));
        this.retentionDays = Math.max(1, retentionDays);
        this.dedupeEnabled = dedupeEnabled;
        this.dedupeWindowSeconds = Math.max(1, dedupeWindowSeconds);
        this.playerTotalsTable = playerTotalsTable;
        this.purchaseLogsTable = purchaseLogsTable;
        this.purchaseDedupeTable = purchaseDedupeTable;
        this.messages = Map.copyOf(messages);
        this.dateFormatter = DateTimeFormatter.ofPattern(datePattern == null || datePattern.isBlank() ? "yyyy-MM-dd HH:mm:ss" : datePattern);
        this.priceDecimals = Math.max(0, priceDecimals);
        this.services = new LinkedHashMap<>(services);
    }

    public int pollIntervalSeconds() {
        return pollIntervalSeconds;
    }

    public int fetchLimit() {
        return fetchLimit;
    }

    public boolean skipExistingLogsOnStartup() {
        return skipExistingLogsOnStartup;
    }

    public int minChatOnlyIntervalSeconds() {
        return minChatOnlyIntervalSeconds;
    }

    public boolean cleanupEnabled() {
        return cleanupEnabled;
    }

    public int cleanupHour() {
        return cleanupHour;
    }

    public int cleanupMinute() {
        return cleanupMinute;
    }

    public int retentionDays() {
        return retentionDays;
    }

    public boolean dedupeEnabled() {
        return dedupeEnabled;
    }

    public int dedupeWindowSeconds() {
        return dedupeWindowSeconds;
    }

    public String playerTotalsTable() {
        return playerTotalsTable;
    }

    public String purchaseLogsTable() {
        return purchaseLogsTable;
    }

    public String purchaseDedupeTable() {
        return purchaseDedupeTable;
    }

    public DateTimeFormatter dateFormatter() {
        return dateFormatter;
    }

    public int priceDecimals() {
        return priceDecimals;
    }

    public Collection<ConfiguredService> services() {
        return services.values();
    }

    public Optional<ConfiguredService> service(String input) {
        if (input == null) {
            return Optional.empty();
        }
        ConfiguredService exact = services.get(input.toLowerCase(Locale.ROOT));
        if (exact != null && exact.enabled()) {
            return Optional.of(exact);
        }
        return services.values().stream()
                .filter(ConfiguredService::enabled)
                .filter(service -> service.matches(input))
                .findFirst();
    }

    public Optional<ConfiguredService> serviceByKey(String key) {
        if (key == null) {
            return Optional.empty();
        }
        ConfiguredService service = services.get(key.toLowerCase(Locale.ROOT));
        return service != null && service.enabled() ? Optional.of(service) : Optional.empty();
    }

    public String message(String key) {
        return messages.getOrDefault(key, "<red>Missing message: " + key);
    }
}

