package hex.core.placeholder.provider;

import hex.core.database.model.MoneyTopEntry;
import hex.core.placeholder.HexPlaceholderContext;
import hex.core.service.ranking.MoneyTopService;

import java.math.BigDecimal;
import java.util.Locale;

public final class TopMoneyPlaceholderProvider implements PlaceholderProvider {

    private final MoneyTopService moneyTopService;

    public TopMoneyPlaceholderProvider(MoneyTopService moneyTopService) {
        this.moneyTopService = moneyTopService;
    }

    @Override
    public String getIdentifier() {
        return "top_money";
    }

    @Override
    public String resolve(HexPlaceholderContext context) {
        String identifier = context.identifier();
        if (identifier == null) {
            return null;
        }

        String id = identifier.toLowerCase(Locale.ROOT);
        if (!id.startsWith("top_money_")) {
            return null;
        }

        id = id.substring("top_money_".length());
        String[] parts = id.split("_", 2);
        if (parts.length != 2) {
            return null;
        }

        int position;
        try {
            position = Integer.parseInt(parts[0]);
        } catch (NumberFormatException ex) {
            return null;
        }

        if (position < 1 || position > 10) {
            return "-";
        }

        MoneyTopEntry entry = moneyTopService.getTop(position);
        return switch (parts[1]) {
            case "name" -> normalizeName(entry.playerName());
            case "amount" -> formatAmount(entry.totalSpent());
            default -> null;
        };
    }

    private String normalizeName(String value) {
        return value == null || value.isBlank() ? "-" : value;
    }

    private String formatAmount(BigDecimal amount) {
        return amount == null ? "-" : amount.stripTrailingZeros().toPlainString();
    }
}

