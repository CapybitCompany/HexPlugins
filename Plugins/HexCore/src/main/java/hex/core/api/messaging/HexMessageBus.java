package hex.core.api.messaging;

public interface HexMessageBus {

    void subscribe(String channel, HexMessageListener listener);

    void unsubscribe(String channel, HexMessageListener listener);

    void publish(HexMessage message);
}

