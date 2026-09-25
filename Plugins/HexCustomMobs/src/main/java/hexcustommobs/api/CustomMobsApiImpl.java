package hexcustommobs.api;

import hexcustommobs.config.HexCustomMobsConfig;
import hexcustommobs.service.CustomMobService;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;

import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Default {@link CustomMobsApi} provider. It only delegates to {@link CustomMobService};
 * no internal state is exposed to consumers.
 */
public final class CustomMobsApiImpl implements CustomMobsApi {

    private final CustomMobService customMobService;
    private final Supplier<HexCustomMobsConfig> configSupplier;

    public CustomMobsApiImpl(CustomMobService customMobService, Supplier<HexCustomMobsConfig> configSupplier) {
        this.customMobService = Objects.requireNonNull(customMobService, "customMobService");
        this.configSupplier = Objects.requireNonNull(configSupplier, "configSupplier");
    }

    @Override
    public String apiVersion() {
        return API_VERSION;
    }

    @Override
    public boolean hasMob(String mobId) {
        return normalized(mobId).map(id -> configSupplier.get().mobs().containsKey(id)).orElse(false);
    }

    @Override
    public Set<String> mobIds() {
        return Set.copyOf(configSupplier.get().mobs().keySet());
    }

    @Override
    public Optional<LivingEntity> spawn(Location location, String mobId) {
        requireMainThread();
        if (location == null || location.getWorld() == null) {
            return Optional.empty();
        }
        return normalized(mobId)
                .map(id -> customMobService.spawnCustomMob(location, id));
    }

    @Override
    public Optional<String> mobIdOf(Entity entity) {
        if (!(entity instanceof LivingEntity living)) {
            return Optional.empty();
        }
        return customMobService.getCustomMobId(living);
    }

    private static Optional<String> normalized(String mobId) {
        if (mobId == null || mobId.isBlank()) {
            return Optional.empty();
        }
        return Optional.of(mobId.trim().toLowerCase(Locale.ROOT));
    }

    private static void requireMainThread() {
        if (!Bukkit.isPrimaryThread()) {
            throw new IllegalStateException("CustomMobsApi.spawn must be called from the server main thread");
        }
    }
}
