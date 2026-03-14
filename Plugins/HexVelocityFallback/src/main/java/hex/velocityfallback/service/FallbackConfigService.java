package hex.velocityfallback.service;

import hex.velocityfallback.model.FallbackConfig;
import org.slf4j.Logger;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Properties;

public final class FallbackConfigService {

    private static final String FILE_NAME = "config.properties";
    private static final String KEY_SERVER_NAME = "fallback.server-name";
    private static final String KEY_HOST = "fallback.host";
    private static final String KEY_PORT = "fallback.port";
    private static final String KEY_REDIRECT_ON_CONNECT_FAILURE = "fallback.redirect-on-connect-failure";
    private static final String KEY_REDIRECT_ON_EMPTY_REASON = "fallback.redirect-on-empty-reason";
    private static final String KEY_REASON_KEYWORDS = "fallback.reason-keywords";

    private static final String DEFAULT_SERVER_NAME = "lobby";
    private static final String DEFAULT_HOST = "127.0.0.1";
    private static final int DEFAULT_PORT = 25565;
    private static final boolean DEFAULT_REDIRECT_ON_CONNECT_FAILURE = true;
    private static final boolean DEFAULT_REDIRECT_ON_EMPTY_REASON = true;
    private static final String DEFAULT_REASON_KEYWORDS = "restart,restarting,reboot,shutdown,shutting down,closed,stopped,offline,crash";

    private final Path dataDirectory;
    private final Logger logger;

    public FallbackConfigService(Path dataDirectory, Logger logger) {
        this.dataDirectory = dataDirectory;
        this.logger = logger;
    }

    public FallbackConfig load() throws IOException {
        Files.createDirectories(dataDirectory);
        Path configFile = dataDirectory.resolve(FILE_NAME);

        if (Files.notExists(configFile)) {
            writeDefaults(configFile);
        }

        Properties properties = new Properties();
        try (Reader reader = Files.newBufferedReader(configFile, StandardCharsets.UTF_8)) {
            properties.load(reader);
        }

        String serverName = normalize(properties.getProperty(KEY_SERVER_NAME), DEFAULT_SERVER_NAME);
        String host = normalize(properties.getProperty(KEY_HOST), DEFAULT_HOST);
        int port = parsePort(properties.getProperty(KEY_PORT), DEFAULT_PORT);
        boolean redirectOnConnectFailure = parseBoolean(
                properties.getProperty(KEY_REDIRECT_ON_CONNECT_FAILURE),
                DEFAULT_REDIRECT_ON_CONNECT_FAILURE
        );
        boolean redirectOnEmptyReason = parseBoolean(
                properties.getProperty(KEY_REDIRECT_ON_EMPTY_REASON),
                DEFAULT_REDIRECT_ON_EMPTY_REASON
        );
        List<String> reasonKeywords = parseKeywords(properties.getProperty(KEY_REASON_KEYWORDS), DEFAULT_REASON_KEYWORDS);

        return new FallbackConfig(
                serverName,
                host,
                port,
                redirectOnConnectFailure,
                redirectOnEmptyReason,
                reasonKeywords
        );
    }

    private void writeDefaults(Path file) throws IOException {
        Properties defaults = new Properties();
        defaults.setProperty(KEY_SERVER_NAME, DEFAULT_SERVER_NAME);
        defaults.setProperty(KEY_HOST, DEFAULT_HOST);
        defaults.setProperty(KEY_PORT, Integer.toString(DEFAULT_PORT));
        defaults.setProperty(KEY_REDIRECT_ON_CONNECT_FAILURE, Boolean.toString(DEFAULT_REDIRECT_ON_CONNECT_FAILURE));
        defaults.setProperty(KEY_REDIRECT_ON_EMPTY_REASON, Boolean.toString(DEFAULT_REDIRECT_ON_EMPTY_REASON));
        defaults.setProperty(KEY_REASON_KEYWORDS, DEFAULT_REASON_KEYWORDS);

        try (Writer writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
            defaults.store(writer, "HexVelocityFallback configuration");
        }
    }

    private String normalize(String raw, String fallback) {
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        return raw.trim();
    }

    private int parsePort(String raw, int fallback) {
        if (raw == null || raw.isBlank()) {
            return fallback;
        }

        try {
            int parsed = Integer.parseInt(raw.trim());
            if (parsed < 1 || parsed > 65_535) {
                logger.warn("Invalid fallback port '{}', using default {}.", raw, fallback);
                return fallback;
            }
            return parsed;
        } catch (NumberFormatException ex) {
            logger.warn("Cannot parse fallback port '{}', using default {}.", raw, fallback);
            return fallback;
        }
    }

    private boolean parseBoolean(String raw, boolean fallback) {
        if (raw == null || raw.isBlank()) {
            return fallback;
        }

        String normalized = raw.trim().toLowerCase(Locale.ROOT);
        if ("true".equals(normalized)) {
            return true;
        }
        if ("false".equals(normalized)) {
            return false;
        }

        logger.warn("Cannot parse boolean '{}', using default {}.", raw, fallback);
        return fallback;
    }

    private List<String> parseKeywords(String raw, String fallback) {
        String source = normalize(raw, fallback);
        String[] split = source.split(",");
        List<String> keywords = new ArrayList<>(split.length);

        for (String value : split) {
            String normalized = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
            if (!normalized.isEmpty()) {
                keywords.add(normalized);
            }
        }

        return List.copyOf(keywords);
    }
}
