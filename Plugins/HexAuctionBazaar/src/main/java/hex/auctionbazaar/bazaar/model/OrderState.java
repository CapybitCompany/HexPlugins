package hex.auctionbazaar.bazaar.model;

/**
 * Cykl zycia zlecenia Bazaar.
 *  ACTIVE            -> zlecenie oczekuje w orderbooku
 *  PARTIALLY_FILLED  -> czesc zostala zrealizowana, reszta oczekuje
 *  FILLED            -> zlecenie w pelni zrealizowane
 *  CANCELLED         -> anulowane przez gracza; zwrocone srodki/przedmioty
 *  EXPIRED           -> automatyczne wygasniecie (opcjonalne)
 */
public enum OrderState {
    ACTIVE,
    PARTIALLY_FILLED,
    FILLED,
    CANCELLED,
    EXPIRED;

    public boolean isOpen() {
        return this == ACTIVE || this == PARTIALLY_FILLED;
    }

    public boolean isFinal() {
        return this == FILLED || this == CANCELLED || this == EXPIRED;
    }
}
