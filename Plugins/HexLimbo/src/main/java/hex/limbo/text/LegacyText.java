package hex.limbo.text;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;

/**
 * The single place where HexLimbo turns configured legacy colour codes into Adventure components.
 *
 * <p>Every player-facing string in {@code messages.yml} may use the classic Minecraft codes
 * ({@code &6} gold, {@code &7} grey, {@code &f} white, {@code &a} green, {@code &e} yellow,
 * {@code &c} red, {@code &8} dark grey, {@code &b} aqua) plus the style codes ({@code &l} bold,
 * {@code &o} italic, {@code &n} underline, {@code &r} reset) and {@code &#rrggbb} hex colours.
 *
 * <p>Legacy codes – not MiniMessage – are used on purpose: the prompts contain literal
 * placeholders such as {@code <hasło>} which MiniMessage would try to interpret as a tag and
 * silently swallow. The legacy serializer only ever reacts to {@code &}, so angle brackets survive
 * verbatim.
 *
 * <p>No Bukkit/{@code ChatColor} API is involved; this is pure Adventure and therefore works on
 * the Velocity proxy.
 */
public final class LegacyText {

    private static final LegacyComponentSerializer SERIALIZER = LegacyComponentSerializer.builder()
            .character(LegacyComponentSerializer.AMPERSAND_CHAR)
            .hexCharacter('#')
            .hexColors()
            .build();

    private LegacyText() {}

    /**
     * Parses a legacy-coded string into a component. {@code null} and empty input yield
     * {@link Component#empty()} so callers never have to null-check before rendering.
     */
    public static Component parse(String legacy) {
        if (legacy == null || legacy.isEmpty()) {
            return Component.empty();
        }
        return SERIALIZER.deserialize(legacy);
    }

    /** Renders a component back to a legacy-coded string. Used by tests and diagnostics. */
    public static String serialize(Component component) {
        return component == null ? "" : SERIALIZER.serialize(component);
    }
}
