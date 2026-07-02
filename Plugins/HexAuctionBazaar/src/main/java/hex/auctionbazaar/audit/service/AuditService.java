package hex.auctionbazaar.audit.service;

import hex.auctionbazaar.audit.model.AuditEntry;
import hex.auctionbazaar.audit.repository.AuditLogRepository;
import hex.auctionbazaar.bridge.HexCoreBridge;
import hex.auctionbazaar.util.MessageFactory;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Warstwa uslug do zapisow audytowych. Zapisy dziala asynchronicznie,
 * a blad zapisu nigdy nie moze zatrzymac zaakceptowanej transakcji gracza
 * (blad trafia do konsoli serwera).
 * Formatowanie wpisow dla admina korzysta z messages.yml aby zachowac
 * Polski jezyk i konfigurowalne etykiety.
 */
public final class AuditService {

    private final Logger logger;
    private final HexCoreBridge hexCore;
    private final AuditLogRepository repo;
    private final MessageFactory messages;

    public AuditService(Logger logger, HexCoreBridge hexCore, AuditLogRepository repo,
                        MessageFactory messages) {
        this.logger = Objects.requireNonNull(logger, "logger");
        this.hexCore = Objects.requireNonNull(hexCore, "hexCore");
        this.repo = Objects.requireNonNull(repo, "repo");
        this.messages = Objects.requireNonNull(messages, "messages");
    }

    public static final class Builder {
        private UUID actorUuid;
        private String actorName;
        private String action;
        private String market;
        private String itemKey;
        private Long listingId;
        private Long orderId;
        private Long claimId;
        private Long amount;
        private BigDecimal unitPrice;
        private BigDecimal total;
        private String result = "OK";
        private String reason;
        private String metadataJson;

        public Builder actor(UUID uuid, String name) {
            this.actorUuid = uuid;
            this.actorName = name;
            return this;
        }
        public Builder action(String v) { this.action = v; return this; }
        public Builder market(String v) { this.market = v; return this; }
        public Builder itemKey(String v) { this.itemKey = v; return this; }
        public Builder listingId(Long v) { this.listingId = v; return this; }
        public Builder orderId(Long v) { this.orderId = v; return this; }
        public Builder claimId(Long v) { this.claimId = v; return this; }
        public Builder amount(Long v) { this.amount = v; return this; }
        public Builder unitPrice(BigDecimal v) { this.unitPrice = v; return this; }
        public Builder total(BigDecimal v) { this.total = v; return this; }
        public Builder result(String v) { this.result = v; return this; }
        public Builder reason(String v) { this.reason = v; return this; }
        public Builder metadataJson(String v) { this.metadataJson = v; return this; }
    }

    public Builder builder() {
        return new Builder();
    }

    /** Nieblokujacy zapis - bledy trafiaja do logu, ale nie sa propagowane dalej. */
    public CompletableFuture<Long> log(Builder b) {
        Objects.requireNonNull(b, "builder");
        long now = System.currentTimeMillis();
        return hexCore.async(() -> repo.insert(
                        now, b.actorUuid, b.actorName, b.action, b.market, b.itemKey,
                        b.listingId, b.orderId, b.claimId, b.amount, b.unitPrice, b.total,
                        b.result, b.reason, b.metadataJson))
                .exceptionally(ex -> {
                    logger.log(Level.WARNING, "audit log insert failed action=" + b.action, ex);
                    return -1L;
                });
    }

    public CompletableFuture<List<AuditEntry>> query(String kind, String value, int limit) {
        return hexCore.async(() -> {
            switch (kind.toLowerCase()) {
                case "player":
                case "actor": {
                    try {
                        UUID uuid = UUID.fromString(value);
                        return repo.findByActor(uuid, limit);
                    } catch (IllegalArgumentException ex) {
                        return List.<AuditEntry>of();
                    }
                }
                case "item":
                    return repo.findByItem(value.toLowerCase(), limit);
                case "listing":
                    try {
                        return repo.findByListing(Long.parseLong(value), limit);
                    } catch (NumberFormatException ex) {
                        return List.<AuditEntry>of();
                    }
                case "order":
                    try {
                        return repo.findByOrder(Long.parseLong(value), limit);
                    } catch (NumberFormatException ex) {
                        return List.<AuditEntry>of();
                    }
                case "market":
                    return repo.findByMarket(value.toUpperCase(), limit);
                default:
                    return List.<AuditEntry>of();
            }
        }).exceptionally(ex -> {
            logger.log(Level.WARNING, "audit query failed", ex);
            return List.of();
        });
    }

    public CompletableFuture<List<String>> queryFormatted(String kind, String value, int limit) {
        return query(kind, value, limit).thenApply(entries -> {
            List<String> lines = new ArrayList<>();
            DateTimeFormatter fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
                    .withZone(ZoneId.systemDefault());
            for (AuditEntry e : entries) {
                StringBuilder fields = new StringBuilder();
                appendField(fields, "auction.admin-audit-field-market", e.market());
                if (e.actorName() != null) appendField(fields, "auction.admin-audit-field-actor", e.actorName());
                if (e.itemKey() != null) appendField(fields, "auction.admin-audit-field-item", e.itemKey());
                if (e.amount() != null) appendField(fields, "auction.admin-audit-field-amount",
                        String.valueOf(e.amount()));
                if (e.total() != null) appendField(fields, "auction.admin-audit-field-total",
                        e.total().toPlainString());
                if (e.orderId() != null) appendField(fields, "auction.admin-audit-field-order",
                        String.valueOf(e.orderId()));
                if (e.listingId() != null) appendField(fields, "auction.admin-audit-field-listing",
                        String.valueOf(e.listingId()));
                String resultLabel = switch (e.result() == null ? "" : e.result()) {
                    case "OK" -> messages.raw("auction.admin-audit-result-ok", null);
                    case "FAILED" -> messages.raw("auction.admin-audit-result-failed", null);
                    case "ROLLBACK" -> messages.raw("auction.admin-audit-result-rollback", null);
                    case "REFUND_PENDING" -> messages.raw("auction.admin-audit-result-refund-pending", null);
                    default -> e.result();
                };
                lines.add(messages.raw("auction.admin-audit-line",
                        MessageFactory.placeholders(
                                "date", fmt.format(Instant.ofEpochMilli(e.createdAt())),
                                "action", e.action(),
                                "fields", fields.toString().trim(),
                                "result", resultLabel)));
            }
            return lines;
        });
    }

    private void appendField(StringBuilder sb, String path, String value) {
        if (value == null || value.isEmpty()) return;
        sb.append(messages.raw(path, MessageFactory.placeholders("value", value)));
        sb.append(' ');
    }
}
