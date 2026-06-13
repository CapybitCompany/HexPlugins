package hex.auctionbazaar.auction.repository;

import hex.auctionbazaar.auction.model.AuctionClaim;
import hex.auctionbazaar.auction.model.ClaimState;
import hex.core.api.db.Db;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public final class AuctionClaimRepository {

    private static final String TABLE = "hex_auction_claims";

    private final Db db;

    public AuctionClaimRepository(Db db) {
        this.db = Objects.requireNonNull(db, "db");
    }

    private String t() {
        return db.t(TABLE);
    }

    public void ensureTable() {
        db.update("CREATE TABLE IF NOT EXISTS " + t() + " (" +
                "id BIGINT AUTO_INCREMENT PRIMARY KEY," +
                "owner_uuid CHAR(36) NOT NULL," +
                "item_blob BLOB NULL," +
                "money_amount DECIMAL(19,2) NULL," +
                "reason VARCHAR(64) NOT NULL," +
                "listing_id BIGINT NULL," +
                "created_at BIGINT NOT NULL," +
                "state VARCHAR(16) NOT NULL DEFAULT 'PENDING'," +
                "KEY idx_owner_state (owner_uuid, state)" +
                ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
        // Schema guard: a legacy table from a previous dev run may not have
        // the state column or the (owner_uuid, state) index. We probe
        // INFORMATION_SCHEMA first and only ALTER when the column / index is
        // actually missing. Works on any MySQL/MariaDB version without
        // requiring ADD COLUMN IF NOT EXISTS support.
        String fullTable = t();
        if (!columnExists(fullTable, "state")) {
            db.update("ALTER TABLE " + fullTable +
                    " ADD COLUMN state VARCHAR(16) NOT NULL DEFAULT 'PENDING'");
        }
        if (!indexExists(fullTable, "idx_owner_state")) {
            try {
                db.update("ALTER TABLE " + fullTable +
                        " ADD INDEX idx_owner_state (owner_uuid, state)");
            } catch (RuntimeException ignored) {
                // Index may exist under a different name; not critical for correctness.
            }
        }
    }

    private boolean columnExists(String tableName, String columnName) {
        return db.queryOne(
                "SELECT COUNT(*) AS c FROM INFORMATION_SCHEMA.COLUMNS" +
                        " WHERE TABLE_SCHEMA = DATABASE()" +
                        "   AND TABLE_NAME = ?" +
                        "   AND COLUMN_NAME = ?",
                rs -> rs.getInt("c"),
                tableName, columnName
        ).orElse(0) > 0;
    }

    private boolean indexExists(String tableName, String indexName) {
        return db.queryOne(
                "SELECT COUNT(*) AS c FROM INFORMATION_SCHEMA.STATISTICS" +
                        " WHERE TABLE_SCHEMA = DATABASE()" +
                        "   AND TABLE_NAME = ?" +
                        "   AND INDEX_NAME = ?",
                rs -> rs.getInt("c"),
                tableName, indexName
        ).orElse(0) > 0;
    }

    public long insertItem(UUID owner, byte[] item, String reason, Long listingId, long now) {
        return db.tx(tx -> insertItemTx(tx, owner, item, reason, listingId, now));
    }

    public static long insertItemTx(Db tx, UUID owner, byte[] item, String reason, Long listingId, long now) {
        String table = tx.t(TABLE);
        tx.update("INSERT INTO " + table +
                        " (owner_uuid, item_blob, money_amount, reason, listing_id, created_at, state)" +
                        " VALUES (?, ?, NULL, ?, ?, ?, ?)",
                owner.toString(), item, reason, listingId, now, ClaimState.PENDING.name());
        return tx.queryOne("SELECT LAST_INSERT_ID() AS id",
                rs -> rs.getLong("id")).orElseThrow();
    }

    public long insertMoney(UUID owner, BigDecimal amount, String reason, Long listingId, long now) {
        return db.tx(tx -> insertMoneyTx(tx, owner, amount, reason, listingId, now));
    }

    public static long insertMoneyTx(Db tx, UUID owner, BigDecimal amount, String reason, Long listingId, long now) {
        String table = tx.t(TABLE);
        tx.update("INSERT INTO " + table +
                        " (owner_uuid, item_blob, money_amount, reason, listing_id, created_at, state)" +
                        " VALUES (?, NULL, ?, ?, ?, ?, ?)",
                owner.toString(), amount, reason, listingId, now, ClaimState.PENDING.name());
        return tx.queryOne("SELECT LAST_INSERT_ID() AS id",
                rs -> rs.getLong("id")).orElseThrow();
    }

    /** Only PENDING claims are visible to the player UI. */
    public List<AuctionClaim> findPendingByOwner(UUID owner, int limit) {
        return db.query(
                "SELECT * FROM " + t() + " WHERE owner_uuid=? AND state=? ORDER BY id ASC LIMIT ?",
                AuctionClaimRepository::map, owner.toString(), ClaimState.PENDING.name(), limit);
    }

    /**
     * Atomic PENDING -&gt; CLAIMING. Returns the locked claim snapshot if the
     * transition succeeded, or empty if the claim is gone / already claiming.
     */
    public Optional<AuctionClaim> tryReserve(long claimId, UUID owner) {
        return db.tx(tx -> {
            int updated = tx.update(
                    "UPDATE " + tx.t(TABLE) + " SET state=? " +
                            " WHERE id=? AND owner_uuid=? AND state=?",
                    ClaimState.CLAIMING.name(), claimId, owner.toString(), ClaimState.PENDING.name());
            if (updated != 1) {
                return Optional.<AuctionClaim>empty();
            }
            return tx.queryOne("SELECT * FROM " + tx.t(TABLE) + " WHERE id=?",
                    AuctionClaimRepository::map, claimId);
        });
    }

    /** CLAIMING -&gt; PENDING rollback when payout fails. */
    public boolean rollback(long claimId, UUID owner) {
        int updated = db.update(
                "UPDATE " + t() + " SET state=? " +
                        " WHERE id=? AND owner_uuid=? AND state=?",
                ClaimState.PENDING.name(), claimId, owner.toString(), ClaimState.CLAIMING.name());
        return updated == 1;
    }

    /** Final delete - call only after the payout has been observed successful. */
    public boolean delete(long claimId, UUID owner) {
        int updated = db.update(
                "DELETE FROM " + t() + " WHERE id=? AND owner_uuid=? AND state=?",
                claimId, owner.toString(), ClaimState.CLAIMING.name());
        return updated == 1;
    }

    private static AuctionClaim map(ResultSet rs) throws SQLException {
        long listingIdRaw = rs.getLong("listing_id");
        Long listingId = rs.wasNull() ? null : listingIdRaw;
        BigDecimal money = rs.getBigDecimal("money_amount");
        String stateRaw = rs.getString("state");
        ClaimState state = stateRaw == null ? ClaimState.PENDING : ClaimState.valueOf(stateRaw);
        return new AuctionClaim(
                rs.getLong("id"),
                UUID.fromString(rs.getString("owner_uuid")),
                rs.getBytes("item_blob"),
                money,
                rs.getString("reason"),
                listingId,
                rs.getLong("created_at"),
                state
        );
    }
}
