package hex.events.registry;

import hex.events.api.RewardProvider;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

public final class RewardProviderRegistry {
    private final Map<String, RewardProvider> providers = new LinkedHashMap<>();
    public void register(RewardProvider provider){ providers.put(provider.type().toLowerCase(java.util.Locale.ROOT), provider); }
    public Optional<RewardProvider> find(String type){ return Optional.ofNullable(providers.get(type.toLowerCase(java.util.Locale.ROOT))); }
    public Map<String, RewardProvider> all(){ return Map.copyOf(providers); }
}
