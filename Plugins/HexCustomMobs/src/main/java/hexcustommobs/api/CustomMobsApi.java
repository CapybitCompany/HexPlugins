package hexcustommobs.api;

import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;

import java.util.Optional;
import java.util.Set;

/**
 * Public, stable API of HexCustomMobs published through the Bukkit {@code ServicesManager}.
 *
 * <p>Consumers should obtain it with:
 * <pre>{@code
 * var reg = Bukkit.getServicesManager().getRegistration(CustomMobsApi.class);
 * CustomMobsApi mobs = reg == null ? null : reg.getProvider();
 * }</pre>
 *
 * <p>Every method that touches entities or worlds must be called from the server main thread.
 */
public interface CustomMobsApi {

    /**
     * Semantic version of this API contract. Consumers can pin against the major part to
     * detect incompatible changes early instead of failing at an arbitrary call site.
     */
    String API_VERSION = "1.0";

    /** @return {@link #API_VERSION} of the provider that is actually installed. */
    String apiVersion();

    /** @return true when a mob template with this id exists in the current configuration. */
    boolean hasMob(String mobId);

    /** @return all configured mob template ids (lower-case, immutable snapshot). */
    Set<String> mobIds();

    /**
     * Spawns a configured custom mob at the given location on the main thread.
     *
     * @return the spawned entity, or {@link Optional#empty()} when the id or world is unusable.
     */
    Optional<LivingEntity> spawn(Location location, String mobId);

    /** @return the custom mob id carried by this entity, if it is a HexCustomMobs mob. */
    Optional<String> mobIdOf(Entity entity);

    /** Convenience shortcut for {@code mobIdOf(entity).isPresent()}. */
    default boolean isCustomMob(Entity entity) {
        return mobIdOf(entity).isPresent();
    }
}
