package hex.auctionbazaar.auction.model;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Shared claim structure for:
 *  - Auction-Sale (money to the seller)
 *  - Auction-Expire/Cancel (item back to the seller)
 *  - Bazaar-Buy with full inventory (item to the buyer)
 *  - Refund/Recovery (money or item back)
 */
public record AuctionClaim(
        long id,
        UUID ownerUuid,
        byte[] itemBlob,        // either an item ...
        BigDecimal moneyAmount, // ... or money; never both at once
        String reason,
        Long listingId,
        long createdAt,
        ClaimState state
) {
    public boolean isMoney() {
        return moneyAmount != null;
    }

    public boolean isItem() {
        return itemBlob != null && itemBlob.length > 0;
    }
}
