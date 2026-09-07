package hex.events.api;

import java.math.BigDecimal;

public record RewardGrant(String type, BigDecimal amount, EventModuleSettings settings) {
    public RewardGrant {
        amount = amount == null ? BigDecimal.ZERO : amount;
        settings = settings == null ? EventModuleSettings.empty() : settings;
    }
}
