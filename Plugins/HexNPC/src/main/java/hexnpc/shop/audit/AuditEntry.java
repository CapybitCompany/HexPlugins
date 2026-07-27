package hexnpc.shop.audit;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Niezmienny wpis audytu jednej próby transakcji. Nie zawiera pełnego NBT/PDC
 * ani danych wrażliwych — tylko identyfikatory, ilości, cenę i status.
 */
public record AuditEntry(
        String transactionId,
        UUID playerUuid,
        String playerName,
        String shopId,
        String itemId,
        String material,
        AuditAction action,
        int requestedQuantity,
        int actualQuantity,
        BigDecimal totalPrice,
        BigDecimal balanceAfter,
        AuditStatus status,
        String reason
) {
    /** Parametry do parametryzowanego INSERT (kolejność zgodna z kolumnami). */
    public Object[] toParams() {
        return new Object[]{
                transactionId,
                playerUuid == null ? null : playerUuid.toString(),
                truncate(playerName, 16),
                truncate(shopId, 128),
                truncate(itemId, 128),
                truncate(material, 64),
                action == null ? "UNKNOWN" : action.name(),
                requestedQuantity,
                actualQuantity,
                totalPrice == null ? BigDecimal.ZERO : totalPrice,
                balanceAfter, // może być null -> kolumna NULL
                status == null ? "UNKNOWN" : status.name(),
                truncate(reason, 255)
        };
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max);
    }
}
