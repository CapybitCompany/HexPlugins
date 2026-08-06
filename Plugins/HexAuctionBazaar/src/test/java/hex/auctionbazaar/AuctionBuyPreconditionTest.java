package hex.auctionbazaar;

import hex.auctionbazaar.auction.model.AuctionListing;
import hex.auctionbazaar.auction.model.ListingState;
import hex.auctionbazaar.auction.service.AuctionService;
import hex.auctionbazaar.auction.service.AuctionService.BuyOutcome;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Punkt #2: kupno WŁASNEJ aukcji jest blokowane przez czysty guard oceniany
 * PRZED rezerwacją i pobraniem środków (żadnej mutacji DB/Economy).
 */
class AuctionBuyPreconditionTest {

    private static AuctionListing listing(UUID seller, ListingState state) {
        return new AuctionListing(1L, seller, "Seller", new byte[]{1}, "DIAMOND", 1,
                new BigDecimal("100"), state, 0L, Long.MAX_VALUE, null, null, null, null,
                null, null, null, null);
    }

    @Test
    void ownActiveListingIsBlocked() {
        UUID me = UUID.randomUUID();
        assertEquals(BuyOutcome.OWN_LISTING,
                AuctionService.checkPreBuy(listing(me, ListingState.ACTIVE), me));
    }

    @Test
    void foreignActiveListingProceeds() {
        UUID seller = UUID.randomUUID();
        UUID buyer = UUID.randomUUID();
        assertNull(AuctionService.checkPreBuy(listing(seller, ListingState.ACTIVE), buyer));
    }

    @Test
    void nullListingIsNotActive() {
        assertEquals(BuyOutcome.NOT_ACTIVE, AuctionService.checkPreBuy(null, UUID.randomUUID()));
    }

    @Test
    void nonActiveListingIsBlockedBeforeOwnershipCheck() {
        UUID me = UUID.randomUUID();
        // Nawet własna, ale nieaktywna aukcja -> NOT_ACTIVE (nie przechodzi do rezerwacji).
        assertEquals(BuyOutcome.NOT_ACTIVE,
                AuctionService.checkPreBuy(listing(me, ListingState.RESERVED), me));
    }
}
