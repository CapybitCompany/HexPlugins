package hex.events.provider;

import hex.economy.api.CurrencyType;
import hex.economy.api.EconomyResult;
import hex.economy.api.HexEconomyApi;
import hex.events.api.CostCheck;
import hex.events.api.CostOperationResult;
import hex.events.api.CostProvider;
import hex.events.api.CostReceipt;
import hex.events.api.EventModuleSettings;
import hex.events.api.PlayerContext;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Objects;

/** HexEconomy-backed cost provider. MONEY is JDBC-backed and may run async; HEX_COINS stays on main because its external XConomy backend has no explicit async-thread contract. */
public final class EconomyCostProvider implements CostProvider {
    private final String type;
    private final CurrencyType currency;
    private final HexEconomyApi api;

    public EconomyCostProvider(String type, String currency, HexEconomyApi api) {
        this.type = type;
        this.currency = CurrencyType.valueOf(currency);
        this.api = Objects.requireNonNull(api, "api");
    }

    @Override public String type() { return type; }

    private BigDecimal amount(EventModuleSettings settings) {
        Object raw = settings.get("amount").orElse("0");
        try { return new BigDecimal(String.valueOf(raw)); }
        catch (NumberFormatException ex) { return BigDecimal.ZERO; }
    }

    @Override public CostCheck validate(PlayerContext player, EventModuleSettings settings) {
        BigDecimal amount = amount(settings);
        if (!api.isCurrencyAvailable(currency)) return CostCheck.fail("Waluta " + currency + " jest niedostępna.");
        if (amount.signum() <= 0) return CostCheck.fail("Koszt musi być > 0.");
        return api.has(player.playerId(), currency, amount) ? CostCheck.ok() : CostCheck.fail("Brak środków: " + api.format(currency, amount));
    }

    @Override public CostOperationResult charge(PlayerContext player, EventModuleSettings settings, String costId, String idempotencyKey) {
        BigDecimal amount = amount(settings);
        EconomyResult result = api.withdraw(player.playerId(), player.playerName(), currency, amount, "HexEvents entry " + idempotencyKey);
        if (!result.success()) return CostOperationResult.failed(result.reason(), false);
        return CostOperationResult.charged(new CostReceipt(type, costId, Map.of("currency", currency.name(), "amount", amount.toPlainString())));
    }

    @Override public CostOperationResult refund(PlayerContext player, CostReceipt receipt, String idempotencyKey) {
        try {
            CurrencyType receiptCurrency = CurrencyType.valueOf(receipt.data().getOrDefault("currency", currency.name()));
            BigDecimal amount = new BigDecimal(receipt.data().getOrDefault("amount", "0"));
            EconomyResult result = api.deposit(player.playerId(), player.playerName(), receiptCurrency, amount, "HexEvents refund " + idempotencyKey);
            return result.success() ? CostOperationResult.refunded() : CostOperationResult.failed(result.reason(), true);
        } catch (Exception ex) { return CostOperationResult.failed(ex.getMessage(), true); }
    }

    @Override public boolean available() { return api.isCurrencyAvailable(currency); }
    @Override public String unavailableReason() { return available() ? "" : "HexEconomy currency unavailable: " + currency; }
    @Override public boolean requiresMainThread() { return currency != CurrencyType.MONEY; }
}
