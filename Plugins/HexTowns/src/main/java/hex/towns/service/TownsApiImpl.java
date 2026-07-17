package hex.towns.service;

import hex.towns.api.Page;
import hex.towns.api.TownDataNamespace;
import hex.towns.api.TownDataResetHandler;
import hex.towns.api.TownsApi;
import hex.towns.api.TownsListener;
import hex.towns.model.Town;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;

public final class TownsApiImpl implements TownsApi {
    private final TownsService service;
    private final TownDataRegistry dataRegistry;

    public TownsApiImpl(TownsService service, TownDataRegistry dataRegistry) {
        this.service = service;
        this.dataRegistry = dataRegistry;
    }

    @Override public Optional<Town> findTown(UUID townId) { return service.findTown(townId); }
    @Override public Optional<UUID> townIdAt(int chunkX, int chunkZ, String world) { return service.townIdAt(world, chunkX, chunkZ); }
    @Override public Optional<Town> townAt(Chunk chunk) { return service.townAt(chunk.getWorld().getName(), chunk.getX(), chunk.getZ()); }
    @Override public Optional<Town> townAt(Location loc) { return service.townAt(loc); }
    @Override public Optional<UUID> townIdOf(UUID playerId) { return service.townIdOf(playerId); }
    @Override public boolean isMember(UUID playerId, UUID townId) { return service.isMember(playerId, townId); }
    @Override public boolean isOwner(UUID playerId, UUID townId) { return service.isOwner(playerId, townId); }
    @Override public boolean isProtected(Location loc) { return service.townAt(loc).isPresent(); }
    @Override public boolean canBuild(Player player, Location loc) { return service.canBuild(player, loc); }
    @Override public boolean isPvpAllowed(Location loc) { return service.isPvpAllowed(loc); }
    @Override public void forEachTown(Consumer<Town> visitor, int batchSize) { service.forEachTown(visitor, batchSize); }
    @Override public Page<Town> listPage(String afterTownId, int limit) { return service.listPage(afterTownId, limit); }
    @Override public int countTowns() { return service.countTowns(); }
    @Override public int growthPoints(UUID townId) { return service.growthPoints(townId); }
    @Override public void addGrowthPoints(UUID townId, int delta, String source) { service.addGrowthPoints(townId, delta, source); }
    @Override public String getMeta(UUID townId, String key, String def) { return service.getMeta(townId, key, def); }
    @Override public int getMetaInt(UUID townId, String key, int def) { return service.getMetaInt(townId, key, def); }
    @Override public void setMeta(UUID townId, String key, String value) { service.setMeta(townId, key, value); }
    @Override public Map<String, String> getMetaPrefix(UUID townId, String keyPrefix) { return service.getMetaPrefix(townId, keyPrefix); }
    @Override public TownDataNamespace dataNamespace(Plugin owner, String namespace, TownDataResetHandler onReset) { return dataRegistry.register(owner, namespace, onReset); }
    @Override public void registerListener(TownsListener listener) { service.registerListener(listener); }
}