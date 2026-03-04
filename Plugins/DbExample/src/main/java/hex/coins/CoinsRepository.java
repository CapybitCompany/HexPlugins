package hex.coins;

import hex.core.api.db.Db;

import java.util.Optional;
import java.util.UUID;

public final class CoinsRepository {

    private final Db db;

    public CoinsRepository(Db db) {
        this.db = db;
    }

    public void ensureTable() {
        db.update("CREATE TABLE IF NOT EXISTS " + db.t("player_coins") + " (" +
                "uuid VARCHAR(36) PRIMARY KEY," +
                "coins INT NOT NULL DEFAULT 0" +
                ")");
    }

    public Optional<Integer> find(UUID uuid) {
        return db.queryOne(
                "SELECT coins FROM " + db.t("player_coins") + " WHERE uuid=?",
                rs -> rs.getInt("coins"),
                uuid.toString()
        );
    }

    public void set(UUID uuid, int amount) {
        // MySQL/MariaDB upsert
        db.update(
                "INSERT INTO " + db.t("player_coins") + " (uuid, coins) VALUES (?, ?) " +
                        "ON DUPLICATE KEY UPDATE coins=VALUES(coins)",
                uuid.toString(), amount
        );
    }

    public void add(UUID uuid, int delta) {
        db.update(
                "INSERT INTO " + db.t("player_coins") + " (uuid, coins) VALUES (?, ?) " +
                        "ON DUPLICATE KEY UPDATE coins=coins+VALUES(coins)",
                uuid.toString(), delta
        );
    }
}