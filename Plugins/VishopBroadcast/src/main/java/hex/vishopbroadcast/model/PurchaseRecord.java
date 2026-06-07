package hex.vishopbroadcast.model;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

public record PurchaseRecord(
        long id,
        String externalId,
        LocalDateTime purchaseTime,
        String serviceKey,
        String serviceDisplay,
        UUID playerUuid,
        String playerName,
        String amount,
        BigDecimal price,
        String broadcastInfo,
        String createdBy
) {
}

