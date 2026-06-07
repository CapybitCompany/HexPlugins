package hex.core.database.model;

import java.math.BigDecimal;
import java.util.UUID;

public record MoneyTopEntry(
        UUID playerUuid,
        String playerName,
        BigDecimal totalSpent
) {
}

