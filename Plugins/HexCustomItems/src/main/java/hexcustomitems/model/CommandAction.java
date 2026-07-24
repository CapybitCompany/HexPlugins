package hexcustomitems.model;

import java.util.List;
import java.util.Objects;

/**
 * Führt eine Liste von Befehlen aus - entweder über die Konsole oder als Spieler.
 * Platzhalter (%player%, %uuid%, %item_id%, %world%, %x%, %y%, %z%, %amount%)
 * werden vor der Ausführung ersetzt.
 */
public record CommandAction(
        CommandExecutorType executor,
        List<String> commands,
        boolean offensive
) implements ItemAction {

    public CommandAction {
        executor = executor == null ? CommandExecutorType.CONSOLE : executor;
        commands = List.copyOf(Objects.requireNonNull(commands, "commands"));
    }
}
