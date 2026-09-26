package hexpvpsmp.config;

import org.bukkit.Material;

/**
 * Client-side barrier shown when a combat-tagged player is blocked from
 * entering a spawn safezone. The barrier is drawn with fake block changes
 * (never real blocks) along the nearest safezone wall and reverts after
 * {@code durationTicks}.
 *
 * <ul>
 *   <li>{@code material}: the fake block sent to the client (e.g. red glass).</li>
 *   <li>{@code durationTicks}: how long the fake wall stays before reverting.</li>
 *   <li>{@code radius}: how many blocks along the wall to each side of the
 *       player the wall extends (line length = 2*radius+1).</li>
 *   <li>{@code height}: how many blocks tall the wall is, starting at the
 *       player's feet.</li>
 * </ul>
 */
public record BarrierConfig(
        boolean enabled,
        Material material,
        int durationTicks,
        int radius,
        int height
) {
    public BarrierConfig {
        material = material == null ? Material.RED_STAINED_GLASS : material;
        durationTicks = Math.max(1, durationTicks);
        radius = Math.max(0, radius);
        height = Math.max(1, height);
    }

    public static BarrierConfig defaults() {
        return new BarrierConfig(true, Material.RED_STAINED_GLASS, 40, 4, 3);
    }
}
