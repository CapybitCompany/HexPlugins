package hex.towns.service;

import hex.towns.api.Page;
import hex.towns.api.TownDataNamespace;
import hex.towns.api.TownBoundItems;
import hex.towns.api.TownPermission;
import hex.towns.api.TownDataResetHandler;
import hex.towns.api.TownDataResetHandlerV2;
import hex.towns.api.TownsApi;
import hex.towns.api.TownsListener;
import hex.towns.model.Town;
import hex.towns.guide.TownGuideService;
import hex.towns.heart.TownHeartService;
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
    private final TownGuideService guideService;
    private final TownHeartService heartService;

    public TownsApiImpl(TownsService service, TownDataRegistry dataRegistry, TownGuideService guideService, TownHeartService heartService) {
        this.service = service;
        this.dataRegistry = dataRegistry;
        this.guideService = guideService;
        this.heartService = heartService;
    }

    @Override public Optional<Town> findTown(UUID townId) { return service.findTown(townId); }
    @Override public Optional<UUID> townIdAt(int chunkX, int chunkZ, String world) { return service.townIdAt(world, chunkX, chunkZ); }
    @Override public Optional<Town> townAt(Chunk chunk) { return service.townAt(chunk.getWorld().getName(), chunk.getX(), chunk.getZ()); }
    @Override public Optional<Town> townAt(Location loc) { return service.townAt(loc); }
    @Override public Optional<UUID> townIdOf(UUID playerId) { return service.townIdOf(playerId); }
    @Override public boolean isMember(UUID playerId, UUID townId) { return service.isMember(playerId, townId); }
    @Override public boolean isOwner(UUID playerId, UUID townId) { return service.isOwner(playerId, townId); }
    @Override public boolean hasAdminBypass(UUID playerId) { return service.hasAdminBypass(playerId); }
    @Override public boolean canActAsMember(UUID playerId, UUID townId) { return service.canActAsMember(playerId, townId); }
    @Override public boolean isProtected(Location loc) { return service.protectedTownAt(loc).isPresent(); }
    @Override public boolean canBuild(Player player, Location loc) { return service.canBuild(player, loc); }
    @Override public boolean isHeartProtected(Location loc) { return heartService != null && heartService.protectedHeartAt(loc).isPresent(); }
    @Override public boolean isPvpAllowed(Location loc) { return service.isPvpAllowed(loc); }
    @Override public TownBoundItems townBoundItems() { return service.townBoundItems(); }
    @Override public boolean can(UUID playerId, UUID townId, TownPermission permission) { return service.can(playerId, townId, permission); }
    @Override public Map<TownPermission, Boolean> permissionsOf(UUID playerId, UUID townId) { return service.permissionsOf(playerId, townId); }
    @Override public java.util.concurrent.CompletableFuture<Boolean> setPermission(UUID ownerId, UUID townId, UUID memberId, TownPermission permission, boolean allowed) { return service.setPermission(ownerId, townId, memberId, permission, allowed); }
    @Override public void audit(UUID townId, UUID playerId, String action, String data) { service.audit(townId, playerId, action, data); }
    @Override public void forEachTown(Consumer<Town> visitor, int batchSize) { service.forEachTown(visitor, batchSize); }
    @Override public Page<Town> listPage(String afterTownId, int limit) { return service.listPage(afterTownId, limit); }
    @Override public int countTowns() { return service.countTowns(); }
    @Override public int memberCount(UUID townId) {
        return service.findTown(townId).map(town -> {
            var members = service.membersOf(town);
            return Math.max(1, members.size() + (members.contains(town.ownerId()) ? 0 : 1));
        }).orElse(1);
    }
    @Override public int growthPoints(UUID townId) { return service.growthPoints(townId); }
    @Override public void addGrowthPoints(UUID townId, int delta, String source) { service.addGrowthPoints(townId, delta, source); }
    @Override public String getMeta(UUID townId, String key, String def) { return service.getMeta(townId, key, def); }
    @Override public int getMetaInt(UUID townId, String key, int def) { return service.getMetaInt(townId, key, def); }
    @Override public void setMeta(UUID townId, String key, String value) { service.setMeta(townId, key, value); }
    @Override public Map<String, String> getMetaPrefix(UUID townId, String keyPrefix) { return service.getMetaPrefix(townId, keyPrefix); }
    @Override public void openTownGuide(Player player) { if (guideService != null) guideService.open(player); }
    @Override public TownDataNamespace dataNamespace(Plugin owner, String namespace, TownDataResetHandler onReset) { return dataRegistry.register(owner, namespace, onReset); }
    @Override public TownDataNamespace dataNamespaceV2(Plugin owner, String namespace, TownDataResetHandlerV2 onReset) { return dataRegistry.registerV2(owner, namespace, onReset); }
    @Override public void registerListener(TownsListener listener) { service.registerListener(listener); }
}