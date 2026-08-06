package hex.auctionbazaar.gui;

import net.kyori.adventure.text.Component;
import org.bukkit.Location;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.Optional;

/**
 * Warstwa transportu (I/O) dla {@link SignPrompt}. Kapsułuje wszystkie
 * interakcje z Bukkit/światem/schedulerem tak, aby maszyna stanów promptu była
 * w pełni testowalna bez uruchamiania serwera (test dostarcza atrapę).
 *
 * Kontrakt kolejności (patrz punkt #1):
 *  - {@link #prepareSign} umieszcza tymczasową tabliczkę i zapisuje na niej
 *    linie, ale NIE otwiera edytora (i nie zmienia świata na stałe),
 *  - {@link #openEditor} otwiera edytor - {@link SignPrompt} woła je dopiero w
 *    NASTĘPNYM ticku, gdy pending-sesja jest już zarejestrowana,
 *  - {@link SignHandle#restore()} przywraca oryginalny blok i MUSI być
 *    idempotentne (wywołanie wielokrotne = jedno realne przywrócenie).
 */
public interface SignPromptTransport {

    /**
     * Typizowany wynik próby otwarcia edytora tabliczki (punkt #9).
     *  - {@link #OPENED}: wywołanie Paper ({@link org.bukkit.entity.Player#openSign}) zostało przyjęte
     *    bez błędu. UWAGA: oznacza to WYŁĄCZNIE, że serwer zaakceptował żądanie - NIE gwarantuje, że
     *    klient faktycznie wyświetlił okno edytora. Dlatego równolegle działa awaryjny prompt na czacie
     *    (po krótkiej chwili), by gracz zawsze mógł wpisać wartość.
     *  - {@link #FAILED}: nie udało się (blok nie jest już tabliczką albo wyjątek) -> natychmiastowy
     *    czysty fallback na czat.
     */
    enum OpenResult { OPENED, FAILED }

    /**
     * Zamknij aktualnie otwarte GUI gracza (potrzebne, by mógł otworzyć się
     * edytor tabliczki / by gracz widział czat).
     */
    void closeUi(Player player);

    /**
     * Przygotuj tymczasową tabliczkę w bezpiecznej, załadowanej i zastępowalnej
     * lokalizacji w zasięgu gracza; zapisz {@code lines}. Zwraca uchwyt lub
     * pusty Optional, gdy nie ma bezpiecznej lokalizacji (natychmiastowy
     * fallback na czat).
     */
    Optional<SignHandle> prepareSign(Player player, List<String> lines);

    /**
     * Otwórz edytor tabliczki (wołane w następnym ticku). Zwraca {@link OpenResult#OPENED} gdy edytor
     * faktycznie otwarto, albo {@link OpenResult#FAILED} - wtedy {@link SignPrompt} natychmiast przechodzi
     * na czysty fallback czatu (nie {@code TRANSPORT_FAILED}, bo czat jest dostępny).
     */
    OpenResult openEditor(Player player, SignHandle handle);

    /** Zaplanuj zadanie po {@code ticks} tickach; zwraca uchwyt do anulowania. */
    Cancellable runLater(Runnable task, long ticks);

    /** Wykonaj zadanie na wątku głównym (natychmiast lub w najbliższym ticku). */
    void runMain(Runnable task);

    /** Wyślij prywatną wiadomość do gracza (nie trafia do publicznego czatu). */
    void sendMessage(Player player, Component message);

    interface SignHandle {
        Location location();

        /** Idempotentne przywrócenie oryginalnego bloku (brak ghost-bloków). */
        void restore();
    }

    interface Cancellable {
        void cancel();
    }
}
