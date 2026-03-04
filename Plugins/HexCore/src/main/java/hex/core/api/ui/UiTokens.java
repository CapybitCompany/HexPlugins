package hex.core.api.ui;

import java.util.HashMap;
import java.util.Map;

/**
 * Simple placeholder container used when rendering UI templates.
 * Maps token names to string values.
 */
public final class UiTokens {
    private final Map<String, String> tokens = new HashMap<>();

    public static UiTokens of(String k, String v) {
        return new UiTokens().put(k, v);
    }

    /** Adds or replaces a placeholder value. */
    public UiTokens put(String k, String v) {
        tokens.put(k, v);
        return this;
    }

    public Map<String, String> asMap() {
        return tokens;
    }

}
