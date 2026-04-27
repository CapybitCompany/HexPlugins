package hex.core.placeholder;

import hex.core.placeholder.provider.PlaceholderProvider;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public final class HexPlaceholderRegistry {

    private final Map<String, PlaceholderProvider> providers = new ConcurrentHashMap<>();
    private final Map<String, PlaceholderProvider> prefixProviders = new ConcurrentHashMap<>();

    public void register(PlaceholderProvider provider) {
        providers.put(normalize(provider.getIdentifier()), provider);
    }

    /**
     * Registers a provider that matches by identifier prefix (case-insensitive).
     * Example: prefix "top_global_" will handle identifiers like "top_global_1_name".
     */
    public void registerPrefix(String prefix, PlaceholderProvider provider) {
        prefixProviders.put(normalize(prefix), provider);
    }

    public Optional<PlaceholderProvider> find(String identifier) {
        String key = normalize(identifier);

        PlaceholderProvider direct = providers.get(key);
        if (direct != null) {
            return Optional.of(direct);
        }

        // Try prefix providers (longest prefix first to avoid collisions)
        if (!prefixProviders.isEmpty()) {
            PlaceholderProvider best = null;
            int bestLen = -1;
            for (var entry : prefixProviders.entrySet()) {
                String prefix = entry.getKey();
                if (key.startsWith(prefix) && prefix.length() > bestLen) {
                    best = entry.getValue();
                    bestLen = prefix.length();
                }
            }
            if (best != null) {
                return Optional.of(best);
            }
        }

        return Optional.empty();
    }

    private String normalize(String identifier) {
        if (identifier == null) {
            return "";
        }
        return identifier.toLowerCase(Locale.ROOT);
    }
}
