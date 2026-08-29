package hex.events.api;

import java.util.Map;

public record CostReceipt(String providerType, String costId, Map<String, String> data) {
    public CostReceipt { data = data == null ? Map.of() : Map.copyOf(data); }
}
