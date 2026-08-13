package hexcustomitems.region;

import org.bukkit.plugin.Plugin;

/**
 * Wählt defensiv die passende {@link RegionQuery}: HexCore-basiert, falls HexCore
 * vorhanden ist, sonst ein No-Op. Die HexCore-Klasse wird nur berührt, wenn das
 * Plugin geladen ist - so bleibt HexCore eine reine soft-Dependency.
 */
public final class RegionGuardFactory {

    private RegionGuardFactory() {
    }

    public static RegionQuery create(Plugin plugin) {
        if (plugin.getServer().getPluginManager().getPlugin("HexCore") != null) {
            try {
                RegionQuery query = new HexCoreRegionQuery();
                plugin.getLogger().info("HexCore wykryte - integracja regionów aktywna.");
                return query;
            } catch (Throwable throwable) {
                plugin.getLogger().warning("Nie udało się włączyć integracji HexCore: " + throwable.getMessage());
            }
        }
        return new NoopRegionQuery();
    }
}
