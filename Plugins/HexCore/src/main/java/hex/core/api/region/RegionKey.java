package hex.core.api.region;

import java.util.Locale;
import java.util.Objects;

public final class RegionKey {
    private final String namespace;
    private final String id;

    public RegionKey(String namespace, String id) {
        this.namespace = normalize(namespace);
        this.id = normalize(id);
        if (this.namespace.isBlank() || this.id.isBlank()) {
            throw new IllegalArgumentException("RegionKey parts cannot be blank");
        }
    }

    public static RegionKey parse(String raw) {
        Objects.requireNonNull(raw, "raw");
        String[] parts = raw.split(":", 2);
        if (parts.length != 2) throw new IllegalArgumentException("Invalid key format. Use namespace:id");
        return new RegionKey(parts[0], parts[1]);
    }

    private static String normalize(String s) {
        return Objects.requireNonNull(s, "value").trim().toLowerCase(Locale.ROOT);
    }

    public String namespace() { return namespace; }
    public String id() { return id; }

    @Override public String toString() { return namespace + ":" + id; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof RegionKey that)) return false;
        return namespace.equals(that.namespace) && id.equals(that.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(namespace, id);
    }
}
