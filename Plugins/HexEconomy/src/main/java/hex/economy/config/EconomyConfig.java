package hex.economy.config;

import org.bukkit.configuration.file.FileConfiguration;

import java.math.BigDecimal;
import java.math.RoundingMode;

public record EconomyConfig(
        String currencyName,
        String singular,
        String plural,
        String symbol,
        int decimals,
        String format,
        BigDecimal defaultBalance,
        boolean allowNegativeBalance,
        Messages messages
) {
    public static EconomyConfig load(FileConfiguration cfg) {
        int decimals = Math.max(0, Math.min(8, cfg.getInt("currency.decimals", 2)));
        BigDecimal defaultBalance = parseDecimal(cfg.getString("default-balance", "0"), BigDecimal.ZERO).setScale(decimals, RoundingMode.HALF_UP);
        return new EconomyConfig(
                cfg.getString("currency.name", "monety"),
                cfg.getString("currency.singular", "moneta"),
                cfg.getString("currency.plural", "monet"),
                cfg.getString("currency.symbol", "⛃"),
                decimals,
                cfg.getString("currency.format", "{amount} {currency}"),
                defaultBalance,
                cfg.getBoolean("allow-negative-balance", false),
                Messages.load(cfg)
        );
    }

    public BigDecimal normalize(BigDecimal value) {
        if (value == null) {
            return BigDecimal.ZERO.setScale(decimals, RoundingMode.HALF_UP);
        }
        return value.setScale(decimals, RoundingMode.HALF_UP);
    }

    public String formatAmount(BigDecimal amount) {
        BigDecimal normalized = normalize(amount);
        String amountText = normalized.toPlainString();
        String currency = normalized.compareTo(BigDecimal.ONE.setScale(decimals, RoundingMode.HALF_UP)) == 0 ? singular : plural;
        return format
                .replace("{amount}", amountText)
                .replace("{currency}", currencyName)
                .replace("{singular}", singular)
                .replace("{plural}", plural)
                .replace("{currency_form}", currency)
                .replace("{symbol}", symbol);
    }

    private static BigDecimal parseDecimal(String raw, BigDecimal fallback) {
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        try {
            return new BigDecimal(raw.trim().replace(',', '.'));
        } catch (NumberFormatException exception) {
            return fallback;
        }
    }

    public record Messages(
            String prefix,
            String playerOnly,
            String noPermission,
            String usageMain,
            String usageBalance,
            String usageAdmin,
            String balanceSelf,
            String balanceOther,
            String added,
            String removed,
            String set,
            String notEnough,
            String invalidAmount,
            String negativeDisabled,
            String dbError,
            String reloaded
    ) {
        private static Messages load(FileConfiguration cfg) {
            return new Messages(
                    cfg.getString("messages.prefix", ""),
                    cfg.getString("messages.player-only", "&cTa komenda jest tylko dla gracza."),
                    cfg.getString("messages.no-permission", "&cNie masz permisji."),
                    cfg.getString("messages.usage.main", "&7Użycie: &f/smpeconomy <balance|add|remove|set>"),
                    cfg.getString("messages.usage.balance", "&7Użycie: &f/money [gracz]"),
                    cfg.getString("messages.usage.admin", "&7Użycie: &f/smpeconomy <add|remove|set> <gracz> <kwota>"),
                    cfg.getString("messages.balance-self", "&aStan konta: &e{balance}"),
                    cfg.getString("messages.balance-other", "&aStan konta gracza &e{player}&a: &e{balance}"),
                    cfg.getString("messages.added", "&aDodano &e{amount}&a dla &e{player}&a. Stan: &e{balance}"),
                    cfg.getString("messages.removed", "&aUsunięto &e{amount}&a dla &e{player}&a. Stan: &e{balance}"),
                    cfg.getString("messages.set", "&aUstawiono konto &e{player}&a na &e{balance}"),
                    cfg.getString("messages.not-enough", "&cBrak środków."),
                    cfg.getString("messages.invalid-amount", "&cNieprawidłowa kwota: &e{amount}"),
                    cfg.getString("messages.negative-disabled", "&cSaldo nie może zejść poniżej zera."),
                    cfg.getString("messages.db-error", "&cBłąd bazy danych: &f{error}"),
                    cfg.getString("messages.reloaded", "&aPrzeładowano konfigurację HexEconomy.")
            );
        }
    }
}
