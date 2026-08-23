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

    /**
     * Administrative access bypass. This is deliberately separate from membership identity:
     * an operator may act like a normal member inside an ACTIVE town without becoming a member.
     */
    default boolean hasAdminBypass(UUID playerId) { return false; }

    /**
     * Access-oriented membership check. Third-party implementations retain legacy semantics by
     * default; HexTowns extends it with hextowns.admin.bypass.
     */
    default boolean canActAsMember(UUID playerId, UUID townId) { return isMember(playerId, townId); }

    boolean isProtected(Location loc);

    boolean canBuild(Player player, Location loc);

    /**
     * Returns true only for the small protected geometry of an active town heart
     * (3x3x3 around the heart plus its 3x3 bedrock foundation).
     */
    default boolean isHeartProtected(Location loc) { return false; }

    boolean isPvpAllowed(Location loc);

    TownBoundItems townBoundItems();

    boolean can(UUID playerId, UUID townId, TownPermission permission);

    Map<TownPermission, Boolean> permissionsOf(UUID playerId, UUID townId);

    java.util.concurrent.CompletableFuture<Boolean> setPermission(UUID ownerId, UUID townId, UUID memberId, TownPermission permission, boolean allowed);

    void audit(UUID townId, UUID playerId, String action, String data);

    void forEachTown(Consumer<Town> visitor, int batchSize);

    Page<Town> listPage(String afterTownId, int limit);

    int countTowns();

    int memberCount(UUID townId);

    int growthPoints(UUID townId);

    void addGrowthPoints(UUID townId, int delta, String source);

    String getMeta(UUID townId, String key, String def);

    int getMetaInt(UUID townId, String key, int def);

    void setMeta(UUID townId, String key, String value);

    Map<String, String> getMetaPrefix(UUID townId, String keyPrefix);

    TownDataNamespace dataNamespace(Plugin owner, String namespace, TownDataResetHandler onReset);

    /**
     * Full-context cleanup registration. The default adapter keeps third-party TownsApi
     * implementations source-compatible, while HexTowns overrides it to provide the durable
     * cleanup snapshot (world/chunks/internal id/owner).
     */
    default TownDataNamespace dataNamespaceV2(Plugin owner, String namespace, TownDataResetHandlerV2 onReset) {
        return dataNamespace(owner, namespace, (townId, members) -> onReset.purgeTown(TownPurgeContext.compatibility(townId, members)));
    }

    /** Opens the canonical HexTowns city guide without dispatching a command. */
    default void openTownGuide(Player player) { }

    void registerListener(TownsListener listener);
}