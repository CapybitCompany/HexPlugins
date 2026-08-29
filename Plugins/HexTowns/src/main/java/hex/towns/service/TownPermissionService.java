package hex.towns.service;

import hex.towns.api.TownPermission;
import hex.towns.database.TownRepository;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiPredicate;
import java.util.function.Function;

/** Fast in-memory permission checks with durable DB overrides. */
public final class TownPermissionService {
    private final TownRepository repository;
    private final BiPredicate<UUID, UUID> ownerCheck;
    private final BiPredicate<UUID, UUID> memberCheck;
    private final Function<UUID, Long> internalTownId;
    private final Map<UUID, EnumMap<TownPermission, Boolean>> overrides = new ConcurrentHashMap<>();

    public TownPermissionService(TownRepository repository,
                                 BiPredicate<UUID, UUID> ownerCheck,
                                 BiPredicate<UUID, UUID> memberCheck,
                                 Function<UUID, Long> internalTownId) {
        this.repository = repository;
        this.ownerCheck = ownerCheck;
        this.memberCheck = memberCheck;
        this.internalTownId = internalTownId;
    }

    public void load(List<TownRepository.MemberPermissionRecord> records) {
        overrides.clear();
        if (records == null) return;
        for (TownRepository.MemberPermissionRecord record : records) {
            overrides.computeIfAbsent(record.playerId(), ignored -> new EnumMap<>(TownPermission.class))
                    .put(record.permission(), record.allowed());
        }
    }

    public boolean can(UUID playerId, UUID townId, TownPermission permission) {
        if (playerId == null || townId == null || permission == null) return false;
        if (ownerCheck.test(playerId, townId)) return true;
        if (!memberCheck.test(playerId, townId)) return false;
        EnumMap<TownPermission, Boolean> map = overrides.get(playerId);
        // Legacy members remain trusted for the historic permissions, but the newly introduced
        // BANK_WITHDRAW capability is intentionally fail-closed. Otherwise every old COOP member
        // would automatically gain access to the town treasury after an update.
        if (map == null) return permission != TownPermission.BANK_WITHDRAW;
        return map.getOrDefault(permission, permission != TownPermission.BANK_WITHDRAW);
    }

    public Map<TownPermission, Boolean> snapshot(UUID playerId, UUID townId) {
        EnumMap<TownPermission, Boolean> result = new EnumMap<>(TownPermission.class);
        for (TownPermission permission : TownPermission.values()) result.put(permission, can(playerId, townId, permission));
        return Map.copyOf(result);
    }

    public Map<TownPermission, Boolean> restrictedDefaults() {
        EnumMap<TownPermission, Boolean> map = new EnumMap<>(TownPermission.class);
        for (TownPermission permission : TownPermission.values()) {
            boolean allowed = switch (permission) {
                case CONTAINERS, MINION_PICKUP, MACHINE_BREAK, BANK_WITHDRAW -> false;
                default -> true;
            };
            map.put(permission, allowed);
        }
        return Map.copyOf(map);
    }

    public void initializeRestricted(long townInternalId, UUID playerId) {
        Map<TownPermission, Boolean> defaults = restrictedDefaults();
        for (Map.Entry<TownPermission, Boolean> entry : defaults.entrySet()) {
            repository.setMemberPermission(townInternalId, playerId, entry.getKey(), entry.getValue());
        }
        installRestrictedRuntime(playerId, defaults);
    }

    /** Runtime-only half used after a transactional membership DB commit. */
    public void installRestrictedRuntime(UUID playerId, Map<TownPermission, Boolean> values) {
        EnumMap<TownPermission, Boolean> map = new EnumMap<>(TownPermission.class);
        if (values != null) map.putAll(values);
        overrides.put(playerId, map);
    }

    public boolean set(UUID ownerId, UUID townId, UUID memberId, TownPermission permission, boolean allowed) {
        if (!ownerCheck.test(ownerId, townId) || !memberCheck.test(memberId, townId) || ownerCheck.test(memberId, townId)) return false;
        Long internalId = internalTownId.apply(townId);
        if (internalId == null || internalId <= 0) return false;
        overrides.computeIfAbsent(memberId, ignored -> new EnumMap<>(TownPermission.class)).put(permission, allowed);
        repository.setMemberPermission(internalId, memberId, permission, allowed);
        return true;
    }

    /** Administrative repair path. Caller must verify admin authorization before invoking. */
    public boolean setAsAdmin(UUID townId, UUID memberId, TownPermission permission, boolean allowed) {
        if (townId == null || memberId == null || permission == null || !memberCheck.test(memberId, townId) || ownerCheck.test(memberId, townId)) return false;
        Long internalId = internalTownId.apply(townId);
        if (internalId == null || internalId <= 0) return false;
        overrides.computeIfAbsent(memberId, ignored -> new EnumMap<>(TownPermission.class)).put(permission, allowed);
        repository.setMemberPermission(internalId, memberId, permission, allowed);
        return true;
    }

    public void remove(UUID playerId) {
        overrides.remove(playerId);
    }
}
