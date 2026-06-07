package hex.core.service.trigger;

import hex.core.api.messaging.HexMessage;
import hex.core.api.messaging.HexMessageBus;
import hex.core.api.messaging.HexMessageListener;
import hex.core.api.trigger.GameTrigger;
import hex.core.api.trigger.TriggerListener;
import hex.core.api.trigger.TriggerService;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

public final class HexTriggerService implements TriggerService {
    private final HexMessageBus messageBus;
    private final Map<TriggerKey, HexMessageListener> adapters = new ConcurrentHashMap<>();

    public HexTriggerService(HexMessageBus messageBus) {
        this.messageBus = Objects.requireNonNull(messageBus, "messageBus");
    }

    @Override
    public void publish(GameTrigger trigger) {
        Objects.requireNonNull(trigger, "trigger");
        messageBus.publish(HexMessage.of(
                GameTrigger.channelOf(trigger.triggerId()),
                trigger.sourcePlugin(),
                trigger.data()
        ));
    }

    @Override
    public void subscribe(String triggerId, TriggerListener listener) {
        Objects.requireNonNull(listener, "listener");
        String channel = GameTrigger.channelOf(triggerId);
        TriggerKey key = new TriggerKey(channel, listener);
        HexMessageListener adapter = message -> listener.onTrigger(
                GameTrigger.of(GameTrigger.triggerIdFromChannel(message.channel()), message.sender(), message.data())
        );
        HexMessageListener previous = adapters.putIfAbsent(key, adapter);
        if (previous == null) {
            messageBus.subscribe(channel, adapter);
        }
    }

    @Override
    public void unsubscribe(String triggerId, TriggerListener listener) {
        if (listener == null) {
            return;
        }
        String channel = GameTrigger.channelOf(triggerId);
        HexMessageListener adapter = adapters.remove(new TriggerKey(channel, listener));
        if (adapter != null) {
            messageBus.unsubscribe(channel, adapter);
        }
    }

    private record TriggerKey(String channel, TriggerListener listener) {
    }
}

