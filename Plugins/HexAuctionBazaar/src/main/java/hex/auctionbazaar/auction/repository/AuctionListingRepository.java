package hex.auctionbazaar.auction.repository;

import hex.auctionbazaar.auction.model.AuctionListing;
import hex.auctionbazaar.auction.model.ListingState;
import hex.core.api.db.Db;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public final class AuctionListingRepository {

    private static final String TABLE = "hex_auction_listings";

    private final Db db;

    public AuctionListingRepository(Db db) {
        this.db = Objects.requireNonNull(db, "db");
    }

    private String t() {
        return db.t(TABLE);
    }

    public void ensureTable() {
        db.update("CREATE TABLE IF NOT EXISTS " + t() + " (" +
                "id BIGINT AUTO_INCREMENT PRIMARY KEY," +
                "seller_uuid CHAR(36) NOT NULL," +
                "seller_name VARCHAR(32) NULL," +
                "item_blob BLOB NOT NULL," +
                "item_material VARCHAR(64) NOT NULL," +
                "item_amount INT NOT NULL," +
                "price DECIMAL(19,2) NOT NULL," +
                "state VARCHAR(16) NOT NULL," +
                "created_at BIGINT NOT NULL," +
                "expires_at BIGINT NOT NULL," +
                "reserved_by_uuid CHAR(36) NULL," +
                "reserved_until BIGINT NULL," +
                "sold_to_uuid CHAR(36) NULL," +
                "sold_at BIGINT NULL," +
                "KEY idx_state_expires (state, expires_at)," +
                "KEY idx_seller (seller_uuid)" +
                ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
    }

    public long insert(UUID sellerUuid, String sellerName, byte[] itemBlob, String material,
                       int amount, BigDecimal price, long createdAt, long expiresAt) {
        return db.tx(tx -> {
            tx.update("INSERT INTO " + t() +
                            " (seller_uuid, seller_name, item_blob, item_material, item_amount, price, " +
                            "  state, created_at, expires_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    sellerUuid.toString(), sellerName, itemBlob, material, amount,
                    price, ListingState.ACTIVE.name(), createdAt, expiresAt);
            return tx.queryOne("SELECT LAST_INSERT_ID() AS id",
                    rs -> rs.getLong("id")).orElseThrow();
        });
    }

    public Optional<AuctionListing> findById(long id) {
        return db.queryOne("SELECT * FROM " + t() + " WHERE id=?",
                AuctionListingRepository::map, id);
    }

    public List<AuctionListing> findActive(int limit, int offset) {
        return db.query("SELECT * FROM " + t() + " WHERE state=? ORDER BY id DESC LIMIT ? OFFSET ?",
                AuctionListingRepository::map, ListingState.ACTIVE.name(), limit, offset);
    }

    public List<AuctionListing> findActiveSorted(int limit, int offset,
                                                  hex.auctionbazaar.auction.service.AuctionService.SortMode sort) {
        String orderBy = switch (sort) {
            case PRICE_ASC -> "price ASC, id DESC";
            case PRICE_DESC -> "price DESC, id DESC";
            default -> "id DESC";
        };
        return db.query("SELECT * FROM " + t() + " WHERE state=? ORDER BY " + orderBy + " LIMIT ? OFFSET ?",
                AuctionListingRepository::map, ListingState.ACTIVE.name(), limit, offset);
    }

    public int countActive() {
        return db.queryOne(
                "SELECT COUNT(*) AS c FROM " + t() + " WHERE state=?",
                rs -> rs.getInt("c"),
                ListingState.ACTIVE.name()
        ).orElse(0);
    }

    public List<AuctionListing> findActiveBySeller(UUID seller) {
        return db.query("SELECT * FROM " + t() + " WHERE seller_uuid=? AND state IN (?, ?) ORDER BY id DESC",
                AuctionListingRepository::map, seller.toString(),
                ListingState.ACTIVE.name(), ListingState.RESERVED.name());
    }

    public int countActiveBySeller(UUID seller) {
        return db.queryOne(
                "SELECT COUNT(*) AS c FROM " + t() + " WHERE seller_uuid=? AND state IN (?, ?)",
                rs -> rs.getInt("c"),
                seller.toString(), ListingState.ACTIVE.name(), ListingState.RESERVED.name()
        ).orElse(0);
    }

    /** ACTIVE -&gt; RESERVED. Returns true on success. */
    public boolean tryReserve(long listingId, UUID buyer, long reservedUntilEpochMs) {
        int updated = db.update(
                "UPDATE " + t() + " SET state=?, reserved_by_uuid=?, reserved_until=? " +
                        " WHERE id=? AND state=?",
                ListingState.RESERVED.name(), buyer.toString(), reservedUntilEpochMs,
                listingId, ListingState.ACTIVE.name()
        );
        return updated == 1;
    }

    /** RESERVED -&gt; ACTIVE (release after withdraw failure). */
    public boolean releaseReservation(long listingId, UUID buyer) {
        int updated = db.update(
                "UPDATE " + t() + " SET state=?, reserved_by_uuid=NULL, reserved_until=NULL " +
                        " WHERE id=? AND state=? AND reserved_by_uuid=?",
                ListingState.ACTIVE.name(), listingId, ListingState.RESERVED.name(), buyer.toString()
        );
        return updated == 1;
    }

    /**
     * Atomic in one transaction: RESERVED -&gt; SOLD + insert pending money-claim
     * for the seller. Returns the new claim id, or empty when the listing was
     * not in RESERVED state for this buyer.
     */
    public Optional<Long> markSoldWithSellerClaimTx(long listingId, UUID buyer, long soldAt,
                                                     UUID sellerUuid, BigDecimal netAmount,
                                                     String reason) {
        return db.tx(tx -> {
            int updated = tx.update(
                    "UPDATE " + tx.t(TABLE) + " SET state=?, sold_to_uuid=?, sold_at=?, " +
                            " reserved_by_uuid=NULL, reserved_until=NULL " +
                            " WHERE id=? AND state=? AND reserved_by_uuid=?",
                    ListingState.SOLD.name(), buyer.toString(), soldAt,
                    listingId, ListingState.RESERVED.name(), buyer.toString());
            if (updated != 1) {
                return Optional.<Long>empty();
            }
            long claimId = AuctionClaimRepository.insertMoneyTx(
                    tx, sellerUuid, netAmount, reason, listingId, soldAt);
            return Optional.of(claimId);
        });
    }

    /**
     * Atomic ACTIVE -&gt; CANCELLED + item-claim insert for the seller.
     * Returns the new claim id, or empty if the listing was no longer ACTIVE.
     */
    public Optional<Long> cancelActiveWithClaimTx(long listingId, UUID seller, byte[] itemBlob,
                                                   String reason, long now) {
        return db.tx(tx -> {
            int updated = tx.update(
                    "UPDATE " + tx.t(TABLE) + " SET state=? WHERE id=? AND seller_uuid=? AND state=?",
                    ListingState.CANCELLED.name(), listingId, seller.toString(),
                    ListingState.ACTIVE.name());
            if (updated != 1) {
                return Optional.<Long>empty();
            }
            long claimId = AuctionClaimRepository.insertItemTx(
                    tx, seller, itemBlob, reason, listingId, now);
            return Optional.of(claimId);
        });
    }

    /** Find expired ACTIVE listings (read-only). */
    public List<AuctionListing> findExpired(long nowMs, int limit) {
        return db.query(
                "SELECT * FROM " + t() + " WHERE state=? AND expires_at<=? ORDER BY id ASC LIMIT ?",
                AuctionListingRepository::map,
                ListingState.ACTIVE.name(), nowMs, limit);
    }

    /**
     * Atomic: for each expired listing, mark EXPIRED + insert item-claim for
     * the seller. Returns the number of listings actually processed.
     */
    public int expireBatchWithClaimsTx(List<AuctionListing> due, long now) {
        if (due.isEmpty()) return 0;
        return db.tx(tx -> {
            int processed = 0;
            for (AuctionListing l : due) {
                int updated = tx.update(
                        "UPDATE " + tx.t(TABLE) + " SET state=? WHERE id=? AND state=?",
                        ListingState.EXPIRED.name(), l.id(), ListingState.ACTIVE.name());
                if (updated == 1) {
                    AuctionClaimRepository.insertItemTx(
                            tx, l.sellerUuid(), l.itemBlob(),
                            "auction-expired-" + l.id(), l.id(), now);
                    processed++;
                }
            }
            return processed;
        });
    }

    /** RESERVED with expired reserved_until -&gt; ACTIVE. */
    public int releaseStaleReservations(long nowMs) {
        return db.update(
                "UPDATE " + t() + " SET state=?, reserved_by_uuid=NULL, reserved_until=NULL " +
                        " WHERE state=? AND reserved_until IS NOT NULL AND reserved_until<=?",
                ListingState.ACTIVE.name(), ListingState.RESERVED.name(), nowMs);
    }

    private static AuctionListing map(ResultSet rs) throws SQLException {
        long reservedUntilRaw = rs.getLong("reserved_until");
        Long reservedUntil = rs.wasNull() ? null : reservedUntilRaw;
        long soldAtRaw = rs.getLong("sold_at");
        Long soldAt = rs.wasNull() ? null : soldAtRaw;
        String reservedBy = rs.getString("reserved_by_uuid");
        String soldTo = rs.getString("sold_to_uuid");
        return new AuctionListing(
                rs.getLong("id"),
                UUID.fromString(rs.getString("seller_uuid")),
                rs.getString("seller_name"),
                rs.getBytes("item_blob"),
                rs.getString("item_material"),
                rs.getInt("item_amount"),
                rs.getBigDecimal("price"),
                ListingState.valueOf(rs.getString("state")),
                rs.getLong("created_at"),
                rs.getLong("expires_at"),
                reservedBy == null ? null : UUID.fromString(reservedBy),
                reservedUntil,
                soldTo == null ? null : UUID.fromString(soldTo),
                soldAt
        );
    }
}
