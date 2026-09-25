package hexposterunki.config;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Immutable set of validated outposts plus the report explaining what was rejected. */
public record OutpostCatalog(Map<String, OutpostDefinition> outposts, ValidationReport report) {

    public OutpostCatalog {
        outposts = outposts == null ? Map.of() : Map.copyOf(new LinkedHashMap<>(outposts));
    }

    public static OutpostCatalog empty() {
        return new OutpostCatalog(Map.of(), new ValidationReport());
    }

    public Optional<OutpostDefinition> find(String id) {
        return id == null ? Optional.empty() : Optional.ofNullable(outposts.get(id));
    }

    public List<OutpostDefinition> all() {
        return List.copyOf(outposts.values());
    }

    public boolean isEmpty() {
        return outposts.isEmpty();
    }
}
