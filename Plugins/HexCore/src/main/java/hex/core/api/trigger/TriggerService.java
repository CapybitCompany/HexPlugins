package hex.core.api.trigger;

public interface TriggerService {
    void publish(GameTrigger trigger);

    void subscribe(String triggerId, TriggerListener listener);

    void unsubscribe(String triggerId, TriggerListener listener);
}

