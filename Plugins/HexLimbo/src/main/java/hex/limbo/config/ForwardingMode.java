package hex.limbo.config;

import java.util.Locale;

/**
 * Velocity's player-info-forwarding mode as seen from the backend side. Only used by HexLimbo's
 * internal void limbo to decide whether to perform the Velocity {@code velocity:player_info}
 * Login-Plugin-Request handshake before sending Login Success.
 */
public enum ForwardingMode {
    /** No forwarding handshake; the backend trusts the username in Login Start. */
    NONE,
    /** BungeeCord-compatible legacy forwarding. Treated identically to NONE by the limbo. */
    LEGACY,
    /** Velocity modern forwarding: backend must send a velocity:player_info plugin request. */
    MODERN;

    public static ForwardingMode parse(String raw, ForwardingMode fallback) {
        if (raw == null) {
            return fallback;
        }
        String normalized = raw.trim().toUpperCase(Locale.ROOT);
        for (ForwardingMode mode : values()) {
            if (mode.name().equals(normalized)) {
                return mode;
            }
        }
        return fallback;
    }
}
