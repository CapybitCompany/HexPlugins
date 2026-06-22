package hex.limbo.listener;

import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.player.PlayerChatEvent;
import com.velocitypowered.api.proxy.Player;
import hex.limbo.auth.AuthService;
import hex.limbo.config.RuntimeContext;
import net.kyori.adventure.text.Component;

/**
 * Silences chat while the player is unauthenticated. Admin-bypass users skip the gate.
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
        if (authService.isAuthenticated(player.getUniqueId())) {
            return;
        }
        event.setResult(PlayerChatEvent.ChatResult.denied());
        player.sendMessage(Component.text(context.messages().raw("error.chat-blocked")));
    }
}
