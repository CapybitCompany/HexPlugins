package hex.minigames.framework.status;

import hex.core.api.HexApi;
import hex.minigames.framework.ModeStatusSnapshot;
import hex.minigames.framework.ServerStatusSnapshot;
import org.bukkit.plugin.java.JavaPlugin;

import java.lang.reflect.Method;
import java.util.Map;

public final class ReflectiveStatusPublisher implements StatusPublisher {

    private final JavaPlugin plugin;
    private final HexApi api;

    private boolean warned;

    public ReflectiveStatusPublisher(JavaPlugin plugin, HexApi api) {
        this.plugin = plugin;
        this.api = api;
    }

    @Override
    public void publish(Map<String, ModeStatusSnapshot> modes, ServerStatusSnapshot server) {
        Object statusService = resolveStatusService();
        if (statusService == null) {
            return;
        }

        for (ModeStatusSnapshot mode : modes.values()) {
            invokeIfExists(
                    statusService,
                    "publishMode",
                    new Class[]{String.class, int.class, int.class, boolean.class, int.class},
                    mode.gameTypeId(),
                    mode.waiting(),
                    mode.inGame(),
                    mode.joinable(),
                    mode.maxPlayers()
            );

            invokeIfExists(
                    statusService,
                    "publishModeStatus",
                    new Class[]{String.class, int.class, int.class, boolean.class, int.class},
                    mode.gameTypeId(),
                    mode.waiting(),
                    mode.inGame(),
                    mode.joinable(),
                    mode.maxPlayers()
            );
        }

        invokeIfExists(
                statusService,
                "publishServerStatus",
                new Class[]{int.class, String.class, String.class},
                server.playersOnline(),
                server.motd(),
                server.state()
        );
    }

    private Object resolveStatusService() {
        try {
            Method m = api.getClass().getMethod("status");
            return m.invoke(api);
        } catch (ReflectiveOperationException ex) {
            if (!warned) {
                plugin.getLogger().info("[Minigames] HexCore status() not available, publishing disabled.");
                warned = true;
            }
            return null;
        }
    }

    private void invokeIfExists(Object target, String methodName, Class<?>[] sig, Object... args) {
        try {
            Method m = target.getClass().getMethod(methodName, sig);
            m.invoke(target, args);
        } catch (ReflectiveOperationException ignored) {
            // Keep compatibility with older/newer HexCore versions.
        }
    }
}

