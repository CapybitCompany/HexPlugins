package hex.core.api.messaging;

@FunctionalInterface
public interface HexMessageListener {
    void onMessage(HexMessage message);
}

