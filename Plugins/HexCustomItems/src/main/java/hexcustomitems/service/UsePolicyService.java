package hexcustomitems.service;

import hexcustomitems.config.HexCustomItemsConfig;
import hexcustomitems.region.RegionQuery;
import org.bukkit.entity.Player;

import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Guard-/Policy-Schicht für offensive Item-Aktionen.
 *
 * <p>Auch wenn die Default-Config keine offensiven Items enthält, respektiert diese
 * Schicht künftige {@code offensive}-Aktionen anhand von Welt-PvP-Flag und optionalen
 * HexCore-Regionen. Reihenfolge:
 * <ol>
 *   <li>Region-Awareness deaktiviert -&gt; immer erlaubt</li>
 *   <li>{@code respect-pvp} und Welt-PvP aus -&gt; verboten</li>
 *   <li>Region liefert klare Entscheidung -&gt; diese gilt</li>
 *   <li>keine Region-Info -&gt; {@code fail-closed} entscheidet</li>
 * </ol>
 */
public final class UsePolicyService {

    private final Supplier<HexCustomItemsConfig> configSupplier;
    private final RegionQuery regionQuery;

    public UsePolicyService(Supplier<HexCustomItemsConfig> configSupplier, RegionQuery regionQuery) {
        this.configSupplier = Objects.requireNonNull(configSupplier, "configSupplier");
        this.regionQuery = Objects.requireNonNull(regionQuery, "regionQuery");
    }

    public boolean allowsOffensive(Player player) {
        HexCustomItemsConfig.RegionAwareness policy = configSupplier.get().regionAwareness();
        if (!policy.enabled()) {
            return true;
        }
        if (policy.respectPvp() && !worldPvpEnabled(player)) {
            return false;
        }
        Optional<Boolean> regionDecision = regionQuery.offensiveAllowed(player.getLocation());
        if (regionDecision.isPresent()) {
            return regionDecision.get();
        }
        return !policy.failClosed();
    }

    // World#getPVP() ist seit 1.21.9 als deprecated markiert, es gibt aber keinen
    // öffentlichen Ersatz für den Welt-PvP-Status. Bewusst hier gekapselt.
    @SuppressWarnings("deprecation")
    private boolean worldPvpEnabled(Player player) {
        return player.getWorld().getPVP();
    }
}
