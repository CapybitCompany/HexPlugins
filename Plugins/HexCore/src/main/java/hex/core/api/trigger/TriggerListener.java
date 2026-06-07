package hex.core.api.trigger;

@FunctionalInterface
public interface TriggerListener {
    void onTrigger(GameTrigger trigger);
}

