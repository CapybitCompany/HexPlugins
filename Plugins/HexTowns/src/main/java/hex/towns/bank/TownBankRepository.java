package hex.towns.bank;

import hex.core.api.db.Db;
import hex.towns.util.UuidBytes;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Durable town-bank ledger. All mutations are expected to be serialized by TownsService. */
public final class TownBankRepository {
    private static final BigDecimal ZERO = new BigDecimal("0.00");

    private final Db db;

    public TownBankRepository(Db db) {
        this.db = db;
    }

    public void ensureTables() {
        db.update("CREATE TABLE IF NOT EXISTS " + db.t("town_bank") + " (" +
                "town_id BIGINT UNSIGNED NOT NULL," +
                "balance DECIMAL(19,2) NOT NULL DEFAULT 0.00," +
                "updated_at BIGINT NOT NULL," +
                "PRIMARY KEY (town_id)" +
                ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin");

        db.update("CREATE TABLE IF NOT EXISTS " + db.t("town_bank_accounts") + " (" +
                "town_id BIGINT UNSIGNED NOT NULL," +
                "player_uuid BINARY(16) NOT NULL," +
                "deposited_gross DECIMAL(19,2) NOT NULL DEFAULT 0.00," +
                "deposit_tax DECIMAL(19,2) NOT NULL DEFAULT 0.00," +
                "withdrawn DECIMAL(19,2) NOT NULL DEFAULT 0.00," +
                "debt_repaid DECIMAL(19,2) NOT NULL DEFAULT 0.00," +
                "net_balance DECIMAL(19,2) NOT NULL DEFAULT 0.00," +
                "updated_at BIGINT NOT NULL," +
                "PRIMARY KEY (town_id, player_uuid)," +
                "KEY idx_bank_player (player_uuid)" +
                ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin");

        String suffix = Integer.toUnsignedString(db.tablePrefix().hashCode(), 16);
        try {
            db.update("ALTER TABLE " + db.t("town_bank") +
                    " ADD CONSTRAINT fk_" + suffix + "_town_bank_parent FOREIGN KEY (town_id) REFERENCES " +
                    db.t("towns") + "(id) ON DELETE CASCADE");
        } catch (RuntimeException ignored) {
            // Already present or unsupported. Core cleanup also deletes bank rows explicitly.
        }
        try {
            db.update("ALTER TABLE " + db.t("town_bank_accounts") +
                    " ADD CONSTRAINT fk_" + suffix + "_town_bank_accounts_parent FOREIGN KEY (town_id) REFERENCES " +
                    db.t("towns") + "(id) ON DELETE CASCADE");
        } catch (RuntimeException ignored) {
            // Already present or unsupported. Core cleanup also deletes bank rows explicitly.
        }
    }

    public BigDecimal balance(long townId) {
        return db.queryOne("SELECT balance FROM " + db.t("town_bank") + " WHERE town_id=?",
                rs -> money(rs.getBigDecimal("balance")), townId).orElse(ZERO);
    }

    public Optional<MemberAccount> account(long townId, UUID playerId) {
        if (playerId == null) return Optional.empty();
        return db.queryOne("SELECT player_uuid, deposited_gross, deposit_tax, withdrawn, debt_repaid, net_balance " +
                        "FROM " + db.t("town_bank_accounts") + " WHERE town_id=? AND player_uuid=?",
                rs -> new MemberAccount(
                        UuidBytes.fromBytes(rs.getBytes("player_uuid")),
                        money(rs.getBigDecimal("deposited_gross")),
                        money(rs.getBigDecimal("deposit_tax")),
                        money(rs.getBigDecimal("withdrawn")),
                        money(rs.getBigDecimal("debt_repaid")),
                        money(rs.getBigDecimal("net_balance"))
                ), townId, UuidBytes.toBytes(playerId));
    }

    public Map<UUID, MemberAccount> accounts(long townId) {
        Map<UUID, MemberAccount> result = new LinkedHashMap<>();
        db.query("SELECT player_uuid, deposited_gross, deposit_tax, withdrawn, debt_repaid, net_balance " +
                        "FROM " + db.t("town_bank_accounts") + " WHERE town_id=? ORDER BY updated_at ASC",
                rs -> {
                    MemberAccount account = new MemberAccount(
                            UuidBytes.fromBytes(rs.getBytes("player_uuid")),
                            money(rs.getBigDecimal("deposited_gross")),
                            money(rs.getBigDecimal("deposit_tax")),
                            money(rs.getBigDecimal("withdrawn")),
                            money(rs.getBigDecimal("debt_repaid")),
                            money(rs.getBigDecimal("net_balance"))
                    );
                    result.put(account.playerId(), account);
                    return account;
                }, townId);
        return Map.copyOf(result);
    }

    public void recordDeposit(long townId, UUID playerId, BigDecimal gross, BigDecimal tax, BigDecimal net) {
        long now = System.currentTimeMillis();
        BigDecimal safeGross = money(gross);
        BigDecimal safeTax = money(tax);
        BigDecimal safeNet = money(net);
        db.tx(tx -> {
            ensureBankRow(tx, townId, now);
            tx.update("UPDATE " + tx.t("town_bank") + " SET balance=balance+?, updated_at=? WHERE town_id=?",
                    safeNet, now, townId);
            tx.update("INSERT INTO " + tx.t("town_bank_accounts") +
                            " (town_id, player_uuid, deposited_gross, deposit_tax, withdrawn, debt_repaid, net_balance, updated_at) " +
                            "VALUES (?, ?, ?, ?, 0.00, 0.00, ?, ?) " +
                            "ON DUPLICATE KEY UPDATE deposited_gross=deposited_gross+VALUES(deposited_gross), " +
                            "deposit_tax=deposit_tax+VALUES(deposit_tax), net_balance=net_balance+VALUES(net_balance), updated_at=VALUES(updated_at)",
                    townId, UuidBytes.toBytes(playerId), safeGross, safeTax, safeNet, now);
            return null;
        });
    }

    /** Returns false when the town bank does not contain enough money. */
    public boolean recordWithdrawal(long townId, UUID playerId, BigDecimal amount) {
        long now = System.currentTimeMillis();
        BigDecimal safeAmount = money(amount);
        return db.tx(tx -> {
            ensureBankRow(tx, townId, now);
            int changed = tx.update("UPDATE " + tx.t("town_bank") +
                            " SET balance=balance-?, updated_at=? WHERE town_id=? AND balance>=?",
                    safeAmount, now, townId, safeAmount);
            if (changed <= 0) return false;
            tx.update("INSERT INTO " + tx.t("town_bank_accounts") +
                            " (town_id, player_uuid, deposited_gross, deposit_tax, withdrawn, debt_repaid, net_balance, updated_at) " +
                            "VALUES (?, ?, 0.00, 0.00, ?, 0.00, ?, ?) " +
                            "ON DUPLICATE KEY UPDATE withdrawn=withdrawn+VALUES(withdrawn), " +
                            "net_balance=net_balance+VALUES(net_balance), updated_at=VALUES(updated_at)",
                    townId, UuidBytes.toBytes(playerId), safeAmount, safeAmount.negate(), now);
            return true;
        });
    }

    public void rollbackWithdrawal(long townId, UUID playerId, BigDecimal amount) {
        long now = System.currentTimeMillis();
        BigDecimal safeAmount = money(amount);
        db.tx(tx -> {
            ensureBankRow(tx, townId, now);
            tx.update("UPDATE " + tx.t("town_bank") + " SET balance=balance+?, updated_at=? WHERE town_id=?",
                    safeAmount, now, townId);
            tx.update("UPDATE " + tx.t("town_bank_accounts") +
                            " SET withdrawn=GREATEST(0.00, withdrawn-?), net_balance=net_balance+?, updated_at=? " +
                            "WHERE town_id=? AND player_uuid=?",
                    safeAmount, safeAmount, now, townId, UuidBytes.toBytes(playerId));
            return null;
        });
    }

    public void recordDebtRepayment(long townId, UUID playerId, BigDecimal amount) {
        long now = System.currentTimeMillis();
        BigDecimal safeAmount = money(amount);
        db.tx(tx -> {
            ensureBankRow(tx, townId, now);
            tx.update("UPDATE " + tx.t("town_bank") + " SET balance=balance+?, updated_at=? WHERE town_id=?",
                    safeAmount, now, townId);
            tx.update("INSERT INTO " + tx.t("town_bank_accounts") +
                            " (town_id, player_uuid, deposited_gross, deposit_tax, withdrawn, debt_repaid, net_balance, updated_at) " +
                            "VALUES (?, ?, 0.00, 0.00, 0.00, ?, ?, ?) " +
                            "ON DUPLICATE KEY UPDATE debt_repaid=debt_repaid+VALUES(debt_repaid), " +
                            "net_balance=net_balance+VALUES(net_balance), updated_at=VALUES(updated_at)",
                    townId, UuidBytes.toBytes(playerId), safeAmount, safeAmount, now);
            return null;
        });
    }

    public void deleteTown(long townId) {
        db.tx(tx -> {
            tx.update("DELETE FROM " + tx.t("town_bank_accounts") + " WHERE town_id=?", townId);
            tx.update("DELETE FROM " + tx.t("town_bank") + " WHERE town_id=?", townId);
            return null;
        });
    }

    private void ensureBankRow(Db tx, long townId, long now) {
        tx.update("INSERT INTO " + tx.t("town_bank") + " (town_id, balance, updated_at) VALUES (?, 0.00, ?) " +
                        "ON DUPLICATE KEY UPDATE town_id=VALUES(town_id)",
                townId, now);
    }

    private static BigDecimal money(BigDecimal value) {
        return value == null ? ZERO : value.setScale(2, java.math.RoundingMode.HALF_UP);
    }

    public record MemberAccount(UUID playerId,
                                BigDecimal depositedGross,
                                BigDecimal depositTax,
                                BigDecimal withdrawn,
                                BigDecimal debtRepaid,
                                BigDecimal netBalance) {
        public static MemberAccount empty(UUID playerId) {
            return new MemberAccount(playerId, ZERO, ZERO, ZERO, ZERO, ZERO);
        }

        public BigDecimal creditedNet() {
            return depositedGross.subtract(depositTax).setScale(2, java.math.RoundingMode.HALF_UP);
        }
    }
}
