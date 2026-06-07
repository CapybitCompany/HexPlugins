package hex.towns.api;

import hex.towns.model.Town;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;

public interface TownsApi {
    Optional<Town> findTown(UUID townId);

    Optional<UUID> townIdAt(int chunkX, int chunkZ, String world);

    Optional<Town> townAt(Chunk chunk);

    Optional<Town> townAt(Location loc);

    Optional<UUID> townIdOf(UUID playerId);

    boolean isMember(UUID playerId, UUID townId);

    boolean isOwner(UUID playerId, UUID townId);

    boolean isProtected(Location loc);

    boolean canBuild(Player player, Location loc);

    void forEachTown(Consumer<Town> visitor, int batchSize);

    Page<Town> listPage(String afterTownId, int limit);

    int countTowns();

    int growthPoints(UUID townId);

    void addGrowthPoints(UUID townId, int delta, String source);

    String getMeta(UUID townId, String key, String def);

    int getMetaInt(UUID townId, String key, int def);

    void setMeta(UUID townId, String key, String value);

    Map<String, String> getMetaPrefix(UUID townId, String keyPrefix);

    TownDataNamespace dataNamespace(Plugin owner, String namespace, TownDataResetHandler onReset);

    void registerListener(TownsListener listener);
}