package hexnpc.shop.gui;

import java.math.BigDecimal;

/**
 * Niezmienna migawka „ile gracz może teraz sprzedać i za ile" — przekazywana
 * do buildera GUI, aby ten nie musiał sam sięgać do gracza. To tylko podgląd:
 * przy kliknięciu/potwierdzeniu ilość i cena są liczone ponownie serwerowo.
 */
public record SellAllQuote(int amount, BigDecimal totalPrice) {

    public SellAllQuote {
        if (amount < 0) {
            amount = 0;
        }
        totalPrice = totalPrice == null ? BigDecimal.ZERO : totalPrice;
    }

    public static SellAllQuote empty() {
        return new SellAllQuote(0, BigDecimal.ZERO);
    }

    public boolean hasItems() {
        return amount > 0;
    }
}
