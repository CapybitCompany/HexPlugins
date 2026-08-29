package hexcustomitems.model;

import java.util.Objects;

/** Sendet dem nutzenden Spieler eine MiniMessage-Nachricht (mit Prefix). */
public record MessageAction(
        String message,
        boolean offensive
) implements ItemAction {

    public MessageAction {
        message = Objects.requireNonNull(message, "message");
    }
}
