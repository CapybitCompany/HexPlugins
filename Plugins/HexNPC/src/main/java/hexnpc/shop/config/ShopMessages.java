package hexnpc.shop.config;

import java.util.Objects;

public record ShopMessages(
        String economyMissing,
        String shopNotFound,
        String inventoryFull,
        String notEnoughMoney,
        String notEnoughItems,
        String bought,
        String sold,
        String transactionFailed,
        String tradeBusy
) {
    public ShopMessages {
        economyMissing = Objects.toString(economyMissing, "");
        shopNotFound = Objects.toString(shopNotFound, "");
        inventoryFull = Objects.toString(inventoryFull, "");
        notEnoughMoney = Objects.toString(notEnoughMoney, "");
        notEnoughItems = Objects.toString(notEnoughItems, "");
        bought = Objects.toString(bought, "");
        sold = Objects.toString(sold, "");
        transactionFailed = Objects.toString(transactionFailed, "");
        tradeBusy = Objects.toString(tradeBusy, "");
    }

    public static ShopMessages defaults() {
        return new ShopMessages(
                "&cEkonomia jest niedostępna.",
                "&cNie znaleziono sklepu: &f<shop>",
                "&cNie masz miejsca w ekwipunku.",
                "&cNie masz wystarczająco pieniędzy.",
                "&cNie masz wystarczająco przedmiotów.",
                "&aKupiono &f<amount>x <item> &aza &f<price>&a.",
                "&aSprzedano &f<amount>x <item> &aza &f<price>&a.",
                "&cTransakcja nie powiodła się. &7<reason>",
                "&ePoczekaj, poprzednia transakcja nadal trwa."
        );
    }
}
