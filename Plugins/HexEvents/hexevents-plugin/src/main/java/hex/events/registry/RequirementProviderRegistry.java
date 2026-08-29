package hex.events.registry;

import hex.events.api.RequirementProvider;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

public final class RequirementProviderRegistry {
    private final Map<String, RequirementProvider> providers = new ConcurrentHashMap<>();
    public void register(RequirementProvider provider) { providers.put(normalize(provider.type()), provider); }
    public Optional<RequirementProvider> find(String type) { return Optional.ofNullable(providers.get(normalize(type))); }
    public Map<String, RequirementProvider> snapshot() { return Map.copyOf(providers); }
    private static String normalize(String type) { return type == null ? "" : type.trim().toLowerCase(java.util.Locale.ROOT); }
}
