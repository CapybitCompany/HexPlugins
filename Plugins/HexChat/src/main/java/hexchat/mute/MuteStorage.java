package hexchat.mute;

import java.util.Map;
import java.util.UUID;

/**
 * Trwałe przechowywanie wyciszeń graczy. Implementacje muszą być bezpieczne wątkowo
 * na tyle, by odczyt startowy i pojedyncze zapisy/usunięcia nie psuły stanu.
 */
public interface MuteStorage {

    /** Wczytuje wszystkie zapisane wyciszenia (wywoływane raz przy starcie). */
    Map<UUID, MuteEntry> loadAll();

    /** Zapisuje/aktualizuje pojedyncze wyciszenie. */
    void save(MuteEntry entry);

    /** Usuwa wyciszenie danego gracza (jeśli istnieje). */
    void remove(UUID playerId);
}
