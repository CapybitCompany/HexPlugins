package hexnpc.shop.config;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Wszystkie widoczne dla gracza/administratora teksty sklepu — czat oraz
 * podpisy elementów GUI. Wszystko po polsku i w pełni konfigurowalne
 * przez sekcję {@code shops.messages} w config.yml.
 *
 * <p>Przechowywane jako niezmienna mapa {klucz kebab-case -> tekst legacy
 * z kodami &}. Metody-akcesory zwracają wartość lub domyślną. Nieznane
 * klucze zwracają pusty string zamiast rzucać.
 */
public final class ShopMessages {

    private final Map<String, String> values;

    public ShopMessages(Map<String, String> overrides) {
        Map<String, String> merged = new LinkedHashMap<>(defaultValues());
        if (overrides != null) {
            for (Map.Entry<String, String> e : overrides.entrySet()) {
                if (e.getKey() != null && e.getValue() != null) {
                    merged.put(e.getKey(), e.getValue());
                }
            }
        }
        this.values = Map.copyOf(merged);
    }

    public static ShopMessages defaults() {
        return new ShopMessages(Map.of());
    }

    /** Zwraca surowy (legacy &) tekst dla klucza; pusty string, gdy brak. */
    public String get(String key) {
        return values.getOrDefault(key, "");
    }

    public Map<String, String> asMap() {
        return values;
    }

    // --- Akcesory czatu ---
    public String economyMissing() { return get("economy-missing"); }
    public String shopNotFound() { return get("shop-not-found"); }
    public String inventoryFull() { return get("inventory-full"); }
    public String notEnoughMoney() { return get("not-enough-money"); }
    public String notEnoughCurrency() { return get("not-enough-currency"); }
    public String notEnoughItems() { return get("not-enough-items"); }
    public String bought() { return get("bought"); }
    public String sold() { return get("sold"); }
    public String transactionFailed() { return get("transaction-failed"); }
    public String tradeBusy() { return get("trade-busy"); }
    public String invalidQuantity() { return get("invalid-quantity"); }
    public String quantityTooSmall() { return get("quantity-too-small"); }
    public String quantityTooLarge() { return get("quantity-too-large"); }
    public String buyLimitReached() { return get("buy-limit-reached"); }
    public String signExpired() { return get("sign-expired"); }
    public String signPrompt() { return get("sign-prompt"); }
    public String signChatFallback() { return get("sign-chat-fallback"); }
    public String nothingToSell() { return get("nothing-to-sell"); }
    public String buyDisabled() { return get("buy-disabled"); }
    public String sellDisabled() { return get("sell-disabled"); }
    public String alreadyPurchased() { return get("already-purchased"); }
    public String rewardFailed() { return get("reward-failed"); }
    public String customItemUnavailable() { return get("custom-item-unavailable"); }
    public String actionFailed() { return get("action-failed"); }

    // --- Podpisy GUI ---
    public String guiSelectedQuantity() { return get("gui-selected-quantity"); }
    public String guiPreviousPage() { return get("gui-previous-page"); }
    public String guiNextPage() { return get("gui-next-page"); }
    public String guiPageInfo() { return get("gui-page-info"); }
    public String guiBuyButton() { return get("gui-buy-button"); }
    public String guiSellButton() { return get("gui-sell-button"); }
    public String guiSellAllButton() { return get("gui-sell-all-button"); }
    public String guiCustomQuantityButton() { return get("gui-custom-quantity-button"); }
    public String guiBackButton() { return get("gui-back-button"); }
    public String guiPresetButton() { return get("gui-preset-button"); }
    public String guiBuyLimitLore() { return get("gui-buy-limit-lore"); }
    public String guiClickForDetails() { return get("gui-click-for-details"); }
    public String guiClickToOpen() { return get("gui-click-to-open"); }
    public String guiBuyLine() { return get("gui-buy-line"); }
    public String guiSellLine() { return get("gui-sell-line"); }
    public String guiClickToBuy() { return get("gui-click-to-buy"); }
    public String guiClickToSell() { return get("gui-click-to-sell"); }
    public String guiPresetSelected() { return get("gui-preset-selected"); }
    public String guiSellAllOwned() { return get("gui-sell-all-owned"); }
    public String guiSellAllEarn() { return get("gui-sell-all-earn"); }
    public String guiSellAllNone() { return get("gui-sell-all-none"); }
    public String guiConfirmBuy() { return get("gui-confirm-buy"); }
    public String guiConfirmSell() { return get("gui-confirm-sell"); }
    public String guiConfirmQuantity() { return get("gui-confirm-quantity"); }
    public String guiConfirmPrice() { return get("gui-confirm-price"); }
    public String guiConfirmPreviewHint() { return get("gui-confirm-preview-hint"); }
    public String guiCancelButton() { return get("gui-cancel-button"); }

    /**
     * Domyślne, polskie teksty. Klucze w kebab-case odpowiadają wpisom w
     * config.yml (sekcja {@code shops.messages}).
     */
    public static Map<String, String> defaultValues() {
        Map<String, String> m = new LinkedHashMap<>();
        // Czat
        m.put("economy-missing", "&cEkonomia jest niedostępna.");
        m.put("shop-not-found", "&cNie znaleziono sklepu: &f<shop>");
        m.put("inventory-full", "&cNie masz miejsca w ekwipunku.");
        m.put("not-enough-money", "&cNie masz wystarczająco pieniędzy.");
        m.put("not-enough-currency", "&cNie masz wystarczająco waluty &f<currency>&c.");
        m.put("not-enough-items", "&cNie masz wystarczająco przedmiotów.");
        m.put("bought", "&aKupiono &f<amount>x <item> &aza &f<price>&a.");
        m.put("sold", "&aSprzedano &f<amount>x <item> &aza &f<price>&a.");
        m.put("transaction-failed", "&cTransakcja nie powiodła się. &7<reason>");
        m.put("trade-busy", "&ePoczekaj, poprzednia transakcja nadal trwa.");
        m.put("invalid-quantity", "&cNieprawidłowa ilość. Podaj dodatnią liczbę całkowitą.");
        m.put("quantity-too-small", "&cIlość jest za mała. Minimum to &f<min>x&c.");
        m.put("quantity-too-large", "&cIlość jest za duża. Maksimum to &f<max>x&c.");
        m.put("buy-limit-reached",
                "&cMożesz kupić maksymalnie &f<limit>x &ctego przedmiotu dziennie. Pozostało dziś: &f<remaining>x&c.");
        m.put("sign-expired", "&cCzas na wpisanie ilości minął. Spróbuj ponownie.");
        m.put("sign-prompt", "&eWpisz ilość (od &f<min> &edo &f<max>&e).");
        m.put("sign-chat-fallback", "&eJeśli tabliczka się nie otworzyła, wpisz ilość na czacie.");
        m.put("nothing-to-sell", "&cNie masz nic do sprzedania.");
        m.put("buy-disabled", "&cTego przedmiotu nie można kupić.");
        m.put("sell-disabled", "&cTego przedmiotu nie można sprzedać.");
        m.put("already-purchased", "&cTen przedmiot już został zakupiony.");
        m.put("reward-failed", "&cNie udało się przyznać nagrody. Płatność została cofnięta.");
        m.put("custom-item-unavailable", "&cTen produkt jest chwilowo niedostępny. Zgłoś problem administracji.");
        m.put("action-failed", "&cNie udało się otworzyć tej funkcji.");
        // GUI
        m.put("gui-selected-quantity", "&eWybrana ilość: &f<quantity>x");
        m.put("gui-previous-page", "&ePoprzednia strona");
        m.put("gui-next-page", "&eNastępna strona");
        m.put("gui-page-info", "&fStrona &e<current>&7/&e<total>");
        m.put("gui-buy-button", "&aKup &f<quantity>x &7(&a<price>&7)");
        m.put("gui-sell-button", "&6Sprzedaj &f<quantity>x &7(&e<price>&7)");
        m.put("gui-sell-all-button", "&6Sprzedaj wszystko");
        m.put("gui-custom-quantity-button", "&bWłasna ilość");
        m.put("gui-back-button", "&cWróć");
        m.put("gui-preset-button", "&f<quantity>x");
        m.put("gui-buy-limit-lore", "&cDzienny limit: &f<limit>x &7(pozostało &f<remaining>x&7)");
        m.put("gui-click-for-details", "&8Kliknij, aby otworzyć szczegóły");
        m.put("gui-click-to-open", "&eKliknij, aby otworzyć");
        m.put("gui-buy-line", "&7Kup: &a<price>");
        m.put("gui-sell-line", "&7Sprzedaj: &e<price>");
        m.put("gui-click-to-buy", "&eKliknij, aby kupić");
        m.put("gui-click-to-sell", "&eKliknij, aby sprzedać");
        m.put("gui-preset-selected", "&aWybrano");
        m.put("gui-sell-all-owned", "&7Posiadasz: &f<amount>x");
        m.put("gui-sell-all-earn", "&7Otrzymasz: &e<price>");
        m.put("gui-sell-all-none", "&cNie masz tego przedmiotu");
        m.put("gui-confirm-buy", "&aPotwierdź zakup");
        m.put("gui-confirm-sell", "&6Potwierdź sprzedaż");
        m.put("gui-confirm-quantity", "&7Ilość: &f<amount>x");
        m.put("gui-confirm-price", "&7Łączna cena: &e<price>");
        m.put("gui-confirm-preview-hint", "&8Podgląd produktu — zatwierdź zielonym przyciskiem.");
        m.put("gui-cancel-button", "&cAnuluj");
        return m;
    }
}
