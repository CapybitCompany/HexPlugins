package hex.auctionbazaar.auction.model;

import java.math.BigDecimal;
import java.util.UUID;

public record AuctionListing(
        long id,
        UUID sellerUuid,
        String sellerName,
        byte[] itemBlob,
        String itemMaterial,
        int itemAmount,
        BigDecimal price,
        ListingState state,
        long createdAt,
        long expiresAt,
        UUID reservedByUuid,
        Long reservedUntil,
        UUID soldToUuid,
        Long soldAt
) {
}
