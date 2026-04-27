package hex.minigames.framework.status;

import hex.minigames.framework.ModeStatusSnapshot;
import hex.minigames.framework.ServerStatusSnapshot;

import java.util.Map;

public interface StatusPublisher {
    void publish(Map<String, ModeStatusSnapshot> modes, ServerStatusSnapshot server);
}

