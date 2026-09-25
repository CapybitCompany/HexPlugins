package hexposterunki.config;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Result of validating outpost definitions: what was accepted, what was skipped and why. */
public final class ValidationReport {

    private final List<String> accepted = new ArrayList<>();
    private final Map<String, String> skipped = new LinkedHashMap<>();
    private final List<String> warnings = new ArrayList<>();

    public void accept(String outpostId) {
        accepted.add(outpostId);
    }

    public void skip(String outpostId, String reason) {
        skipped.put(outpostId, reason);
    }

    public void warn(String message) {
        warnings.add(message);
    }

    public List<String> accepted() {
        return List.copyOf(accepted);
    }

    public Map<String, String> skipped() {
        return Map.copyOf(skipped);
    }

    public List<String> warnings() {
        return List.copyOf(warnings);
    }

    public boolean hasValidOutposts() {
        return !accepted.isEmpty();
    }
}
