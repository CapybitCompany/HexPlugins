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
    private final String purchaseLogsTable;
    private final Map<String, String> messages;
    private final DateTimeFormatter dateFormatter;
    private final int priceDecimals;
    private final Map<String, ConfiguredService> services;

    public VishopSettings(
            int pollIntervalSeconds,
            int fetchLimit,
            boolean skipExistingLogsOnStartup,
            int minChatOnlyIntervalSeconds,
            String purchaseLogsTable,
            Map<String, String> messages,
            String datePattern,
            int priceDecimals,
            Map<String, ConfiguredService> services
    ) {
        this.pollIntervalSeconds = Math.max(1, pollIntervalSeconds);
        this.fetchLimit = Math.max(1, fetchLimit);
        this.skipExistingLogsOnStartup = skipExistingLogsOnStartup;
        this.minChatOnlyIntervalSeconds = Math.max(1, minChatOnlyIntervalSeconds);
        this.purchaseLogsTable = purchaseLogsTable;
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

    public String purchaseLogsTable() {
        return purchaseLogsTable;
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

