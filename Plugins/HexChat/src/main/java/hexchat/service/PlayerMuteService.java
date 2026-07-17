package hexchat.service;

import hexchat.config.HexChatConfig;
import hexchat.mute.MuteEntry;
import hexchat.mute.MuteStorage;
import hexchat.permission.HexChatPermissions;
import hexchat.util.DurationUtil;
import org.bukkit.entity.Player;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * Zarządza indywidualnymi (per gracz) wyciszeniami — uzupełnienie globalnego wyciszenia.
 * Wyciszenia są trwałe (przez {@link MuteStorage}) i mogą być czasowe lub permanentne.
 */
public final class PlayerMuteService {

    private final Supplier<HexChatConfig> configSupplier;
    private final MuteStorage storage;
    private final LongSupplier clock;
    private final ConcurrentHashMap<UUID, MuteEntry> activeMutes = new ConcurrentHashMap<>();

    public PlayerMuteService(Supplier<HexChatConfig> configSupplier, MuteStorage storage) {
        this(configSupplier, storage, System::currentTimeMillis);
    }

    public PlayerMuteService(Supplier<HexChatConfig> configSupplier, MuteStorage storage, LongSupplier clock) {
        this.configSupplier = Objects.requireNonNull(configSupplier, "configSupplier");
        this.storage = Objects.requireNonNull(storage, "storage");
        this.clock = Objects.requireNonNull(clock, "clock");
        for (Map.Entry<UUID, MuteEntry> entry : storage.loadAll().entrySet()) {
            activeMutes.put(entry.getKey(), entry.getValue());
        }
    }

    public boolean isModuleEnabled() {
        return configSupplier.get().playerMute().enabled();
    }

    /**
     * Nakłada wyciszenie.
     *
     * @param durationMillis czas trwania w ms, lub {@link DurationUtil#PERMANENT} dla permanentnego
     * @return utworzony wpis wyciszenia
     */
    public MuteEntry mute(UUID playerId, String playerName, long durationMillis, String reason) {
        Objects.requireNonNull(playerId, "playerId");
        long now = clock.getAsLong();
        long until = durationMillis == DurationUtil.PERMANENT ? 0L : now + durationMillis;
        MuteEntry entry = new MuteEntry(
                playerId,
                playerName == null ? "?" : playerName,
                until,
                reason == null ? "" : reason,
                now
        );
        activeMutes.put(playerId, entry);
        storage.save(entry);
        return entry;
    }

    /**
     * Zdejmuje wyciszenie.
     *
     * @return {@code true}, jeśli gracz był aktywnie wyciszony
     */
    public boolean unmute(UUID playerId) {
        boolean wasActive = activeMute(playerId).isPresent();
        activeMutes.remove(playerId);
        storage.remove(playerId);
        return wasActive;
    }

    /**
     * Zwraca aktywne wyciszenie gracza. Wygasłe wpisy są czyszczone leniwie.
     */
    public Optional<MuteEntry> activeMute(UUID playerId) {
        MuteEntry entry = activeMutes.get(playerId);
        if (entry == null) {
            return Optional.empty();
        }
        if (entry.isExpiredAt(clock.getAsLong())) {
            activeMutes.remove(playerId, entry);
            storage.remove(playerId);
            return Optional.empty();
        }
        return Optional.of(entry);
    }

    /**
     * Czy dany gracz powinien być zablokowany na czacie przez wyciszenie indywidualne.
     * OP/administratorzy oraz gracze z uprawnieniem bypass nie są wyciszani.
     */
    public boolean isMutedFor(Player player) {
        if (!isModuleEnabled()) {
            return false;
        }
        if (player.isOp() || player.hasPermission(HexChatPermissions.ADMIN)) {
            return false;
        }
        if (player.hasPermission(configSupplier.get().playerMute().bypassPermission())) {
            return false;
        }
        return activeMute(player.getUniqueId()).isPresent();
    }

    public long remainingMillis(MuteEntry entry) {
        return entry.remainingMillisAt(clock.getAsLong());
    }
}
