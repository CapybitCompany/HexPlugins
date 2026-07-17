package hexchat.service;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Rozwiązywanie graczy po nazwie (online lub znani offline), aby komendy moderacyjne
 * mogły działać także na nieobecnych graczach. Wydzielone jako interfejs, by komendy
 * były testowalne bez działającego serwera.
 */
public interface PlayerDirectory {

    Optional<ResolvedPlayer> resolve(String name);

    /**
     * Nazwy graczy online pasujące do podanego prefiksu (podpowiedzi tab-complete).
     * Bez blokujących zapytań do sieci Mojang — tylko gracze aktualnie online.
     */
    List<String> onlineNames(String prefix);

    /** Wysyła wiadomość do gracza, jeśli jest online (używane przy nakładaniu wyciszenia). */
    void notifyIfOnline(UUID playerId, java.util.function.Consumer<org.bukkit.entity.Player> action);

    record ResolvedPlayer(UUID uuid, String name) {
    }
}
