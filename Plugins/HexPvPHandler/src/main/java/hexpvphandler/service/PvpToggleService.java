package hexpvphandler.service;

import org.bukkit.plugin.java.JavaPlugin;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

public final class PvpToggleService {

    private final JavaPlugin plugin;
    private final AtomicBoolean blocked;

    public PvpToggleService(JavaPlugin plugin, boolean initialBlocked) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.blocked = new AtomicBoolean(initialBlocked);
    }

    public boolean isBlocked() {
        return blocked.get();
    }

    public boolean setBlocked(boolean value) {
        boolean previous = blocked.getAndSet(value);
        if (previous == value) {
            return false;
        }
        plugin.getConfig().set("pvp.blocked", value);
        plugin.saveConfig();
        return true;
    }
}
