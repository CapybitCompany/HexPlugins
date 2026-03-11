package hex.minigames.framework.status;

import hex.minigames.framework.ModeStatusSnapshot;
import hex.minigames.framework.ServerStatusSnapshot;

import java.util.Map;

public final class NoopStatusPublisher implements StatusPublisher {
    @Override
    public void publish(Map<String, ModeStatusSnapshot> modes, ServerStatusSnapshot server) {
        // HexCore status service not available in current API.
    }
}

