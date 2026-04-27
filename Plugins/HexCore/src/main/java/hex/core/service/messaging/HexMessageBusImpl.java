package hex.core.service.messaging;

import hex.core.api.messaging.HexMessage;
import hex.core.api.messaging.HexMessageBus;
import hex.core.api.messaging.HexMessageListener;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Level;
import java.util.logging.Logger;

public final class HexMessageBusImpl implements HexMessageBus {

    private static final Logger LOG = Logger.getLogger("HexCore-MessageBus");

    private final Map<String, List<HexMessageListener>> listeners = new ConcurrentHashMap<>();

    @Override
    public void subscribe(String channel, HexMessageListener listener) {
        listeners.computeIfAbsent(channel, k -> new CopyOnWriteArrayList<>()).add(listener);
    }

    @Override
    public void unsubscribe(String channel, HexMessageListener listener) {
        List<HexMessageListener> list = listeners.get(channel);
        if (list != null) {
            list.remove(listener);
        }
    }

    @Override
    public void publish(HexMessage message) {
        List<HexMessageListener> list = listeners.get(message.channel());
        if (list == null || list.isEmpty()) {
            return;
        }

        for (HexMessageListener listener : list) {
            try {
                listener.onMessage(message);
            } catch (Exception ex) {
                LOG.log(Level.WARNING,
                        "[HexMessageBus] Error in listener on channel '"
                                + message.channel() + "' from sender '" + message.sender() + "'",
                        ex);
            }
        }
    }
}

