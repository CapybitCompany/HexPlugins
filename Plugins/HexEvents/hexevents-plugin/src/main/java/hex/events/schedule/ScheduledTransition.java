package hex.events.schedule;

import java.time.Instant;
import java.util.UUID;

public record ScheduledTransition(UUID instanceId, Type type, Instant at) implements Comparable<ScheduledTransition> {
    public enum Type { BOSSBAR_SHOW, REGISTRATION_OPEN, PREPARE, LOBBY, START, LATE_JOIN_CLOSE, END }

    public String key() { return instanceId + ":" + type.name() + ":" + at.toEpochMilli(); }

    @Override public int compareTo(ScheduledTransition other) {
        int byTime = at.compareTo(other.at);
        if (byTime != 0) return byTime;
        int byInstance = instanceId.compareTo(other.instanceId);
        if (byInstance != 0) return byInstance;
        return type.compareTo(other.type);
    }
}
