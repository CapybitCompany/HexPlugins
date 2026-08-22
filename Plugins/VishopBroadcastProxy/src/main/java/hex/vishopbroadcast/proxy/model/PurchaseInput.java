package hex.vishopbroadcast.proxy.model;

import java.math.BigDecimal;
import java.util.UUID;

public record PurchaseInput(
        String externalId,
        UUID playerUuid,
        String playerName,
        String serviceKey,
        String serviceDisplay,
        String amount,
        BigDecimal price,
        String broadcastInfo,
        String createdBy
) {
}
