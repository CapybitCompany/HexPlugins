package hexabovename.repository;

import hexabovename.config.HexAboveNameConfig;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

public final class MySqlDisplayTextRepository implements DisplayTextRepository {

    private final HexAboveNameConfig.MySql config;
    private final String tableQuoted;
    private final String playerColumnQuoted;
    private final String uuidColumnQuoted;
    private final String textColumnQuoted;
    private final String playerColumn;
    private final String uuidColumn;
    private final String textColumn;
    private final String jdbcUrl;

    public MySqlDisplayTextRepository(HexAboveNameConfig.MySql config) {
        this.config = config;
        this.tableQuoted = quoteIdentifier(config.table());
        this.playerColumnQuoted = quoteIdentifier(config.columns().player());
        this.uuidColumnQuoted = quoteIdentifier(config.columns().uuid());
        this.textColumnQuoted = quoteIdentifier(config.columns().text());
        this.playerColumn = config.columns().player();
        this.uuidColumn = config.columns().uuid();
        this.textColumn = config.columns().text();
        this.jdbcUrl = buildJdbcUrl(config);
    }

    @Override
    public void initialize() throws Exception {
        try (Connection connection = openConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT " + uuidColumnQuoted + " FROM " + tableQuoted + " LIMIT 1"
             )) {
            statement.executeQuery();
        }
    }

    @Override
    public Map<UUID, String> loadDisplayTexts(Collection<PlayerSnapshot> players) throws Exception {
        if (players.isEmpty()) {
            return Map.of();
        }

        List<String> uuids = new ArrayList<>(players.size());
        List<String> names = new ArrayList<>(players.size());
        Map<String, UUID> uuidByLowerName = new HashMap<>(players.size());
        Set<UUID> onlineUuids = players.stream().map(PlayerSnapshot::uuid).collect(Collectors.toSet());

        for (PlayerSnapshot player : players) {
            uuids.add(player.uuid().toString());
            names.add(player.name());
            uuidByLowerName.put(player.name().toLowerCase(Locale.ROOT), player.uuid());
        }

        String sql = "SELECT " + playerColumnQuoted + ", " + uuidColumnQuoted + ", " + textColumnQuoted
                + " FROM " + tableQuoted
                + " WHERE " + uuidColumnQuoted + " IN (" + placeholders(uuids.size()) + ")"
                + " OR " + playerColumnQuoted + " IN (" + placeholders(names.size()) + ")";

        Map<UUID, String> result = new HashMap<>();
        try (Connection connection = openConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            int parameter = 1;
            for (String uuid : uuids) {
                statement.setString(parameter++, uuid);
            }
            for (String player : names) {
                statement.setString(parameter++, player);
            }

            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    String text = rs.getString(textColumn);
                    if (text == null || text.isBlank()) {
                        continue;
                    }

                    UUID resolvedUuid = resolveUuid(rs, onlineUuids, uuidByLowerName);
                    if (resolvedUuid == null) {
                        continue;
                    }
                    result.put(resolvedUuid, text);
                }
            }
        }
        return result;
    }

    @Override
    public void upsertDisplayText(UUID uuid, String playerName, String text) throws Exception {
        String sql = "INSERT INTO " + tableQuoted + " (" + uuidColumnQuoted + ", " + playerColumnQuoted + ", " + textColumnQuoted + ") "
                + "VALUES (?, ?, ?) "
                + "ON DUPLICATE KEY UPDATE "
                + playerColumnQuoted + " = VALUES(" + playerColumnQuoted + "), "
                + textColumnQuoted + " = VALUES(" + textColumnQuoted + ")";

        try (Connection connection = openConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, uuid.toString());
            statement.setString(2, playerName);
            statement.setString(3, text);
            statement.executeUpdate();
        }
    }

    @Override
    public void clearDisplayText(UUID uuid, String playerName) throws Exception {
        String sql = "DELETE FROM " + tableQuoted + " WHERE " + uuidColumnQuoted + " = ? OR " + playerColumnQuoted + " = ?";
        try (Connection connection = openConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, uuid.toString());
            statement.setString(2, playerName);
            statement.executeUpdate();
        }
    }

    private UUID resolveUuid(ResultSet rs, Set<UUID> onlineUuids, Map<String, UUID> uuidByLowerName) throws SQLException {
        String uuidRaw = rs.getString(uuidColumn);
        if (uuidRaw != null && !uuidRaw.isBlank()) {
            try {
                UUID uuid = UUID.fromString(uuidRaw);
                if (onlineUuids.contains(uuid)) {
                    return uuid;
                }
            } catch (IllegalArgumentException ignored) {
            }
        }

        String player = rs.getString(playerColumn);
        if (player == null || player.isBlank()) {
            return null;
        }
        return uuidByLowerName.get(player.toLowerCase(Locale.ROOT));
    }

    private Connection openConnection() throws SQLException {
        return DriverManager.getConnection(jdbcUrl, config.username(), config.password());
    }

    private static String buildJdbcUrl(HexAboveNameConfig.MySql config) {
        return "jdbc:mariadb://" + config.host() + ":" + config.port() + "/" + config.database()
                + "?useSsl=" + config.options().useSsl()
                + "&serverTimezone=" + config.options().serverTimezone()
                + "&useUnicode=true"
                + "&characterEncoding=utf8";
    }

    private static String placeholders(int count) {
        StringBuilder builder = new StringBuilder(Math.max(2, count * 2));
        for (int i = 0; i < count; i++) {
            if (i > 0) {
                builder.append(", ");
            }
            builder.append('?');
        }
        return builder.toString();
    }

    private static String quoteIdentifier(String identifier) {
        if (!identifier.matches("[A-Za-z0-9_]+")) {
            throw new IllegalArgumentException("Niepoprawny identyfikator SQL: " + identifier);
        }
        return '`' + identifier + '`';
    }
}
