package hexcustomitems.region;

import org.bukkit.Location;

import java.util.Optional;

/**
 * Abstrahierte Region-Abfrage für die Guard-Schicht.
 * Rückgabe:
 * <ul>
 *   <li>{@code Optional.of(true)}  - eine Region erlaubt offensive Nutzung ausdrücklich</li>
 *   <li>{@code Optional.of(false)} - eine Region verbietet offensive Nutzung (Safezone)</li>
 *   <li>{@code Optional.empty()}   - keine Region-Information vorhanden</li>
 * </ul>
 */
public interface RegionQuery {
    Optional<Boolean> offensiveAllowed(Location location);
}
