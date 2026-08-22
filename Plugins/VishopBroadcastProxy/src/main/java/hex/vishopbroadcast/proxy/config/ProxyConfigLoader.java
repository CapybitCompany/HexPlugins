package hex.vishopbroadcast.proxy.config;

import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class ProxyConfigLoader {
    private ProxyConfigLoader() {
    }

    public static ProxySettings load(Path dataDirectory) throws IOException {
        Files.createDirectories(dataDirectory);
        Path configPath = dataDirectory.resolve("config.yml");
        if (Files.notExists(configPath)) {
            try (InputStream resource = ProxyConfigLoader.class.getClassLoader().getResourceAsStream("config.yml")) {
                if (resource == null) {
                    throw new IOException("Missing bundled config.yml");
                }
                Files.copy(resource, configPath, StandardCopyOption.REPLACE_EXISTING);
            }
        }

        Map<String, Object> root;
        try (InputStream input = Files.newInputStream(configPath)) {
            Object loaded = new Yaml(new SafeConstructor(new LoaderOptions())).load(input);
            root = map(loaded);
        }

        Map<String, Object> database = section(root, "database");
        Map<String, Object> tables = section(root, "tables");
        Map<String, Object> dedupe = section(root, "dedupe");
        Map<String, Object> cleanup = section(root, "cleanup");
        Map<String, Object> format = section(root, "format");
        Map<String, Object> rawMessages = section(root, "messages");

        Map<String, String> messages = new LinkedHashMap<>();
        rawMessages.forEach((key, value) -> messages.put(key, string(value, "")));

        String defaultAmountPart = string(format.get("amount-part"), " x{amount}");
        String defaultPricePart = string(format.get("price-part"), " za {price} zł");
        String defaultLogInfo = string(format.get("log-info"), "{player} kupił {service}{amount_part}{price_part}");
        List<ProxyService> services = new ArrayList<>();
        for (Map.Entry<String, Object> entry : section(root, "services").entrySet()) {
            Map<String, Object> service = map(entry.getValue());
            services.add(new ProxyService(
                    entry.getKey(),
                    bool(service.get("enabled"), true),
                    strings(service.get("aliases")),
                    string(service.get("display-name"), entry.getKey()),
                    bool(service.get("amount-required"), false),
                    bool(service.get("price-required"), false),
                    bool(service.get("price-from-amount-when-price-missing"), false),
                    string(service.get("amount-part"), defaultAmountPart),
                    string(service.get("price-part"), defaultPricePart),
                    string(service.get("log-info"), defaultLogInfo)
            ));
        }

        return new ProxySettings(
                new ProxySettings.Database(
                        string(database.get("type"), "mysql"),
                        string(database.get("host"), "127.0.0.1"),
                        integer(database.get("port"), 3306),
                        string(database.get("name"), "minecraft"),
                        string(database.get("username"), "root"),
                        string(database.get("password"), ""),
                        Math.max(1, integer(database.get("pool-size"), 3))
                ),
                new ProxySettings.Tables(
                        identifier(tables.get("player-totals"), "vishop_player_totals"),
                        identifier(tables.get("purchase-logs"), "vishop_purchase_logs"),
                        identifier(tables.get("purchase-dedupe"), "vishop_purchase_dedupe")
                ),
                new ProxySettings.Dedupe(bool(dedupe.get("enabled"), true), Math.max(1, integer(dedupe.get("window-seconds"), 10))),
                new ProxySettings.Cleanup(
                        bool(cleanup.get("enabled"), true),
                        clamp(integer(cleanup.get("hour"), 3), 0, 23),
                        clamp(integer(cleanup.get("minute"), 0), 0, 59),
                        Math.max(1, integer(cleanup.get("retention-days"), 30))
                ),
                Math.max(0, integer(format.get("price-decimals"), 2)),
                Map.copyOf(messages),
                List.copyOf(services)
        );
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object value) {
        if (!(value instanceof Map<?, ?> raw)) {
            return Map.of();
        }
        Map<String, Object> result = new LinkedHashMap<>();
        raw.forEach((key, item) -> result.put(String.valueOf(key), item));
        return result;
    }

    private static Map<String, Object> section(Map<String, Object> root, String key) {
        return map(root.get(key));
    }

    private static List<String> strings(Object value) {
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        return list.stream().map(String::valueOf).toList();
    }

    private static String identifier(Object value, String fallback) {
        String candidate = string(value, fallback).trim();
        if (!candidate.matches("[A-Za-z0-9_]+")) {
            throw new IllegalArgumentException("Invalid SQL table name: " + candidate);
        }
        return candidate;
    }

    private static String string(Object value, String fallback) {
        return value == null ? fallback : String.valueOf(value);
    }

    private static int integer(Object value, int fallback) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        try {
            return value == null ? fallback : Integer.parseInt(value.toString());
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private static boolean bool(Object value, boolean fallback) {
        return value == null ? fallback : Boolean.parseBoolean(value.toString());
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
