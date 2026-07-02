package hex.auctionbazaar.bazaar.model;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Pojedyncze zlecenie w orderbooku Bazaar.
 * amountTotal - zamowiona ilosc
 * amountRemaining - ile jeszcze pozostalo do zrealizowania
 * reservedMoney - dla BUY: kasa zablokowana przy skladaniu zlecenia
 * (reservedItemBlob nie jest tu obecny - dla SELL zdejmujemy fizyczne
 * przedmioty z ekwipunku i zwracamy je przy anulowaniu poprzez claim).
 */
public record BazaarOrder(
        long id,
        UUID ownerUuid,
        String ownerName,
        String itemKey,
        OrderSide side,
        long amountTotal,
        long amountRemaining,
        BigDecimal pricePerUnit,
        BigDecimal reservedMoney,
        OrderState state,
        long createdAt,
        long updatedAt,
        Long expiresAt
) {
}
