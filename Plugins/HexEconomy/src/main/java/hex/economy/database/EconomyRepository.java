package hex.economy.database;

import hex.core.api.db.Db;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public final class EconomyRepository {
    private final Db db;

    public EconomyRepository(Db db) { this.db = db; }

    public void ensureTables() {
        db.update("CREATE TABLE IF NOT EXISTS " + db.t("smp_economy") + " (" +
                "player_uuid CHAR(36) NOT NULL," +
                "player_name VARCHAR(32) NULL," +
                "balance DECIMAL(19, 2) NOT NULL DEFAULT 0.00," +
                "created_at BIGINT NOT NULL," +
                "updated_at BIGINT NOT NULL," +
                "PRIMARY KEY (player_uuid)," +
                "KEY idx_player_name (player_name)," +
                "KEY idx_balance (balance)" +
                ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin");
        ensureBalanceIndex();
    }

    /** Returns the richest MONEY accounts. Used only by the async PlaceholderAPI cache refresh. */
    public List<TopBalance> getTopBalances(int requestedLimit) {
        int limit = Math.max(1, Math.min(100, requestedLimit));
        return List.copyOf(db.query(
                "SELECT player_uuid, player_name, balance FROM " + db.t("smp_economy") +
                        " WHERE player_name IS NOT NULL AND player_name <> ''" +
                        " ORDER BY balance DESC, player_name ASC, player_uuid ASC" +
                        " LIMIT " + limit,
                rs -> new TopBalance(
                        UUID.fromString(rs.getString("player_uuid")),
                        rs.getString("player_name"),
                        rs.getBigDecimal("balance")
                )
        ));
    }

    public List<PlayerAccount> findAccountsByName(String playerName) {
        if (playerName == null || playerName.isBlank()) return List.of();
        return db.query("SELECT player_uuid, player_name FROM " + db.t("smp_economy") +
                        " WHERE LOWER(player_name)=LOWER(?) ORDER BY updated_at DESC",
                rs -> new PlayerAccount(UUID.fromString(rs.getString("player_uuid")), rs.getString("player_name")),
                playerName.trim());
    }

    public Optional<BigDecimal> getBalance(UUID playerUuid) {
        return db.queryOne("SELECT balance FROM " + db.t("smp_economy") + " WHERE player_uuid=?",
                rs -> rs.getBigDecimal("balance"), playerUuid.toString());
    }

    public BigDecimal getOrCreateBalance(UUID playerUuid, String playerName, BigDecimal defaultBalance) {
        return db.tx(tx -> {
            ensureAccountRow(tx, playerUuid, playerName, defaultBalance);
            BigDecimal balance = tx.queryOne("SELECT balance FROM " + tx.t("smp_economy") + " WHERE player_uuid=?",
                    rs -> rs.getBigDecimal("balance"), playerUuid.toString()).orElse(defaultBalance);
            touchName(tx, playerUuid, playerName);
            return balance;
        });
    }

    public BigDecimal add(UUID playerUuid, String playerName, BigDecimal delta, BigDecimal defaultBalance) {
        return db.tx(tx -> {
            BigDecimal current = lockedBalanceOrCreate(tx, playerUuid, playerName, defaultBalance);
            BigDecimal next = current.add(delta);
            updateBalance(tx, playerUuid, playerName, next);
            return next;
        });
    }

    /**
     * Atomically checks and subtracts within the same DB transaction/row lock.
     */
    public WithdrawResult withdrawIfSufficient(UUID playerUuid, String playerName, BigDecimal amount,
                                               BigDecimal defaultBalance, boolean allowNegativeBalance) {
        return db.tx(tx -> {
            BigDecimal current = lockedBalanceOrCreate(tx, playerUuid, playerName, defaultBalance);
            if (!allowNegativeBalance && current.compareTo(amount) < 0) {
                touchName(tx, playerUuid, playerName);
                return new WithdrawResult(false, current);
            }
            BigDecimal next = current.subtract(amount);
            updateBalance(tx, playerUuid, playerName, next);
            return new WithdrawResult(true, next);
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


    private void ensureBalanceIndex() {
        try {
            db.update("ALTER TABLE " + db.t("smp_economy") + " ADD INDEX idx_balance (balance)");
        } catch (RuntimeException ignored) {
            // Existing installations normally already have this index. Duplicate-index errors are harmless.
        }
    }

    private BigDecimal lockedBalanceOrCreate(Db tx, UUID uuid, String playerName, BigDecimal defaultBalance) {
        // INSERT IGNORE first makes first-account creation safe when two purchases race on a previously unseen UUID.
        ensureAccountRow(tx, uuid, playerName, defaultBalance);
        return tx.queryOne("SELECT balance FROM " + tx.t("smp_economy") +
                        " WHERE player_uuid=? FOR UPDATE",
                rs -> rs.getBigDecimal("balance"), uuid.toString()).orElse(defaultBalance);
    }

    private void ensureAccountRow(Db tx, UUID uuid, String playerName, BigDecimal defaultBalance) {
        long now = System.currentTimeMillis();
        tx.update("INSERT IGNORE INTO " + tx.t("smp_economy") +
                        " (player_uuid, player_name, balance, created_at, updated_at) VALUES (?, ?, ?, ?, ?)",
                uuid.toString(), safeName(playerName), defaultBalance, now, now);
    }

    private void updateBalance(Db tx, UUID uuid, String playerName, BigDecimal balance) {
        tx.update("UPDATE " + tx.t("smp_economy") + " SET player_name=?, balance=?, updated_at=? WHERE player_uuid=?",
                safeName(playerName), balance, System.currentTimeMillis(), uuid.toString());
    }

    private void touchName(Db tx, UUID playerUuid, String playerName) {
        if (playerName == null || playerName.isBlank()) return;
        tx.update("UPDATE " + tx.t("smp_economy") + " SET player_name=?, updated_at=? WHERE player_uuid=?",
                safeName(playerName), System.currentTimeMillis(), playerUuid.toString());
    }

    public record PlayerAccount(UUID uuid, String playerName) {}
    public record TopBalance(UUID playerUuid, String playerName, BigDecimal balance) {}
    public record WithdrawResult(boolean success, BigDecimal balance) {}

    private String safeName(String playerName) {
        if (playerName == null || playerName.isBlank()) return null;
        return playerName.length() > 32 ? playerName.substring(0, 32) : playerName;
    }
}
