package hex.auctionbazaar.audit.repository;

import hex.auctionbazaar.audit.model.AuditEntry;
import hex.core.api.db.Db;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Repozytorium tabeli hex_market_audit_log.
 * Zapisy dziala asynchronicznie (przez HexCoreBridge.async), a bledy nie
 * moga zerwac zakonczonej transakcji gracza.
 */
public final class AuditLogRepository {

    private static final String TABLE = "hex_market_audit_log";

    private final Db db;

    public AuditLogRepository(Db db) {
        this.db = Objects.requireNonNull(db, "db");
    }

    private String t() {
        return db.t(TABLE);
    }

    public void ensureTable() {
        db.update("CREATE TABLE IF NOT EXISTS " + t() + " (" +
                "id BIGINT AUTO_INCREMENT PRIMARY KEY," +
                "created_at BIGINT NOT NULL," +
                "actor_uuid CHAR(36) NULL," +
                "actor_name VARCHAR(32) NULL," +
                "action VARCHAR(64) NOT NULL," +
                "market VARCHAR(32) NOT NULL," +
                "item_key VARCHAR(128) NULL," +
                "listing_id BIGINT NULL," +
                "order_id BIGINT NULL," +
                "claim_id BIGINT NULL," +
                "amount BIGINT NULL," +
                "unit_price DECIMAL(19,2) NULL," +
                "total DECIMAL(19,2) NULL," +
                "result VARCHAR(32) NOT NULL," +
                "reason VARCHAR(255) NULL," +
                "metadata_json TEXT NULL," +
                "KEY idx_created_at (created_at)," +
                "KEY idx_actor (actor_uuid)," +
                "KEY idx_action (action)," +
                "KEY idx_market (market)," +
                "KEY idx_item_key (item_key)" +
                ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
    }

    public long insert(long createdAt, UUID actorUuid, String actorName,
                       String action, String market, String itemKey,
                       Long listingId, Long orderId, Long claimId,
                       Long amount, BigDecimal unitPrice, BigDecimal total,
                       String result, String reason, String metadataJson) {
        db.update("INSERT INTO " + t() + " (" +
                        "created_at, actor_uuid, actor_name, action, market, item_key, " +
                        "listing_id, order_id, claim_id, amount, unit_price, total, " +
                        "result, reason, metadata_json" +
                        ") VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                createdAt,
                actorUuid == null ? null : actorUuid.toString(),
                actorName, action, market, itemKey,
                listingId, orderId, claimId, amount, unitPrice, total,
                result, reason, metadataJson);
        return db.queryOne("SELECT LAST_INSERT_ID() AS id",
                rs -> rs.getLong("id")).orElse(-1L);
    }

    public List<AuditEntry> findByActor(UUID actor, int limit) {
        return db.query(
                "SELECT * FROM " + t() + " WHERE actor_uuid=? ORDER BY id DESC LIMIT ?",
                AuditLogRepository::map, actor.toString(), limit);
    }

    public List<AuditEntry> findByItem(String itemKey, int limit) {
        return db.query(
                "SELECT * FROM " + t() + " WHERE item_key=? ORDER BY id DESC LIMIT ?",
                AuditLogRepository::map, itemKey, limit);
    }

    public List<AuditEntry> findByListing(long listingId, int limit) {
        return db.query(
                "SELECT * FROM " + t() + " WHERE listing_id=? ORDER BY id DESC LIMIT ?",
                AuditLogRepository::map, listingId, limit);
    }

    public List<AuditEntry> findByOrder(long orderId, int limit) {
        return db.query(
                "SELECT * FROM " + t() + " WHERE order_id=? ORDER BY id DESC LIMIT ?",
                AuditLogRepository::map, orderId, limit);
    }

    public List<AuditEntry> findByMarket(String market, int limit) {
        return db.query(
                "SELECT * FROM " + t() + " WHERE market=? ORDER BY id DESC LIMIT ?",
                AuditLogRepository::map, market, limit);
    }

    private static AuditEntry map(ResultSet rs) throws SQLException {
        String actor = rs.getString("actor_uuid");
        Long listingId = nullableLong(rs, "listing_id");
        Long orderId = nullableLong(rs, "order_id");
        Long claimId = nullableLong(rs, "claim_id");
        Long amount = nullableLong(rs, "amount");
        return new AuditEntry(
                rs.getLong("id"),
                rs.getLong("created_at"),
                actor == null ? null : UUID.fromString(actor),
                rs.getString("actor_name"),
                rs.getString("action"),
                rs.getString("market"),
                rs.getString("item_key"),
                listingId,
                orderId,
                claimId,
                amount,
                rs.getBigDecimal("unit_price"),
                rs.getBigDecimal("total"),
                rs.getString("result"),
                rs.getString("reason"),
                rs.getString("metadata_json")
        );
    }

    private static Long nullableLong(ResultSet rs, String col) throws SQLException {
        long raw = rs.getLong(col);
        return rs.wasNull() ? null : raw;
    }
}
