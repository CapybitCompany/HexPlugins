package hex.towns.heart;

import hex.towns.model.Town;
import hex.towns.model.TownStatus;
import hex.towns.service.TownsService;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.world.ChunkLoadEvent;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Reconciles persistent display entities with the current HexTowns domain state.
 * It never loads chunks for scanning: startup scans loaded chunks only and future
 * chunks are checked lazily from ChunkLoadEvent.
 */
public final class TownHeartReconciliationService implements Listener {
    private static final double VALID_HEART_RADIUS_SQUARED = 36.0D;
    private static final int FOUNDATION_SEARCH_RADIUS = 16;
    private static final int FOUNDATION_VERTICAL_RADIUS = 8;

    private final TownsService townsService;
    private final TownHeartService heartService;
    private final TownHeartRenderer renderer;
    private final Set<UUID> malformedAudited = new HashSet<>();
    private final Set<String> duplicateAudited = new HashSet<>();
    private volatile boolean ready;

    public TownHeartReconciliationService(TownsService townsService, TownHeartService heartService, TownHeartRenderer renderer) {
        this.townsService = townsService;
        this.heartService = heartService;
        this.renderer = renderer;
    }

    public boolean isReady() {
        return ready;
    }

    /** Activates chunk reconciliation only after the database state is loaded. */
    public HeartReconciliationReport activateAndReconcile() {
        this.ready = true;
        return reconcileLoadedChunks();
    }

    public HeartReconciliationReport reconcileLoadedChunks() {
        if (!ready) return HeartReconciliationReport.empty();
        renderer.clearRegistry();
        Scope scope = loadedScope();
        Analysis analysis = analyze(scope.entities(), scope.chunksScanned());
        Map<GroupWork, Integer> removed = reconcile(analysis);
        return report(analysis, removed);
    }

    public HeartReconciliationReport scanLoaded() {
        if (!ready) return HeartReconciliationReport.empty();
        Scope scope = loadedScope();
        return report(analyze(scope.entities(), scope.chunksScanned()), Map.of());
    }

    public HeartReconciliationReport scanNearby(Location center, double radius) {
        if (!ready || center == null || center.getWorld() == null) return HeartReconciliationReport.empty();
        double safeRadius = Math.max(1.0D, Math.min(radius, 256.0D));
        Scope scope = nearbyScope(center, safeRadius);
        return report(analyze(scope.entities(), scope.chunksScanned()), Map.of());
    }

    public HeartPurgeReport purgeOrphansNearby(Location center, double radius, boolean dryRun, UUID actor) {
        if (!ready || center == null || center.getWorld() == null) {
            return new HeartPurgeReport(null, 0, 0, dryRun, HeartReconciliationReport.empty());
        }
        Scope scope = nearbyScope(center, Math.max(1.0D, Math.min(radius, 256.0D)));
        Analysis analysis = analyze(scope.entities(), scope.chunksScanned());
        return purgeOrphans(analysis, dryRun, actor);
    }

    public HeartPurgeReport purgeOrphansLoaded(boolean dryRun, UUID actor) {
        if (!ready) return new HeartPurgeReport(null, 0, 0, dryRun, HeartReconciliationReport.empty());
        Scope scope = loadedScope();
        return purgeOrphans(analyze(scope.entities(), scope.chunksScanned()), dryRun, actor);
    }

    public HeartPurgeReport purgeVisual(UUID townId, boolean dryRun, UUID actor) {
        if (!ready || townId == null) {
            return new HeartPurgeReport(townId, 0, 0, dryRun, HeartReconciliationReport.empty());
        }
        Scope scope = loadedScope();
        Analysis analysis = analyze(scope.entities(), scope.chunksScanned());
        List<Entity> matches = new ArrayList<>();
        for (Entity entity : scope.entities()) {
            String raw = renderer.rawTownId(entity);
            String part = renderer.partId(entity);
            if (raw == null || part == null || part.isBlank()) continue;
            try {
                if (townId.equals(UUID.fromString(raw))) matches.add(entity);
            } catch (IllegalArgumentException ignored) {
            }
        }

        int removed = 0;
        if (!dryRun) {
            for (Entity entity : matches) {
                if (!entity.isValid()) continue;
                renderer.unregister(entity);
                entity.remove();
                removed++;
            }
            townsService.audit(townId, actor, "HEART_ADMIN_PURGE_VISUAL", auditData(anchor(matches), matches.size(), removed, "explicit purge-visual", false));
        }
        return new HeartPurgeReport(townId, matches.size(), removed, dryRun, report(analysis, Map.of()));
    }

    public HeartFoundationReport inspectOrCleanupFoundation(Location origin, boolean confirm, UUID actor) {
        if (!ready || origin == null || origin.getWorld() == null) {
            return HeartFoundationReport.notFound("Heart reconciliation is not ready.");
        }
        FoundationCandidate candidate = findNearestFoundation(origin);
        if (candidate == null) {
            return HeartFoundationReport.notFound("Nie znaleziono pelnego, odizolowanego fundamentu 9x9 BEDROCK w poblizu.");
        }

        Location center = new Location(candidate.world(), candidate.x() + 0.5D, candidate.y(), candidate.z() + 0.5D);
        boolean protectedByHeart = heartService.protectedHeartAt(center).isPresent();
        boolean protectedByTown = townsService.protectedTownAt(center).isPresent();
        boolean protectedActive = protectedByHeart || protectedByTown;
        if (protectedActive) {
            return new HeartFoundationReport(true, true, false, candidate.world().getName(), candidate.x(), candidate.y(), candidate.z(), 81,
                    "Fundament nalezy do aktywnego lub niszczonego miasta i nie zostal usuniety.");
        }
        if (!confirm) {
            return new HeartFoundationReport(true, false, false, candidate.world().getName(), candidate.x(), candidate.y(), candidate.z(), 81,
                    "Wykryto legacy fundament 9x9. Brak danych restoration; uzyj confirm, aby zastapic 81 blokow BEDROCK powietrzem.");
        }
        if (!isExactFoundation(candidate.world(), candidate.x(), candidate.y(), candidate.z())) {
            return new HeartFoundationReport(true, false, false, candidate.world().getName(), candidate.x(), candidate.y(), candidate.z(), 0,
                    "Fundament zmienil sie od czasu skanu; operacja anulowana.");
        }
        for (int x = candidate.x() - 4; x <= candidate.x() + 4; x++) {
            for (int z = candidate.z() - 4; z <= candidate.z() + 4; z++) {
                candidate.world().getBlockAt(x, candidate.y(), z).setType(Material.AIR, false);
            }
        }
        townsService.audit(null, actor, "HEART_FOUNDATION_ADMIN_PURGE",
                "world=" + candidate.world().getName() + ",x=" + candidate.x() + ",y=" + candidate.y() + ",z=" + candidate.z() + ",count=81");
        return new HeartFoundationReport(true, false, true, candidate.world().getName(), candidate.x(), candidate.y(), candidate.z(), 81,
                "Usunieto 81 blokow legacy fundamentu. Oryginalnego terenu nie mozna bylo odtworzyc.");
    }

    @EventHandler
    public void onChunkLoad(ChunkLoadEvent event) {
        if (!ready) return;
        Analysis analysis = analyze(List.of(event.getChunk().getEntities()), 1);
        reconcile(analysis);
    }

    private HeartPurgeReport purgeOrphans(Analysis analysis, boolean dryRun, UUID actor) {
        int matched = 0;
        int removed = 0;
        Map<GroupWork, Integer> removedByGroup = new HashMap<>();
        for (GroupWork group : analysis.groups()) {
            if (group.status() != HeartVisualStatus.ORPHAN_TOWN_MISSING && group.status() != HeartVisualStatus.ORPHAN_HEART_INACTIVE) continue;
            matched += group.entities().size();
            if (dryRun) continue;
            int groupRemoved = removeEntities(group.entities());
            removed += groupRemoved;
            removedByGroup.put(group, groupRemoved);
            townsService.audit(group.townId(), actor, "HEART_ORPHAN_PURGED",
                    auditData(group.anchor(), group.entities().size(), groupRemoved, group.reason(), false));
        }
        return new HeartPurgeReport(null, matched, removed, dryRun, report(analysis, removedByGroup));
    }

    private Map<GroupWork, Integer> reconcile(Analysis analysis) {
        Map<GroupWork, Integer> removedByGroup = new HashMap<>();
        for (GroupWork group : analysis.groups()) {
            switch (group.status()) {
                case VALID -> register(group.keep());
                case ORPHAN_TOWN_MISSING, ORPHAN_HEART_INACTIVE -> {
                    townsService.audit(group.townId(), null, "HEART_ORPHAN_DETECTED",
                            auditData(group.anchor(), group.entities().size(), 0, group.reason(), false));
                    int removed = removeEntities(group.entities());
                    removedByGroup.put(group, removed);
                    townsService.audit(group.townId(), null, "HEART_ORPHAN_PURGED",
                            auditData(group.anchor(), group.entities().size(), removed, group.reason(), false));
                }
                case DUPLICATE -> {
                    register(group.keep());
                    String fingerprint = duplicateFingerprint(group);
                    if (duplicateAudited.add(fingerprint)) {
                        townsService.audit(group.townId(), null, "HEART_DUPLICATE_DETECTED",
                                auditData(group.anchor(), group.entities().size(), 0, group.reason(), false));
                    }
                    int removed = removeEntities(group.removable());
                    removedByGroup.put(group, removed);
                    if (removed > 0) {
                        townsService.audit(group.townId(), null, "HEART_DUPLICATE_PURGED",
                                auditData(group.anchor(), group.entities().size(), removed, group.reason(), false));
                    }
                }
                case MALFORMED -> {
                    for (Entity entity : group.entities()) {
                        if (!malformedAudited.add(entity.getUniqueId())) continue;
                        townsService.audit(group.townId(), null, "HEART_MALFORMED_DETECTED",
                                auditData(entity.getLocation(), 1, 0, group.reason(), false));
                    }
                }
            }
        }
        return removedByGroup;
    }

    private Analysis analyze(Collection<Entity> entities, int chunksScanned) {
        Map<UUID, List<Candidate>> byTown = new LinkedHashMap<>();
        List<GroupWork> groups = new ArrayList<>();
        int heartEntities = 0;

        for (Entity entity : entities) {
            if (entity == null || !renderer.hasAnyHeartMarker(entity)) continue;
            heartEntities++;
            String rawTown = renderer.rawTownId(entity);
            String part = renderer.partId(entity);
            String malformed = malformedReason(entity, rawTown, part);
            if (malformed != null) {
                UUID parsed = parseUuid(rawTown);
                groups.add(new GroupWork(parsed, rawTown, HeartVisualStatus.MALFORMED, malformed, entity.getLocation(),
                        List.of(entity), List.of(), List.of()));
                continue;
            }
            UUID townId = UUID.fromString(rawTown);
            byTown.computeIfAbsent(townId, ignored -> new ArrayList<>()).add(new Candidate(entity, townId, rawTown, part));
        }

        for (Map.Entry<UUID, List<Candidate>> entry : byTown.entrySet()) {
            groups.add(classifyTown(entry.getKey(), entry.getValue()));
        }
        return new Analysis(chunksScanned, heartEntities, List.copyOf(groups));
    }

    private GroupWork classifyTown(UUID townId, List<Candidate> candidates) {
        List<Entity> all = candidates.stream().map(Candidate::entity).toList();
        Location anchor = anchor(all);
        Town town = townsService.findTownIncludingDestroying(townId).orElse(null);
        if (town == null) {
            return new GroupWork(townId, townId.toString(), HeartVisualStatus.ORPHAN_TOWN_MISSING,
                    "town UUID does not exist", anchor, all, List.of(), all);
        }

        if (town.status() == TownStatus.DESTROYING) {
            return new GroupWork(townId, townId.toString(), HeartVisualStatus.VALID,
                    "town is DESTROYING; lifecycle cleanup owns visuals", anchor, all, all, List.of());
        }

        Optional<TownHeartLocation> heartOpt = heartService.heartOf(townId);
        if (heartOpt.isEmpty()) {
            return new GroupWork(townId, townId.toString(), HeartVisualStatus.ORPHAN_HEART_INACTIVE,
                    "town exists but heart.active is false/missing", anchor, all, List.of(), all);
        }

        Location expected = heartOpt.get().toLocation();
        if (expected == null || expected.getWorld() == null) {
            return new GroupWork(townId, townId.toString(), HeartVisualStatus.MALFORMED,
                    "active heart world is unavailable", anchor, all, all, List.of());
        }

        List<Candidate> near = new ArrayList<>();
        List<Entity> removable = new ArrayList<>();
        for (Candidate candidate : candidates) {
            if (sameWorld(candidate.entity().getLocation(), expected)
                    && candidate.entity().getLocation().distanceSquared(expected) <= VALID_HEART_RADIUS_SQUARED) {
                near.add(candidate);
            } else {
                removable.add(candidate.entity());
            }
        }

        List<Entity> keep = new ArrayList<>();
        Map<String, List<Candidate>> byPart = new LinkedHashMap<>();
        for (Candidate candidate : near) byPart.computeIfAbsent(candidate.part(), ignored -> new ArrayList<>()).add(candidate);
        for (List<Candidate> samePart : byPart.values()) {
            samePart.sort(Comparator.comparingDouble(c -> c.entity().getLocation().distanceSquared(expected)));
            keep.add(samePart.get(0).entity());
            for (int i = 1; i < samePart.size(); i++) removable.add(samePart.get(i).entity());
        }

        if (!removable.isEmpty()) {
            return new GroupWork(townId, townId.toString(), HeartVisualStatus.DUPLICATE,
                    "duplicate/stale heart visual entities=" + removable.size(), anchor, all, List.copyOf(keep), List.copyOf(removable));
        }
        return new GroupWork(townId, townId.toString(), HeartVisualStatus.VALID,
                "active heart visual", anchor, all, List.copyOf(keep), List.of());
    }

    private String malformedReason(Entity entity, String rawTown, String part) {
        if (rawTown == null || rawTown.isBlank()) return "missing town_heart_visual_town";
        if (part == null || part.isBlank()) return "missing town_heart_visual_part";
        if (parseUuid(rawTown) == null) return "invalid town UUID: " + rawTown;
        if (!renderer.isKnownPart(part)) return "unknown heart visual part: " + part;
        if (!renderer.matchesExpectedEntityType(entity, part)) return "wrong entity type for heart part " + part;
        return null;
    }

    private HeartReconciliationReport report(Analysis analysis, Map<GroupWork, Integer> removedByGroup) {
        int valid = 0;
        int orphan = 0;
        int duplicate = 0;
        int malformed = 0;
        int removedTotal = 0;
        int orphanRemoved = 0;
        int duplicateRemoved = 0;
        List<HeartVisualGroup> publicGroups = new ArrayList<>();
        for (GroupWork group : analysis.groups()) {
            switch (group.status()) {
                case VALID -> valid++;
                case ORPHAN_TOWN_MISSING, ORPHAN_HEART_INACTIVE -> orphan++;
                case DUPLICATE -> duplicate++;
                case MALFORMED -> malformed++;
            }
            int removed = removedByGroup.getOrDefault(group, 0);
            removedTotal += removed;
            if (group.status() == HeartVisualStatus.ORPHAN_TOWN_MISSING || group.status() == HeartVisualStatus.ORPHAN_HEART_INACTIVE) orphanRemoved += removed;
            if (group.status() == HeartVisualStatus.DUPLICATE) duplicateRemoved += removed;
            Location loc = group.anchor();
            publicGroups.add(new HeartVisualGroup(group.townId(), group.rawTownId(), group.status(), group.reason(),
                    loc == null || loc.getWorld() == null ? "?" : loc.getWorld().getName(),
                    loc == null ? 0.0D : loc.getX(), loc == null ? 0.0D : loc.getY(), loc == null ? 0.0D : loc.getZ(),
                    group.entities().size(), removed));
        }
        return new HeartReconciliationReport(analysis.chunksScanned(), analysis.heartEntities(), valid, orphan, duplicate, malformed, orphanRemoved, duplicateRemoved, removedTotal, publicGroups);
    }

    private Scope loadedScope() {
        List<Entity> entities = new ArrayList<>();
        int chunks = 0;
        for (World world : Bukkit.getWorlds()) {
            for (Chunk chunk : world.getLoadedChunks()) {
                chunks++;
                for (Entity entity : chunk.getEntities()) entities.add(entity);
            }
        }
        return new Scope(List.copyOf(entities), chunks);
    }

    private Scope nearbyScope(Location center, double radius) {
        World world = center.getWorld();
        if (world == null) return new Scope(List.of(), 0);
        List<Entity> entities = new ArrayList<>(world.getNearbyEntities(center, radius, radius, radius));
        int chunks = 0;
        double minX = center.getX() - radius;
        double maxX = center.getX() + radius;
        double minZ = center.getZ() - radius;
        double maxZ = center.getZ() + radius;
        for (Chunk chunk : world.getLoadedChunks()) {
            int chunkMinX = chunk.getX() << 4;
            int chunkMinZ = chunk.getZ() << 4;
            if (chunkMinX + 15 < minX || chunkMinX > maxX || chunkMinZ + 15 < minZ || chunkMinZ > maxZ) continue;
            chunks++;
        }
        return new Scope(List.copyOf(entities), chunks);
    }

    private void register(List<Entity> entities) {
        for (Entity entity : entities) {
            String raw = renderer.rawTownId(entity);
            String part = renderer.partId(entity);
            UUID townId = parseUuid(raw);
            if (townId != null && part != null && renderer.isKnownPart(part) && entity.isValid()) {
                renderer.registerExisting(townId, entity, part);
            }
        }
    }

    private int removeEntities(List<Entity> entities) {
        int removed = 0;
        for (Entity entity : entities) {
            if (entity == null || !entity.isValid()) continue;
            renderer.unregister(entity);
            entity.remove();
            removed++;
        }
        return removed;
    }

    private Location anchor(List<Entity> entities) {
        for (Entity entity : entities) {
            if (entity != null) return entity.getLocation().clone();
        }
        return null;
    }

    private String duplicateFingerprint(GroupWork group) {
        Location loc = group.anchor();
        String where = loc == null || loc.getWorld() == null ? "?" : loc.getWorld().getName() + ":" + loc.getBlockX() + ":" + loc.getBlockY() + ":" + loc.getBlockZ();
        return group.townId() + "@" + where + ":" + group.entities().size() + ":" + group.removable().size();
    }

    private String auditData(Location loc, int entityCount, int removed, String reason, boolean dryRun) {
        String world = loc == null || loc.getWorld() == null ? "?" : loc.getWorld().getName();
        int x = loc == null ? 0 : loc.getBlockX();
        int y = loc == null ? 0 : loc.getBlockY();
        int z = loc == null ? 0 : loc.getBlockZ();
        return "world=" + world + ",x=" + x + ",y=" + y + ",z=" + z + ",entities=" + entityCount + ",removed=" + removed + ",dryRun=" + dryRun + ",reason=" + safeReason(reason);
    }

    private String safeReason(String reason) {
        if (reason == null) return "";
        return reason.length() <= 220 ? reason : reason.substring(0, 220);
    }

    private UUID parseUuid(String raw) {
        if (raw == null || raw.isBlank()) return null;
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private boolean sameWorld(Location a, Location b) {
        return a != null && b != null && a.getWorld() != null && b.getWorld() != null && a.getWorld().equals(b.getWorld());
    }

    private FoundationCandidate findNearestFoundation(Location origin) {
        World world = origin.getWorld();
        if (world == null) return null;
        FoundationCandidate best = null;
        double bestDistance = Double.MAX_VALUE;
        int minY = Math.max(world.getMinHeight(), origin.getBlockY() - FOUNDATION_VERTICAL_RADIUS);
        int maxY = Math.min(world.getMaxHeight() - 1, origin.getBlockY() + FOUNDATION_VERTICAL_RADIUS);
        for (int y = minY; y <= maxY; y++) {
            for (int x = origin.getBlockX() - FOUNDATION_SEARCH_RADIUS; x <= origin.getBlockX() + FOUNDATION_SEARCH_RADIUS; x++) {
                for (int z = origin.getBlockZ() - FOUNDATION_SEARCH_RADIUS; z <= origin.getBlockZ() + FOUNDATION_SEARCH_RADIUS; z++) {
                    if (!world.isChunkLoaded(x >> 4, z >> 4)) continue;
                    if (world.getBlockAt(x, y, z).getType() != Material.BEDROCK) continue;
                    if (!isExactFoundation(world, x, y, z)) continue;
                    double distance = squared(origin.getX(), origin.getY(), origin.getZ(), x + 0.5D, y, z + 0.5D);
                    if (distance < bestDistance) {
                        bestDistance = distance;
                        best = new FoundationCandidate(world, x, y, z);
                    }
                }
            }
        }
        return best;
    }

    private boolean isExactFoundation(World world, int centerX, int y, int centerZ) {
        // Require the immediate outer ring to be non-bedrock. This avoids treating
        // the natural bottom bedrock layer or a larger admin bedrock platform as a heart footprint.
        for (int x = centerX - 5; x <= centerX + 5; x++) {
            if (!loadedAndNotBedrock(world, x, y, centerZ - 5)) return false;
            if (!loadedAndNotBedrock(world, x, y, centerZ + 5)) return false;
        }
        for (int z = centerZ - 4; z <= centerZ + 4; z++) {
            if (!loadedAndNotBedrock(world, centerX - 5, y, z)) return false;
            if (!loadedAndNotBedrock(world, centerX + 5, y, z)) return false;
        }
        for (int x = centerX - 4; x <= centerX + 4; x++) {
            for (int z = centerZ - 4; z <= centerZ + 4; z++) {
                if (!world.isChunkLoaded(x >> 4, z >> 4)) return false;
                if (world.getBlockAt(x, y, z).getType() != Material.BEDROCK) return false;
            }
        }
        return true;
    }

    private boolean loadedAndNotBedrock(World world, int x, int y, int z) {
        return world.isChunkLoaded(x >> 4, z >> 4) && world.getBlockAt(x, y, z).getType() != Material.BEDROCK;
    }

    private double squared(double ax, double ay, double az, double bx, double by, double bz) {
        double dx = ax - bx;
        double dy = ay - by;
        double dz = az - bz;
        return dx * dx + dy * dy + dz * dz;
    }

    private record Candidate(Entity entity, UUID townId, String rawTownId, String part) {
    }

    private record GroupWork(UUID townId, String rawTownId, HeartVisualStatus status, String reason, Location anchor,
                             List<Entity> entities, List<Entity> keep, List<Entity> removable) {
    }

    private record Analysis(int chunksScanned, int heartEntities, List<GroupWork> groups) {
    }

    private record Scope(List<Entity> entities, int chunksScanned) {
    }

    private record FoundationCandidate(World world, int x, int y, int z) {
    }
}
