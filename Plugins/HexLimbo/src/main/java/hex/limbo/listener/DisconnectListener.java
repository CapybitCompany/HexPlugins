package hex.limbo.listener;

import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import hex.limbo.auth.AuthService;
import hex.limbo.prompt.PromptService;

/**
 * Cleans up per-connection auth state, cancels the login timeout, and tears down any active
 * login/register prompt (BossBar + repeating reminder task) when the player leaves. This also
 * covers the login-timeout and admin forcelogout/unregister paths, which all end in a disconnect.
 */
public final class DisconnectListener {

    private final AuthService authService;
    private final LoginListener loginListener;
    private final PromptService promptService;

    public DisconnectListener(AuthService authService, LoginListener loginListener, PromptService promptService) {
        this.authService = authService;
        this.loginListener = loginListener;
        this.promptService = promptService;
    }

    @Subscribe
    public void onDisconnect(DisconnectEvent event) {
        java.util.UUID uuid = event.getPlayer().getUniqueId();
        loginListener.cancelLoginTimer(uuid);
        promptService.clear(uuid, event.getPlayer());
        authService.removeConnection(uuid);
    }
}
