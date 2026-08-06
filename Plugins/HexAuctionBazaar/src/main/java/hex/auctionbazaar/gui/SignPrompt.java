package hex.auctionbazaar.gui;

import hex.auctionbazaar.util.MessageFactory;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.block.SignChangeEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.Plugin;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * Bezpieczny prompt wartości liczbowej/tekstowej. Maszyna stanów jest oddzielona
 * od I/O przez {@link SignPromptTransport}, dzięki czemu jest w pełni testowalna.
 *
 * Przebieg (punkt #1):
 *  1. kończymy ewentualną poprzednią sesję gracza (max jedna na gracza),
 *  2. zamykamy GUI i przygotowujemy tabliczkę w bezpiecznej lokalizacji w
 *     zasięgu (~2 nad graczem); brak lokalizacji -> natychmiastowy fallback na czat,
 *  3. REJESTRUJEMY pending-sesję PRZED otwarciem edytora,
 *  4. edytor otwieramy w NASTĘPNYM ticku (klient zna już stan bloku),
 *  5. po krótkiej, konfigurowalnej chwili (domyślnie ~4 s) wysyłamy polski
 *     hint na czacie - gdy edytor nie otworzył się po cichu, gracz może wpisać
 *     wartość na czacie (czat jest przechwytywany prywatnie od początku sesji),
 *  6. timeout przywraca blok i kończy sesję (callback dostaje null).
 *
 * Blok jest przywracany DOKŁADNIE RAZ przy: sukcesie, błędnej wartości, anulacji,
 * timeoucie, wylogowaniu, /reload i disable (uchwyt {@link SignPromptTransport.SignHandle}
 * jest idempotentny). Callback jest wołany co najwyżej raz.
 */
public final class SignPrompt implements Listener {

    private final Plugin plugin;
    private final SignPromptTransport transport;
    private final MessageFactory messages;
    // Odczytywane per nowa sesja - dzięki temu /reload natychmiast obowiązuje.
    private final java.util.function.LongSupplier fallbackHintTicks;
    private final java.util.function.LongSupplier timeoutTicks;
    private final Map<UUID, Session> sessions = new ConcurrentHashMap<>();

    public SignPrompt(Plugin plugin, SignPromptTransport transport, MessageFactory messages,
                      java.util.function.LongSupplier fallbackHintTicks,
                      java.util.function.LongSupplier timeoutTicks) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.transport = Objects.requireNonNull(transport, "transport");
        this.messages = Objects.requireNonNull(messages, "messages");
        this.fallbackHintTicks = Objects.requireNonNull(fallbackHintTicks, "fallbackHintTicks");
        this.timeoutTicks = Objects.requireNonNull(timeoutTicks, "timeoutTicks");
        if (plugin.getServer() != null) {
            Bukkit.getPluginManager().registerEvents(this, plugin);
        }
    }

    /** Konstruktor testowy - bez rejestracji listenera Bukkit. */
    SignPrompt(SignPromptTransport transport, MessageFactory messages,
               java.util.function.LongSupplier fallbackHintTicks,
               java.util.function.LongSupplier timeoutTicks, boolean register) {
        this.plugin = null;
        this.transport = Objects.requireNonNull(transport, "transport");
        this.messages = Objects.requireNonNull(messages, "messages");
        this.fallbackHintTicks = Objects.requireNonNull(fallbackHintTicks, "fallbackHintTicks");
        this.timeoutTicks = Objects.requireNonNull(timeoutTicks, "timeoutTicks");
    }

    /** Zakończ wszystkie sesje z przywróceniem bloków (bez wyrejestrowania listenera). Używane przy /reload. */
    public void cancelAll() {
        for (UUID id : List.copyOf(sessions.keySet())) {
            abort(id);
        }
    }

    public void shutdown() {
        cancelAll();
        if (plugin != null) {
            HandlerList.unregisterAll(this);
        }
    }

    // ---------------------------------------------------------------- typed contract (punkt #9)

    /**
     * Rozłączne wyniki promptu. Wołający MUSI je rozróżniać - żaden „null" nie miesza
     * już anulacji, timeoutu i błędnej wartości.
     *  - SUCCESS: poprawna wartość (w {@link PromptResult#value()}),
     *  - INVALID: wpisano coś, czego nie da się sparsować (np. „abc" jako liczba),
     *  - CANCELLED: gracz przerwał („anuluj"/„cancel") lub zostawił puste pole,
     *  - TIMEOUT: sesja wygasła bez wpisania wartości,
     *  - TRANSPORT_FAILED: nie dało się w ogóle dostarczyć promptu (np. gracz offline).
     */
    public enum PromptOutcome { SUCCESS, INVALID, CANCELLED, TIMEOUT, TRANSPORT_FAILED }

    public record PromptResult<T>(PromptOutcome outcome, T value) {
        public static <T> PromptResult<T> success(T value) {
            return new PromptResult<>(PromptOutcome.SUCCESS, value);
        }

        public static <T> PromptResult<T> failure(PromptOutcome outcome) {
            return new PromptResult<>(outcome, null);
        }

        public boolean isSuccess() {
            return outcome == PromptOutcome.SUCCESS;
        }
    }

    /** Klucz komunikatu (Polski) dla nieudanego wyniku promptu - wspólny dla wszystkich wołających. */
    public static String messageKey(PromptOutcome outcome) {
        return switch (outcome) {
            case CANCELLED -> "common.input-cancelled";
            case TIMEOUT -> "common.input-timeout";
            case TRANSPORT_FAILED -> "common.input-transport-failed";
            case INVALID, SUCCESS -> "common.input-invalid";
        };
    }

    // ---------------------------------------------------------------- public API

    public void promptString(Player player, String prompt, Consumer<PromptResult<String>> callback) {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(callback, "callback");
        if (!player.isOnline()) {
            // Nie ma jak dostarczyć promptu (ani tabliczka, ani czat) -> stan transportowy.
            // Brak sesji, brak bloku do przywrócenia.
            callback.accept(PromptResult.failure(PromptOutcome.TRANSPORT_FAILED));
            return;
        }
        UUID uid = player.getUniqueId();
        abort(uid);                       // max jedna sesja na gracza
        transport.closeUi(player);

        List<String> signLines = buildSignLines(prompt);
        Optional<SignPromptTransport.SignHandle> handleOpt;
        try {
            handleOpt = transport.prepareSign(player, signLines);
        } catch (Throwable t) {
            // Przygotowanie tabliczki padło - degradujemy do czatu, nie wywracamy flow.
            handleOpt = Optional.empty();
        }

        // preparedLines pozwala odróżnić rzeczywistą linię gracza od dekoracji (caret/prompt/anuluj).
        Session session = new Session(uid, player, handleOpt.orElse(null), signLines, callback);
        sessions.put(uid, session);       // <-- pending-sesja PRZED otwarciem edytora

        // Zawsze świeżo z configu (dzięki temu /reload obowiązuje od nowej sesji).
        long fallbackTicks = Math.max(1L, fallbackHintTicks.getAsLong());
        long timeoutT = Math.max(fallbackTicks + 1L, timeoutTicks.getAsLong());

        // Timeout MUSI się zaplanować - inaczej future wisiałby bez końca. Gdy scheduler odrzuci
        // zadanie (np. plugin wyłączany), kończymy terminalnie TRANSPORT_FAILED (bez ghost-signa).
        try {
            session.timeoutTask = transport.runLater(() -> onTimeout(uid, session), timeoutT);
        } catch (Throwable t) {
            terminateTransportFailure(session);
            return;
        }

        if (handleOpt.isPresent()) {
            SignPromptTransport.SignHandle handle = handleOpt.get();
            // Edytor otwieramy dopiero w następnym ticku - klient zna stan bloku. Wynik jest typizowany:
            // FAILED -> natychmiastowy czysty fallback na czat (nie TRANSPORT_FAILED, bo czat działa).
            try {
                transport.runLater(() -> {
                    Session cur = sessions.get(uid);
                    if (cur != session || !player.isOnline()) {
                        return;
                    }
                    SignPromptTransport.OpenResult opened;
                    try {
                        opened = transport.openEditor(player, handle);
                    } catch (Throwable t) {
                        opened = SignPromptTransport.OpenResult.FAILED;
                    }
                    if (opened != SignPromptTransport.OpenResult.OPENED) {
                        fallbackToChat(uid, session, prompt);
                    }
                }, 1L);
            } catch (Throwable t) {
                // Nie da się zaplanować otwarcia edytora -> od razu fallback na czat (czat dostępny).
                fallbackToChat(uid, session, prompt);
            }
            // Po krótkiej chwili polski hint na czacie (gdy edytor nie ruszył cicho). Best-effort.
            try {
                session.fallbackTask = transport.runLater(
                        () -> onFallbackHint(uid, session, prompt), fallbackTicks);
            } catch (Throwable ignored) {
                // brak hintu nie wywraca flow - czat i tak jest przechwytywany od startu sesji
            }
        } else {
            // Brak bezpiecznej lokalizacji -> od razu tryb czatu.
            sendChatPrompt(player, prompt);
        }
    }

    /**
     * Nieudany edytor (lub brak możliwości zaplanowania otwarcia), ale czat jest dostępny:
     * natychmiastowy, czysty fallback na czat. Anulujemy opóźniony hint (już promptujemy), sesja żyje,
     * timeout obowiązuje, a wpis z czatu jest przechwytywany prywatnie od początku sesji.
     */
    private void fallbackToChat(UUID uid, Session session, String prompt) {
        Session cur = sessions.get(uid);
        if (cur != session || session.completed.get()) {
            return;
        }
        cancel(session.fallbackTask);
        if (session.player.isOnline()) {
            sendChatPrompt(session.player, prompt);
        }
    }

    /**
     * Terminalne domknięcie, gdy nie da się bezpiecznie dostarczyć promptu (np. scheduler odrzucił
     * zaplanowanie timeoutu przy wyłączaniu). Przywraca blok (idempotentnie - bez ghost-signa) i woła
     * callback z {@link PromptOutcome#TRANSPORT_FAILED} bezpośrednio (bez zależności od schedulera).
     */
    private void terminateTransportFailure(Session s) {
        if (!s.completed.compareAndSet(false, true)) {
            return;
        }
        cancel(s.timeoutTask);
        cancel(s.fallbackTask);
        s.pendingTerminal = PromptResult.failure(PromptOutcome.TRANSPORT_FAILED);
        // Wołane synchronicznie na wątku głównym (promptString) - dostarczamy od razu, dokładnie raz.
        runTerminal(s);
    }

    public void promptNumber(Player player, String prompt,
                             Consumer<PromptResult<java.math.BigDecimal>> callback) {
        promptString(player, prompt, res -> {
            if (!res.isSuccess()) {
                callback.accept(PromptResult.failure(res.outcome()));
                return;
            }
            String raw = res.value();
            if (raw == null || raw.isBlank()) {
                callback.accept(PromptResult.failure(PromptOutcome.INVALID));
                return;
            }
            try {
                callback.accept(PromptResult.success(
                        new java.math.BigDecimal(raw.trim().replace(",", "."))));
            } catch (NumberFormatException ex) {
                callback.accept(PromptResult.failure(PromptOutcome.INVALID));
            }
        });
    }

    public void promptLong(Player player, String prompt, Consumer<PromptResult<Long>> callback) {
        promptString(player, prompt, res -> {
            if (!res.isSuccess()) {
                callback.accept(PromptResult.failure(res.outcome()));
                return;
            }
            String raw = res.value();
            try {
                long v = Long.parseLong(raw == null ? "" : raw.trim());
                // Wartość <= 0 jest poza dziedziną (ilość musi być dodatnia) -> INVALID, nie „cancel".
                callback.accept(v > 0 ? PromptResult.success(v)
                        : PromptResult.failure(PromptOutcome.INVALID));
            } catch (NumberFormatException ex) {
                callback.accept(PromptResult.failure(PromptOutcome.INVALID));
            }
        });
    }

    // ---------------------------------------------------------------- events

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = false)
    public void onSignChange(SignChangeEvent event) {
        Session s = sessions.get(event.getPlayer().getUniqueId());
        if (s == null || s.handle == null) return;
        if (!event.getBlock().getLocation().equals(s.handle.location())) return;
        event.setCancelled(true);
        List<String> submitted = new java.util.ArrayList<>(4);
        for (int i = 0; i < 4; i++) {
            submitted.add(event.getLine(i));
        }
        handleSignInput(event.getPlayer(), submitted);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onChat(AsyncChatEvent event) {
        Session s = sessions.get(event.getPlayer().getUniqueId());
        if (s == null) return;
        // Przechwytujemy prywatnie - wiadomość NIE trafia do publicznego czatu.
        event.setCancelled(true);
        String plain = PlainTextComponentSerializer.plainText().serialize(event.message());
        handleChatInput(event.getPlayer(), plain);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        handleQuit(event.getPlayer().getUniqueId());
    }

    // Ochrona pozycji tymczasowej tabliczki: nikt nie może jej rozbić ani zabudować,
    // dopóki trwa aktywna sesja - inaczej restore nadpisałby cudzą zmianę albo zniknąłby blok.
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        if (isActiveSessionSign(event.getBlock().getLocation())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onBlockPlace(BlockPlaceEvent event) {
        if (isActiveSessionSign(event.getBlock().getLocation())) {
            event.setCancelled(true);
        }
    }

    private boolean isActiveSessionSign(Location loc) {
        if (loc == null) return false;
        for (Session s : sessions.values()) {
            if (s.handle != null && loc.equals(s.handle.location())) {
                return true;
            }
        }
        return false;
    }

    /** Logout: przywróć blok, ale nie wołamy callbacku (gracz offline). */
    void handleQuit(UUID uid) {
        abort(uid);
    }

    // ---------------------------------------------------------------- state machine (package-private, testable)

    void handleSignInput(Player player, List<String> submittedLines) {
        Session s = sessions.get(player.getUniqueId());
        if (s == null) return;
        String input = pickPlayerInput(submittedLines, s.preparedLines);
        if (input.isEmpty()) {
            complete(s, PromptOutcome.CANCELLED, null);   // puste pole / same dekoracje = rezygnacja
        } else {
            complete(s, PromptOutcome.SUCCESS, input);
        }
    }

    void handleChatInput(Player player, String message) {
        Session s = sessions.get(player.getUniqueId());
        if (s == null) return;
        String trimmed = message == null ? "" : message.trim();
        if (trimmed.isEmpty() || trimmed.equalsIgnoreCase("anuluj") || trimmed.equalsIgnoreCase("cancel")) {
            complete(s, PromptOutcome.CANCELLED, null);
        } else {
            complete(s, PromptOutcome.SUCCESS, trimmed);
        }
    }

    private void onTimeout(UUID uid, Session session) {
        Session cur = sessions.get(uid);
        if (cur != session) return;
        complete(session, PromptOutcome.TIMEOUT, null);
    }

    private void onFallbackHint(UUID uid, Session session, String prompt) {
        Session cur = sessions.get(uid);
        if (cur != session || session.completed.get()) return;
        if (session.player.isOnline()) {
            transport.sendMessage(session.player,
                    messages.renderNoPrefix("common.input-sign-fallback-hint", Map.of()));
            transport.sendMessage(session.player,
                    messages.renderNoPrefix("common.input-cancel-hint", Map.of()));
        }
    }

    /**
     * Sukces / anulacja / timeout / błędna wartość: zdecyduj terminalny wynik (raz), a następnie dostarcz
     * cleanup (przywrócenie bloku + callback) na wątku głównym. KLUCZOWE (punkt #2): sesja NIE jest
     * usuwana tutaj - dopiero {@link #runTerminal} usuwa ją, gdy cleanup naprawdę się wykona. Dzięki temu
     * odrzucenie zadania przez scheduler (przy wyłączaniu) NIE gubi promptu: sesja zostaje z ustalonym
     * {@code pendingTerminal}, a reload/disable (na wątku głównym) dokończą ją dokładnie raz. Wpis z
     * async-chat może wywołać tę metodę poza wątkiem głównym - blok zmieniamy tylko w runTerminal (main).
     */
    private void complete(Session s, PromptOutcome outcome, String rawInput) {
        if (!s.completed.compareAndSet(false, true)) return;   // callback co najwyżej raz (zwycięzca CAS)
        cancel(s.timeoutTask);
        cancel(s.fallbackTask);
        s.pendingTerminal = outcome == PromptOutcome.SUCCESS
                ? PromptResult.success(rawInput)
                : PromptResult.failure(outcome);
        try {
            transport.runMain(() -> runTerminal(s));
        } catch (Throwable t) {
            // Scheduler odrzucił zadanie (plugin wyłączany): NIE gubimy promptu ani nie ruszamy bloku poza
            // wątkiem głównym. Zostawiamy zakończoną sesję zarejestrowaną z pendingTerminal - shutdown()/
            // cancelAll() na wątku głównym dostarczą ją terminalnie dokładnie raz.
            if (plugin != null) {
                plugin.getLogger().warning("Wprowadzanie przez tabliczkę: nie można zaplanować domknięcia "
                        + "na wątku głównym - dokończy je reload/disable");
            }
        }
    }

    /**
     * Terminalny cleanup wykonywany DOKŁADNIE raz (strażnik {@code cleaned}): wyrejestrowanie sesji,
     * przywrócenie bloku (tylko na wątku głównym) i dostarczenie callbacku, jeśli był ustalony wynik.
     * Wołane z {@link #complete} (przez runMain) oraz z {@link #abort} (reload/disable na wątku głównym).
     */
    private void runTerminal(Session s) {
        if (!s.cleaned.compareAndSet(false, true)) return;
        sessions.remove(s.playerId, s);
        restoreOnce(s);
        PromptResult<String> result = s.pendingTerminal;
        if (result != null) {
            try {
                s.callback.accept(result);
            } catch (Throwable t) {
                if (plugin != null) {
                    plugin.getLogger().log(java.util.logging.Level.WARNING,
                            "Wprowadzanie przez tabliczkę: callback promptu rzucił wyjątek", t);
                }
            }
        }
    }

    /**
     * Zakończ sesję na wątku głównym (replace / logout / reload / disable). Zwykle BEZ callbacku, ale jeśli
     * sesja ma już ustalony wynik, którego dostarczenie odrzucił scheduler ({@code pendingTerminal != null}),
     * dostarczamy go teraz (nie gubimy promptu). Przywrócenie i callback dokładnie raz (strażnik cleaned).
     */
    private void abort(UUID uid) {
        Session s = sessions.remove(uid);
        if (s == null) return;
        s.completed.set(true);
        cancel(s.timeoutTask);
        cancel(s.fallbackTask);
        if (!s.cleaned.compareAndSet(false, true)) return;
        restoreOnce(s);
        PromptResult<String> pending = s.pendingTerminal;
        if (pending != null) {
            try {
                s.callback.accept(pending);
            } catch (Throwable t) {
                if (plugin != null) {
                    plugin.getLogger().log(java.util.logging.Level.WARNING,
                            "Wprowadzanie przez tabliczkę: callback promptu rzucił wyjątek", t);
                }
            }
        }
    }

    private void restoreOnce(Session s) {
        if (s.handle == null) return;
        try {
            s.handle.restore();
        } catch (Throwable ignored) {
            // uchwyt jest idempotentny; ewentualny błąd nie może wywrócić flow
        }
    }

    private void sendChatPrompt(Player player, String prompt) {
        transport.sendMessage(player, messages.renderNoPrefix("common.input-chat-prompt",
                MessageFactory.placeholders("prompt", prompt == null ? "" : prompt)));
        transport.sendMessage(player, messages.renderNoPrefix("common.input-cancel-hint", Map.of()));
    }

    private void cancel(SignPromptTransport.Cancellable c) {
        if (c != null) {
            try {
                c.cancel();
            } catch (Throwable ignored) {
            }
        }
    }

    /**
     * Przygotowane 4 linie tabliczki. Indeks 0 = pusta LINIA WEJŚCIA gracza; linie 1..3 to DEKORACJE
     * (caret, prompt, podpowiedź „anuluj"). Package-private, by test mógł użyć DOKŁADNIE tych linii i
     * zasymulować niezmienioną, odesłaną tabliczkę.
     */
    List<String> buildSignLines(String prompt) {
        // Tabliczka renderuje tekst dosłownie (Component.text) - dlatego USUWAMY legacy-kody koloru
        // (&a/§a), by nie pokazywały się jako widoczne „&a" na tabliczce.
        return List.of(
                "",
                stripColors(messages.raw("common.input-sign-caret", null)),
                safeTrim(stripColors(prompt == null ? "" : prompt), 15),
                stripColors(messages.raw("common.input-sign-cancel", null))
        );
    }

    /** Usuwa legacy-kody koloru/formatu (&a, §a) - dla czystego tekstu na tabliczce. */
    static String stripColors(String s) {
        return s == null ? "" : s.replaceAll("(?i)[&§][0-9A-FK-OR]", "");
    }

    /**
     * Wybiera RZECZYWISTĄ wartość wpisaną przez gracza (punkt #9). Dekoracje przygotowanej tabliczki
     * (caret, prompt, „anuluj" - linie 1..3) NIGDY nie są traktowane jako wartość. Zwraca pierwszą
     * odesłaną linię, która jest niepusta i NIE jest żadną z dekoracji; inaczej pusty string
     * (= rezygnacja). Dzięki temu niezmieniona, odesłana tabliczka daje CANCELLED, a nie INVALID/SUCCESS
     * (np. z caretu {@code ^^^^^^^^^^^^^^^^}).
     */
    static String pickPlayerInput(List<String> submitted, List<String> prepared) {
        java.util.Set<String> decoration = new java.util.HashSet<>();
        if (prepared != null) {
            for (int i = 1; i < prepared.size(); i++) {   // indeks 0 to linia wejścia, pomijamy
                String d = prepared.get(i) == null ? "" : prepared.get(i).trim();
                if (!d.isEmpty()) {
                    decoration.add(d);
                }
            }
        }
        if (submitted != null) {
            for (String line : submitted) {
                String t = line == null ? "" : line.trim();
                if (!t.isEmpty() && !decoration.contains(t)) {
                    return t;
                }
            }
        }
        return "";
    }

    private String safeTrim(String s, int max) {
        if (s == null) return "";
        return s.length() > max ? s.substring(0, max) : s;
    }

    boolean hasSession(UUID uid) {
        return sessions.containsKey(uid);
    }

    int activeSessions() {
        return sessions.size();
    }

    private static final class Session {
        final UUID playerId;
        final Player player;
        final SignPromptTransport.SignHandle handle;
        final List<String> preparedLines;
        final Consumer<PromptResult<String>> callback;
        /** Terminalny WYNIK został zdecydowany (blokuje podwójne przetworzenie wejścia/timeoutu). */
        final AtomicBoolean completed = new AtomicBoolean(false);
        /** Terminalny CLEANUP (restore bloku + callback + wyrejestrowanie) już wykonany (at-most-once). */
        final AtomicBoolean cleaned = new AtomicBoolean(false);
        /**
         * Wynik do dostarczenia w cleanupie. Ustawiany PRZED próbą planowania na wątek główny, więc gdy
         * scheduler odrzuci zadanie, reload/disable (na wątku głównym) dokończą dostarczenie zamiast je
         * zgubić. {@code null} = brak callbacku (abort przy replace/logout/reload/disable bez decyzji).
         */
        volatile PromptResult<String> pendingTerminal;
        SignPromptTransport.Cancellable timeoutTask;
        SignPromptTransport.Cancellable fallbackTask;

        Session(UUID playerId, Player player, SignPromptTransport.SignHandle handle,
                List<String> preparedLines, Consumer<PromptResult<String>> callback) {
            this.playerId = playerId;
            this.player = player;
            this.handle = handle;
            this.preparedLines = preparedLines;
            this.callback = callback;
        }
    }
}
