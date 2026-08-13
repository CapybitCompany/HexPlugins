package hexcustomitems.region;

import hex.core.api.HexApi;
import hex.core.api.region.Region;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.plugin.RegisteredServiceProvider;

import java.util.Map;
import java.util.Optional;

/**
 * Optionale Anbindung an HexCore-Regionen. Diese Klasse referenziert HexCore-Typen
 * und darf daher NUR instanziiert werden, wenn HexCore vorhanden ist
 * (siehe RegionGuardFactory). Andernfalls droht ein NoClassDefFoundError.
 *
 * <p>Konvention für Safezones (HexCore-Regionen kennen kein festes PvP-Flag):
 * eine Region gilt als Schutzzone, wenn ihre Meta-Daten {@code pvp=false},
 * {@code safe=true} oder {@code safezone=true} enthalten.
 */
public final class HexCoreRegionQuery implements RegionQuery {

    @Override
    public Optional<Boolean> offensiveAllowed(Location location) {
        if (location == null) {
            return Optional.empty();
        }
        RegisteredServiceProvider<HexApi> registration =
                Bukkit.getServicesManager().getRegistration(HexApi.class);
        if (registration == null) {
            return Optional.empty();
        }

        HexApi api = registration.getProvider();
        for (Region region : api.regions().listAll()) {
            if (region.contains(location) && isSafezone(region.meta())) {
                return Optional.of(false);
            }
        }
        return Optional.empty();
    }

    private boolean isSafezone(Map<String, String> meta) {
        if (meta == null || meta.isEmpty()) {
            return false;
        }
        return "false".equalsIgnoreCase(meta.get("pvp"))
                || "true".equalsIgnoreCase(meta.get("safe"))
                || "true".equalsIgnoreCase(meta.get("safezone"));
    }
}
