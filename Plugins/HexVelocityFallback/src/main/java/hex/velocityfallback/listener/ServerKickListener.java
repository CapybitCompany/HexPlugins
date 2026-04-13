package hex.velocityfallback.listener;

import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.player.KickedFromServerEvent;
import hex.velocityfallback.service.FallbackRedirectService;

public final class ServerKickListener {

    private final FallbackRedirectService fallbackRedirectService;

    public ServerKickListener(FallbackRedirectService fallbackRedirectService) {
        this.fallbackRedirectService = fallbackRedirectService;
    }

    @Subscribe
    public void onKickedFromServer(KickedFromServerEvent event) {
        fallbackRedirectService.handleKickedFromServer(event);
    }
}
