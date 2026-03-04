package hex.ranking.repository;

import hex.core.api.db.Db;

import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

public final class MySqlPlayerIdentityRepository implements PlayerIdentityRepository {

    private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z0-9_]+");

    private final Db db;
    private final String identityTable;
    private final String nameColumn;
    private final String uuidColumn;

    public MySqlPlayerIdentityRepository(Db db, String identityTable, String nameColumn, String uuidColumn) {
        this.db = db;
        this.identityTable = requireIdentifier(identityTable, "identity table");
        this.nameColumn = requireIdentifier(nameColumn, "identity name column");
        this.uuidColumn = requireIdentifier(uuidColumn, "identity uuid column");
    }

    private static String requireIdentifier(String value, String label) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Missing " + label + " name.");
        }
        if (!IDENTIFIER.matcher(value).matches()) {
            throw new IllegalArgumentException("Invalid " + label + " name: " + value);
        }
        return value;
    }

    private String table() {
        return "`" + identityTable + "`";
    }

    private String nameCol() {
        return "`" + nameColumn + "`";
    }

    private String uuidCol() {
        return "`" + uuidColumn + "`";
    }

    @Override
    public Optional<UUID> findUuidByName(String playerName) {
        return db.queryOne(
                "SELECT " + uuidCol() + " FROM " + table() + " WHERE LOWER(" + nameCol() + ")=LOWER(?) LIMIT 1",
                rs -> UUID.fromString(rs.getString(uuidColumn)),
                playerName
        );
    }
}
