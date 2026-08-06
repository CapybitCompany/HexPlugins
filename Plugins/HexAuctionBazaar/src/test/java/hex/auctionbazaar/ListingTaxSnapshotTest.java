package hex.auctionbazaar;

import hex.auctionbazaar.auction.model.AuctionListing;
import hex.auctionbazaar.auction.model.ListingState;
import hex.auctionbazaar.auction.repository.AuctionListingRepository;
import hex.auctionbazaar.testutil.InMemoryDb;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Punkt #3: niezmienny snapshot podatkowy na aukcji + migracja niedestrukcyjna.
 */
class ListingTaxSnapshotTest {

    private static AuctionListing base(BigDecimal taxPct, BigDecimal taxAmt, BigDecimal fee, BigDecimal net) {
        return new AuctionListing(1L, UUID.randomUUID(), "S", new byte[]{1}, "DIAMOND", 1,
                new BigDecimal("100"), ListingState.ACTIVE, 0L, 1L, null, null, null, null,
                taxPct, taxAmt, fee, net);
    }

    @Test
    void legacyListingWithoutSnapshotIsTaxZeroNetGross() {
        AuctionListing legacy = base(null, null, null, null);
        assertFalse(legacy.hasTaxSnapshot());
        assertEquals(BigDecimal.ZERO, legacy.taxPercentOrZero());
        assertEquals(BigDecimal.ZERO, legacy.taxAmountOrZero());
        assertEquals(BigDecimal.ZERO, legacy.listingFeeAmountOrZero());
        assertEquals(new BigDecimal("100"), legacy.economicNetOrGross(), "legacy netto = brutto");
    }

    @Test
    void snapshotIsReturnedVerbatimRegardlessOfConfig() {
        AuctionListing l = base(new BigDecimal("8"), new BigDecimal("8.00"),
                new BigDecimal("0.00"), new BigDecimal("92.00"));
        assertTrue(l.hasTaxSnapshot());
        assertEquals(new BigDecimal("8"), l.taxPercentOrZero());
        assertEquals(new BigDecimal("8.00"), l.taxAmountOrZero());
        assertEquals(new BigDecimal("92.00"), l.economicNetOrGross(),
                "snapshot niezależny od aktualnych permisji/configu");
    }

    @Test
    void ensureTableCreatesAndMigratesSnapshotColumns() {
        InMemoryDb db = new InMemoryDb();
        new AuctionListingRepository(db).ensureTable();

        String create = db.operations().get(0).sql();
        assertTrue(create.startsWith("CREATE TABLE"), "pierwsza operacja to CREATE TABLE");
        assertTrue(create.contains("tax_percent") && create.contains("tax_amount")
                        && create.contains("listing_fee_amount") && create.contains("economic_net_amount"),
                "CREATE zawiera kolumny snapshotu");

        // Migracja niedestrukcyjna: ALTER ADD COLUMN dla brakujących kolumn.
        long alters = db.operations().stream()
                .filter(op -> op.sql().contains("ALTER TABLE") && op.sql().contains("ADD COLUMN"))
                .count();
        assertEquals(4, alters, "4 kolumny snapshotu dodane migracyjnie (gdy brak)");
        assertTrue(db.operations().stream().anyMatch(op ->
                op.sql().contains("ADD COLUMN economic_net_amount")));
    }
}
