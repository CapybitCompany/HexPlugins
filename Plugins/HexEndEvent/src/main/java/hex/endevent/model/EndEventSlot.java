package hex.endevent.model;

import java.time.ZonedDateTime;

public record EndEventSlot(ZonedDateTime start, ZonedDateTime end, String eventId) {
    public boolean contains(ZonedDateTime now) {
        return !now.isBefore(start) && now.isBefore(end);
    }
}
