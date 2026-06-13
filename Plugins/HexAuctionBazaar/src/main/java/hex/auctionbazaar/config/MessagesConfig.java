package hex.auctionbazaar.config;

import java.util.Map;
import java.util.Objects;

/**
 * Wrapped raw map; lookups are dot-pathed (e.g. "auction.listing-created").
 * Missing keys return a debug-friendly fallback rather than null.
 */
public final class MessagesConfig {

    private final Map<String, String> entries;

    public MessagesConfig(Map<String, String> entries) {
        this.entries = Map.copyOf(Objects.requireNonNull(entries, "entries"));
    }

    public String get(String path) {
        String value = entries.get(path);
        return value == null ? "&cmissing message: " + path : value;
    }

    public boolean has(String path) {
        return entries.containsKey(path);
    }

    public Map<String, String> raw() {
        return entries;
    }
}
