package hexchat.service;

import hexchat.config.HexChatConfig;
import hexchat.permission.HexChatPermissions;
import org.bukkit.entity.Player;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

public final class GlobalChatMuteService {

    private final Supplier<HexChatConfig> configSupplier;
    private final AtomicBoolean muted;

    public GlobalChatMuteService(Supplier<HexChatConfig> configSupplier, boolean initialMuted) {
        this.configSupplier = Objects.requireNonNull(configSupplier, "configSupplier");
        this.muted = new AtomicBoolean(initialMuted);
    }

    public boolean isMuteModuleEnabled() {
        return configSupplier.get().chat().globalMute().enabled();
    }

    public boolean isMuted() {
        return muted.get();
    }

    public boolean isMutedFor(Player player) {
        if (!isMuteModuleEnabled() || !muted.get()) {
            return false;
        }

        if (player.isOp() || player.hasPermission(HexChatPermissions.ADMIN)) {
            return false;
        }

        String bypassPermission = configSupplier.get().chat().globalMute().bypassPermission();
        return !player.hasPermission(bypassPermission);
    }

    public void updateInitialStateFromConfigIfNeeded() {
        if (!muted.get()) {
            muted.compareAndSet(false, configSupplier.get().chat().globalMute().initiallyMuted());
        }
    }

    public boolean setMuted(boolean value) {
        return muted.getAndSet(value);
    }

    public boolean toggleMuted() {
        boolean previous;
        boolean updated;
        do {
            previous = muted.get();
            updated = !previous;
        } while (!muted.compareAndSet(previous, updated));
        return updated;
    }
}
