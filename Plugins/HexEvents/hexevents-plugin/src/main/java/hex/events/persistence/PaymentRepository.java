package hex.events.persistence;

import hex.core.api.db.Db;
import hex.events.api.CostReceipt;
import hex.events.util.ReceiptCodec;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

/** Durable payment/refund ledger. Ambiguous crash windows are never auto-retried. */
public final class PaymentRepository {
    private final Db db;
    public PaymentRepository(Db db) { this.db = db; }

    public void ensureTable() {
        db.update("CREATE TABLE IF NOT EXISTS " + db.t("event_payments") + " (" +
                "payment_uuid CHAR(36) NOT NULL," +
                "instance_uuid CHAR(36) NOT NULL," +
                "player_uuid CHAR(36) NOT NULL," +
                "player_name VARCHAR(32) NULL," +
                "cost_id VARCHAR(96) NOT NULL," +
                "provider VARCHAR(64) NOT NULL," +
                "receipt_data LONGTEXT NOT NULL," +
                "idempotency_key VARCHAR(191) NOT NULL," +
                "status VARCHAR(40) NOT NULL," +
                "created_at BIGINT NOT NULL," +
                "updated_at BIGINT NOT NULL," +
                "PRIMARY KEY (payment_uuid)," +
                "UNIQUE KEY uq_event_payment_idem (idempotency_key)," +
                "KEY idx_event_payment_player (player_uuid, status)," +
                "KEY idx_event_payment_instance (instance_uuid, status)" +
                ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin");
    }

    public PaymentRow beginCharge(UUID instanceId, UUID playerId, String playerName, String provider, String costId, String idem) {
        UUID paymentId = UUID.nameUUIDFromBytes(("hexevent-payment:" + idem).getBytes(StandardCharsets.UTF_8));
        long now = System.currentTimeMillis();
        db.update("INSERT IGNORE INTO " + db.t("event_payments") +
                        " (payment_uuid,instance_uuid,player_uuid,player_name,cost_id,provider,receipt_data,idempotency_key,status,created_at,updated_at)" +
                        " VALUES (?,?,?,?,?,?,?,?,?,?,?)",
                paymentId.toString(), instanceId.toString(), playerId.toString(), playerName, costId, provider,
                "", idem, "CHARGE_PENDING", now, now);
        return new PaymentRow(paymentId, instanceId, playerId, playerName, new CostReceipt(provider, costId, java.util.Map.of()), idem, "CHARGE_PENDING");
    }

    public void markCharged(UUID paymentId, CostReceipt receipt) {
        db.update("UPDATE " + db.t("event_payments") + " SET receipt_data=?, status='CHARGED', updated_at=? WHERE payment_uuid=? AND status='CHARGE_PENDING'",
                ReceiptCodec.encode(receipt.data()), System.currentTimeMillis(), paymentId.toString());
    }

    public void markStatus(UUID paymentId, String status) {
        db.update("UPDATE " + db.t("event_payments") + " SET status=?, updated_at=? WHERE payment_uuid=?",
                status, System.currentTimeMillis(), paymentId.toString());
    }

    public boolean beginRefund(UUID paymentId) {
        int updated = db.update("UPDATE " + db.t("event_payments") +
                        " SET status='REFUNDING', updated_at=? WHERE payment_uuid=? AND status IN ('CHARGED','REFUND_PENDING','REFUND_FAILED_RETRYABLE')",
                System.currentTimeMillis(), paymentId.toString());
        return updated > 0;
    }

    public void completeRefund(UUID paymentId) { markStatus(paymentId, "REFUNDED"); }
    public void failRefund(UUID paymentId) { markStatus(paymentId, "REFUND_FAILED_RETRYABLE"); }
    public void requireRefundReconciliation(UUID paymentId) { markStatus(paymentId, "REFUND_RECONCILIATION_REQUIRED"); }

    /**
     * Called once after DB startup. Operations left mid-flight by a JVM/server crash are ambiguous;
     * retrying them could duplicate money/items, so they are moved to manual reconciliation.
     */
    public void recoverAmbiguousOperations() {
        long now = System.currentTimeMillis();
        db.update("UPDATE " + db.t("event_payments") + " SET status='CHARGE_RECONCILIATION_REQUIRED', updated_at=? WHERE status='CHARGE_PENDING'", now);
        db.update("UPDATE " + db.t("event_payments") + " SET status='REFUND_RECONCILIATION_REQUIRED', updated_at=? WHERE status='REFUNDING'", now);
    }

    public List<PaymentRow> loadRefundable(UUID instanceId, UUID playerId) {
        return db.query("SELECT * FROM " + db.t("event_payments") +
                        " WHERE instance_uuid=? AND player_uuid=? AND status IN ('CHARGED','REFUND_PENDING','REFUND_FAILED_RETRYABLE') ORDER BY created_at DESC",
                rs -> row(rs.getString("payment_uuid"), rs.getString("instance_uuid"), rs.getString("player_uuid"), rs.getString("player_name"),
                        rs.getString("provider"), rs.getString("cost_id"), rs.getString("receipt_data"), rs.getString("idempotency_key"), rs.getString("status")),
                instanceId.toString(), playerId.toString());
    }

    public List<PaymentRow> loadPendingRefunds(UUID playerId) {
        return db.query("SELECT * FROM " + db.t("event_payments") +
                        " WHERE player_uuid=? AND status IN ('REFUND_PENDING','REFUND_FAILED_RETRYABLE') ORDER BY created_at ASC",
                rs -> row(rs.getString("payment_uuid"), rs.getString("instance_uuid"), rs.getString("player_uuid"), rs.getString("player_name"),
                        rs.getString("provider"), rs.getString("cost_id"), rs.getString("receipt_data"), rs.getString("idempotency_key"), rs.getString("status")),
                playerId.toString());
    }

    public boolean hasPendingRefund(UUID instanceId, UUID playerId) {
        return db.queryOne("SELECT payment_uuid FROM " + db.t("event_payments") +
                        " WHERE instance_uuid=? AND player_uuid=? AND status IN ('REFUND_PENDING','REFUND_FAILED_RETRYABLE','REFUNDING') LIMIT 1",
                rs -> rs.getString("payment_uuid"), instanceId.toString(), playerId.toString()).isPresent();
    }

    public boolean hasReconciliationRequired(UUID instanceId, UUID playerId) {
        return db.queryOne("SELECT payment_uuid FROM " + db.t("event_payments") +
                        " WHERE instance_uuid=? AND player_uuid=? AND status IN ('CHARGE_RECONCILIATION_REQUIRED','REFUND_RECONCILIATION_REQUIRED') LIMIT 1",
                rs -> rs.getString("payment_uuid"), instanceId.toString(), playerId.toString()).isPresent();
    }

    public void markForfeited(UUID instanceId, UUID playerId) {
        db.update("UPDATE " + db.t("event_payments") + " SET status='FORFEITED', updated_at=?" +
                        " WHERE instance_uuid=? AND player_uuid=? AND status IN ('CHARGED','REFUND_PENDING','REFUND_FAILED_RETRYABLE')",
                System.currentTimeMillis(), instanceId.toString(), playerId.toString());
    }

    private static PaymentRow row(String paymentId, String instanceId, String playerId, String playerName,
                                  String provider, String costId, String receiptData, String idem, String status) {
        CostReceipt receipt = new CostReceipt(provider, costId, receiptData == null || receiptData.isBlank() ? java.util.Map.of() : ReceiptCodec.decode(receiptData));
        return new PaymentRow(UUID.fromString(paymentId), UUID.fromString(instanceId), UUID.fromString(playerId), playerName, receipt, idem, status);
    }

    public record PaymentRow(UUID paymentId, UUID instanceId, UUID playerId, String playerName,
                             CostReceipt receipt, String idempotencyKey, String status) { }
}
