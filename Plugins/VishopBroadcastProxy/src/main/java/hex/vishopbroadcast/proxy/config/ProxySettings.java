package hex.vishopbroadcast.proxy.config;

import java.util.List;
import java.util.Map;
import java.util.Optional;

public record ProxySettings(
        Database database,
        Tables tables,
        Dedupe dedupe,
        Cleanup cleanup,
        int priceDecimals,
        Map<String, String> messages,
        List<ProxyService> services
) {
    public Optional<ProxyService> service(String input) {
        if (input == null) {
            return Optional.empty();
        }
        return services.stream().filter(ProxyService::enabled).filter(service -> service.matches(input)).findFirst();
    }

    public String message(String key) {
        return messages.getOrDefault(key, "<red>Missing message: " + key);
    }

    public record Database(String type, String host, int port, String name, String username, String password, int poolSize) {
    }

    public record Tables(String playerTotals, String purchaseLogs, String purchaseDedupe) {
    }

    public record Dedupe(boolean enabled, int windowSeconds) {
    }

    public record Cleanup(boolean enabled, int hour, int minute, int retentionDays) {
    }
}
