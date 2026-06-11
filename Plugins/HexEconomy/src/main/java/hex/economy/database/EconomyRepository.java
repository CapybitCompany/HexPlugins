package hex.economy.database;

import hex.core.api.db.Db;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

public final class EconomyRepository {
    private final Db db;

    public EconomyRepository(Db db) {
        this.db = db;
    }

    public void ensureTables() {
        db.update("CREATE TABLE IF NOT EXISTS " + db.t("smp_economy") + " (" +
                "player_uuid CHAR(36) NOT NULL," +
                "player_name VARCHAR(32) NULL," +
                "balance DECIMAL(19, 2) NOT NULL DEFAULT 0.00," +
                "created_at BIGINT NOT NULL," +
                "updated_at BIGINT NOT NULL," +
                "PRIMARY KEY (player_uuid)," +
                "KEY idx_player_name (player_name)" +
                ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin");
    }

    public Optional<BigDecimal> getBalance(UUID playerUuid) {
        return db.queryOne("SELECT balance FROM " + db.t("smp_economy") + " WHERE player_uuid=?",
                rs -> rs.getBigDecimal("balance"), playerUuid.toString());
    }

    public BigDecimal getOrCreateBalance(UUID playerUuid, String playerName, BigDecimal defaultBalance) {
        return db.tx(tx -> {
            Optional<BigDecimal> existing = tx.queryOne("SELECT balance FROM " + tx.t("smp_economy") + " WHERE player_uuid=?",
                    rs -> rs.getBigDecimal("balance"), playerUuid.toString());
            if (existing.isPresent()) {
                touchName(tx, playerUuid, playerName);
                return existing.get();
            }
            long now = System.currentTimeMillis();
            tx.update("INSERT INTO " + tx.t("smp_economy") +
                            " (player_uuid, player_name, balance, created_at, updated_at) VALUES (?, ?, ?, ?, ?)",
                    playerUuid.toString(), safeName(playerName), defaultBalance, now, now);
            return defaultBalance;
        });
    }

    public BigDecimal add(UUID playerUuid, String playerName, BigDecimal delta, BigDecimal defaultBalance) {
        return db.tx(tx -> {
            BigDecimal current = tx.queryOne("SELECT balance FROM " + tx.t("smp_economy") + " WHERE player_uuid=? FOR UPDATE",
                    rs -> rs.getBigDecimal("balance"), playerUuid.toString()).orElse(null);
            if (current == null) {
                current = defaultBalance;
                long now = System.currentTimeMillis();
                tx.update("INSERT INTO " + tx.t("smp_economy") +
                                " (player_uuid, player_name, balance, created_at, updated_at) VALUES (?, ?, ?, ?, ?)",
                        playerUuid.toString(), safeName(playerName), current, now, now);
            }
            BigDecimal next = current.add(delta);
            tx.update("UPDATE " + tx.t("smp_economy") + " SET player_name=?, balance=?, updated_at=? WHERE player_uuid=?",
                    safeName(playerName), next, System.currentTimeMillis(), playerUuid.toString());
            return next;
        });
    }

    public BigDecimal set(UUID playerUuid, String playerName, BigDecimal balance) {
        return db.tx(tx -> {
            long now = System.currentTimeMillis();
            int updated = tx.update("UPDATE " + tx.t("smp_economy") +
                            " SET player_name=?, balance=?, updated_at=? WHERE player_uuid=?",
                    safeName(playerName), balance, now, playerUuid.toString());
            if (updated == 0) {
                tx.update("INSERT INTO " + tx.t("smp_economy") +
                                " (player_uuid, player_name, balance, created_at, updated_at) VALUES (?, ?, ?, ?, ?)",
                        playerUuid.toString(), safeName(playerName), balance, now, now);
            }
            return balance;
        });
    }

    private void touchName(Db tx, UUID playerUuid, String playerName) {
        if (playerName == null || playerName.isBlank()) {
            return;
        }
        tx.update("UPDATE " + tx.t("smp_economy") + " SET player_name=?, updated_at=? WHERE player_uuid=?",
                safeName(playerName), System.currentTimeMillis(), playerUuid.toString());
    }

    private String safeName(String playerName) {
        if (playerName == null || playerName.isBlank()) {
            return null;
        }
        return playerName.length() > 32 ? playerName.substring(0, 32) : playerName;
    }
}
