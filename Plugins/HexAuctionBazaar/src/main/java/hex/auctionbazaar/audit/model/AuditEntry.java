package hex.auctionbazaar.audit.model;

import java.math.BigDecimal;
import java.util.UUID;

/** Pojedynczy wpis w logu audytowym (odczyt z bazy). */
public record AuditEntry(
        long id,
        long createdAt,
        UUID actorUuid,
        String actorName,
        String action,
        String market,
        String itemKey,
        Long listingId,
        Long orderId,
        Long claimId,
        Long amount,
        BigDecimal unitPrice,
        BigDecimal total,
        String result,
        String reason,
        String metadataJson
) {
}
