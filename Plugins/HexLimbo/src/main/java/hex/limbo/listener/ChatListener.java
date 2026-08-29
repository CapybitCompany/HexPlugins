package hex.limbo.listener;

import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.player.PlayerChatEvent;
import com.velocitypowered.api.proxy.Player;
import hex.limbo.auth.AuthService;
import hex.limbo.config.RuntimeContext;

/**
 * Silences chat while the player is unauthenticated. Admin-bypass users skip the gate.
 *
 * <p>The gate is fail-closed: see
 * {@link hex.limbo.auth.ConnectionRegistry#isAuthenticatedConnection(java.util.UUID, Object)}.
 *
 * <p>Velocity API limitation: {@code PlayerChatEvent} is deprecated since Velocity 3.3 in favour of
 * a packet-based signed-chat pipeline that Velocity does not surface a stable API for. Until
 * Velocity exposes a stable replacement, this is the supported chat-block hook and may break with
 * future Velocity revisions of the signed-chat protocol.
 */
public final class ChatListener {

    private final AuthService authService;
    private final RuntimeContext context;

    public ChatListener(AuthService authService, RuntimeContext context) {
        this.authService = authService;
        this.context = context;
    }

    @Subscribe
    @SuppressWarnings("deprecation")
    public void onChat(PlayerChatEvent event) {
        Player player = event.getPlayer();
        if (player.hasPermission(context.config().adminBypassPermission())) {
            return;
        }
        // Fail-closed and identity-scoped: an unknown or superseded socket stays silenced and is
        // never judged against the auth state of whoever holds the UUID now.
        if (authService.connections().isAuthenticatedConnection(player.getUniqueId(), player)) {
            return;
        }
        event.setResult(PlayerChatEvent.ChatResult.denied());
        player.sendMessage(context.messages().component("error.chat-blocked"));
    }
}
