package hex.events.registry;

import hex.events.api.CostProvider;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

public final class CostProviderRegistry {
    private final Map<String, CostProvider> providers = new ConcurrentHashMap<>();
    public void register(CostProvider provider) { providers.put(normalize(provider.type()), provider); }
    public Optional<CostProvider> find(String type) { return Optional.ofNullable(providers.get(normalize(type))); }
    public Map<String, CostProvider> snapshot() { return Map.copyOf(providers); }
    private static String normalize(String type) { return type == null ? "" : type.trim().toLowerCase(java.util.Locale.ROOT); }
}
