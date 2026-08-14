package hex.auctionbazaar.gui;

import hex.auctionbazaar.config.MessagesConfig;
import hex.auctionbazaar.util.MessageFactory;
import net.kyori.adventure.text.Component;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Punkt #1: maszyna stanów promptu (transport zamockowany).
 * Sprawdza: pending-sesja przed otwarciem, opóźnione otwarcie, chat-fallback,
 * callback dokładnie raz, przywrócenie bloku przy sukcesie/timeout/logout/reload/disable.
 */
class SignPromptStateMachineTest {

    // Zmienne (nie final) - test reloadu może je zmienić przed nową sesją.
    private long fallbackTicks = 80L;
    private long timeoutTicks = 600L;

    private ServerMock server;
    private PlayerMock player;
    private FakeTransport transport;
    private SignPrompt prompt;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        player = server.addPlayer("Tester");
        transport = new FakeTransport();
        MessageFactory messages = new MessageFactory(
                () -> new MessagesConfig(Map.of(
                        "common.input-sign-caret", "^^^",
                        "common.input-sign-cancel", "anuluj",
                        "common.input-chat-prompt", "Wpisz: <prompt>",
                        "common.input-cancel-hint", "anuluj = koniec",
                        "common.input-sign-fallback-hint", "Wpisz na czacie")),
                () -> "");
        prompt = new SignPrompt(transport, messages,
                () -> fallbackTicks, () -> timeoutTicks, true);
    }

    @AfterEach
    void tearDown() {
        if (MockBukkit.isMocked()) {
            MockBukkit.unmock();
        }
    }

    @Test
    void pendingSessionRegisteredBeforeDelayedOpen() {
        prompt.promptNumber(player, "cena", v -> {});
        // Kolejność: zamknięcie GUI, przygotowanie tabliczki - PRZED otwarciem edytora.
        assertEquals(List.of("closeUi", "prepareSign"), transport.events);
        assertEquals(0, transport.openEditorCalls, "edytor otwierany dopiero w następnym ticku");
        assertTrue(prompt.hasSession(player.getUniqueId()), "pending-sesja zarejestrowana przed openem");

        transport.runScheduled(1L);   // tick otwarcia edytora
        assertEquals(1, transport.openEditorCalls);
    }

    @Test
    void activeSessionSignIsRecognizedForOpeningCompatibility() {
        Location signLoc = new Location(player.getWorld(), 10, 80, 10);
        transport.handleLocation = signLoc;
        prompt.promptNumber(player, "cena", v -> {});

        assertTrue(prompt.isActiveSessionSign(player.getUniqueId(), signLoc),
                "aktywna tabliczka promptu gracza jest rozpoznana");
        assertFalse(prompt.isActiveSessionSign(UUID.randomUUID(), signLoc),
                "tabliczka innego gracza nie jest uznana za aktywną sesję");
        assertFalse(prompt.isActiveSessionSign(player.getUniqueId(), new Location(player.getWorld(), 11, 80, 10)),
                "inna lokalizacja nie jest tabliczką tej sesji");
    }

    @Test
    void signInputCompletesCallbackExactlyOnce() {
        AtomicReference<BigDecimal> got = new AtomicReference<>();
        AtomicInteger calls = new AtomicInteger();
        prompt.promptNumber(player, "cena", res -> { got.set(res.value()); calls.incrementAndGet(); });

        prompt.handleSignInput(player, List.of("123", "", "", ""));
        assertEquals(1, calls.get());
        assertEquals(new BigDecimal("123"), got.get());
        assertEquals(1, transport.handle.restoreCount, "blok przywrócony dokładnie raz");
        assertFalse(prompt.hasSession(player.getUniqueId()));

        // Kolejne wejścia nie odpalają drugi raz callbacku ani restore.
        prompt.handleSignInput(player, List.of("999", "", "", ""));
        prompt.handleChatInput(player, "50");
        transport.runScheduled(timeoutTicks);
        assertEquals(1, calls.get());
        assertEquals(1, transport.handle.restoreCount);
    }

    @Test
    void chatFallbackHintThenChatAcceptsInput() {
        AtomicReference<BigDecimal> got = new AtomicReference<>();
        prompt.promptNumber(player, "cena", res -> got.set(res.value()));

        transport.runScheduled(fallbackTicks);
        assertFalse(transport.messages.isEmpty(), "po chwili wysłany hint na czat");

        prompt.handleChatInput(player, "50");
        assertEquals(new BigDecimal("50"), got.get());
        assertEquals(1, transport.handle.restoreCount);
    }

    @Test
    void chatOnlyWhenNoSafeLocation() {
        transport.prepareReturnsHandle = false;
        AtomicReference<BigDecimal> got = new AtomicReference<>();
        prompt.promptNumber(player, "cena", res -> got.set(res.value()));

        assertEquals(0, transport.openEditorCalls);
        assertFalse(transport.messages.isEmpty(), "od razu prompt na czat");
        prompt.handleChatInput(player, "7");
        assertEquals(new BigDecimal("7"), got.get());
    }

    @Test
    void timeoutRestoresAndCallsBackNull() {
        AtomicInteger calls = new AtomicInteger();
        AtomicReference<BigDecimal> got = new AtomicReference<>(new BigDecimal("-1"));
        prompt.promptNumber(player, "cena", res -> { got.set(res.value()); calls.incrementAndGet(); });

        transport.runScheduled(timeoutTicks);
        assertEquals(1, calls.get());
        assertEquals(null, got.get());
        assertEquals(1, transport.handle.restoreCount);
    }

    @Test
    void cancelWordCompletesWithNull() {
        AtomicInteger calls = new AtomicInteger();
        AtomicReference<BigDecimal> got = new AtomicReference<>(new BigDecimal("-1"));
        prompt.promptNumber(player, "cena", res -> { got.set(res.value()); calls.incrementAndGet(); });
        prompt.handleChatInput(player, "anuluj");
        assertEquals(1, calls.get());
        assertEquals(null, got.get());
        assertEquals(1, transport.handle.restoreCount);
    }

    @Test
    void logoutRestoresWithoutCallback() {
        AtomicInteger calls = new AtomicInteger();
        prompt.promptNumber(player, "cena", v -> calls.incrementAndGet());
        prompt.handleQuit(player.getUniqueId());
        assertEquals(0, calls.get(), "logout nie woła callbacku");
        assertEquals(1, transport.handle.restoreCount);
        assertFalse(prompt.hasSession(player.getUniqueId()));
    }

    @Test
    void reloadCancelAllRestores() {
        AtomicInteger calls = new AtomicInteger();
        prompt.promptNumber(player, "cena", v -> calls.incrementAndGet());
        prompt.cancelAll();
        assertEquals(0, calls.get());
        assertEquals(1, transport.handle.restoreCount);
    }

    @Test
    void disableShutdownRestores() {
        prompt.promptNumber(player, "cena", v -> {});
        FakeHandle h = transport.handle;
        prompt.shutdown();
        assertEquals(1, h.restoreCount);
    }

    @Test
    void secondPromptReplacesAndRestoresFirst() {
        prompt.promptNumber(player, "cena", v -> {});
        FakeHandle first = transport.handle;
        prompt.promptNumber(player, "cena2", v -> {});
        FakeHandle second = transport.handle;

        assertEquals(1, first.restoreCount, "poprzednia sesja przywrócona");
        assertEquals(0, second.restoreCount, "nowa sesja aktywna");
        assertTrue(prompt.hasSession(player.getUniqueId()));
        assertEquals(1, prompt.activeSessions(), "maks. jedna sesja na gracza");
    }

    @Test
    void reloadedTimingsApplyToNewSession() {
        // Stara sesja z domyślnymi wartościami (80/600).
        prompt.promptNumber(player, "cena", v -> {});
        assertTrue(transport.hasScheduled(80L), "stara sesja: fallback = 80");
        long oldTimeout = timeoutTicks;

        // /reload -> nowe wartości w configu.
        fallbackTicks = 40L;
        timeoutTicks = 200L;
        transport.scheduled.clear();

        // Nowa sesja (zastępuje starą) natychmiast używa nowych wartości.
        prompt.promptNumber(player, "cena", v -> {});
        assertTrue(transport.hasScheduled(40L), "nowa sesja: fallback = 40");
        assertTrue(transport.hasScheduled(200L), "nowa sesja: timeout = 200");
        assertFalse(transport.hasScheduled(oldTimeout), "nie używa starego timeoutu");
    }

    // ---------------------------------------------------------------- #9: rozłączne wyniki promptu

    @Test
    void invalidInputYieldsInvalidOutcome() {
        AtomicReference<SignPrompt.PromptOutcome> outcome = new AtomicReference<>();
        prompt.promptNumber(player, "cena", res -> outcome.set(res.outcome()));
        prompt.handleSignInput(player, List.of("abc", "", "", ""));   // nie-liczba
        assertEquals(SignPrompt.PromptOutcome.INVALID, outcome.get());
        assertEquals(1, transport.handle.restoreCount, "blok przywrócony mimo błędnej wartości");
    }

    @Test
    void emptySignInputYieldsCancelled() {
        AtomicReference<SignPrompt.PromptOutcome> outcome = new AtomicReference<>();
        prompt.promptString(player, "cena", res -> outcome.set(res.outcome()));
        prompt.handleSignInput(player, List.of("", "", "", ""));
        assertEquals(SignPrompt.PromptOutcome.CANCELLED, outcome.get());
    }

    @Test
    void cancelWordYieldsCancelledDistinctFromTimeout() {
        AtomicReference<SignPrompt.PromptOutcome> outcome = new AtomicReference<>();
        prompt.promptNumber(player, "cena", res -> outcome.set(res.outcome()));
        prompt.handleChatInput(player, "anuluj");
        assertEquals(SignPrompt.PromptOutcome.CANCELLED, outcome.get());
    }

    @Test
    void timeoutYieldsTimeoutOutcome() {
        AtomicReference<SignPrompt.PromptOutcome> outcome = new AtomicReference<>();
        prompt.promptNumber(player, "cena", res -> outcome.set(res.outcome()));
        transport.runScheduled(timeoutTicks);
        assertEquals(SignPrompt.PromptOutcome.TIMEOUT, outcome.get());
    }

    @Test
    void nonPositiveLongYieldsInvalid() {
        AtomicReference<SignPrompt.PromptOutcome> outcome = new AtomicReference<>();
        prompt.promptLong(player, "ilość", res -> outcome.set(res.outcome()));
        prompt.handleSignInput(player, List.of("0", "", "", ""));   // 0 poza dziedziną (musi być > 0)
        assertEquals(SignPrompt.PromptOutcome.INVALID, outcome.get());
    }

    @Test
    void successCarriesTypedValue() {
        AtomicReference<SignPrompt.PromptResult<BigDecimal>> res = new AtomicReference<>();
        prompt.promptNumber(player, "cena", res::set);
        prompt.handleSignInput(player, List.of("12.5", "", "", ""));
        assertEquals(SignPrompt.PromptOutcome.SUCCESS, res.get().outcome());
        assertEquals(new BigDecimal("12.5"), res.get().value());
    }

    @Test
    void offlinePlayerYieldsTransportFailedWithoutSession() {
        AtomicReference<SignPrompt.PromptOutcome> outcome = new AtomicReference<>();
        player.disconnect();   // gracz offline -> nie da się dostarczyć promptu
        prompt.promptNumber(player, "cena", res -> outcome.set(res.outcome()));
        assertEquals(SignPrompt.PromptOutcome.TRANSPORT_FAILED, outcome.get());
        assertFalse(prompt.hasSession(player.getUniqueId()), "brak sesji przy braku transportu");
    }

    // ---------------------------------------------------------------- #9: pusta tabliczka + transport

    @Test
    void unchangedPreparedBoardYieldsCancelledNotInvalid() {
        AtomicReference<SignPrompt.PromptOutcome> outcome = new AtomicReference<>();
        prompt.promptNumber(player, "cena", res -> outcome.set(res.outcome()));
        // DOKŁADNIE te linie, które przygotował buildSignLines: ["", "^^^", "cena", "anuluj"].
        List<String> prepared = prompt.buildSignLines("cena");
        // Symulujemy niezmienioną, odesłaną tabliczkę (gracz nic nie wpisał).
        prompt.handleSignInput(player, prepared);
        assertEquals(SignPrompt.PromptOutcome.CANCELLED, outcome.get(),
                "niezmieniona tabliczka = rezygnacja; caret/prompt/anuluj nie są wartością");
    }

    @Test
    void caretLineAloneIsNeverTreatedAsValue() {
        // Nawet gdy w linii wejścia (0) jest pusto, a caret trafi gdzieś indziej - to nie jest wartość.
        List<String> prepared = List.of("", "^^^", "cena", "anuluj");
        assertEquals("", SignPrompt.pickPlayerInput(List.of("", "^^^", "cena", "anuluj"), prepared));
        assertEquals("100", SignPrompt.pickPlayerInput(List.of("100", "^^^", "cena", "anuluj"), prepared),
                "rzeczywista linia gracza wybrana");
    }

    @Test
    void editorFailureImmediatelyFallsBackToChat() {
        transport.openResult = SignPromptTransport.OpenResult.FAILED;
        AtomicReference<BigDecimal> got = new AtomicReference<>();
        prompt.promptNumber(player, "cena", res -> got.set(res.value()));
        assertTrue(transport.messages.isEmpty(), "przed tickiem otwarcia brak promptu na czacie");

        transport.runScheduled(1L);   // tick otwarcia edytora -> FAILED
        assertFalse(transport.messages.isEmpty(), "nieudany edytor -> natychmiastowy prompt na czacie (nie TRANSPORT_FAILED)");

        prompt.handleChatInput(player, "42");
        assertEquals(new BigDecimal("42"), got.get(), "wpis z czatu akceptowany po fallbacku");
    }

    // ---------------------------------------------------------------- fakes

    private static final class FakeTransport implements SignPromptTransport {
        boolean prepareReturnsHandle = true;
        SignPromptTransport.OpenResult openResult = SignPromptTransport.OpenResult.OPENED;
        Location handleLocation;
        final List<String> events = new ArrayList<>();
        final List<Scheduled> scheduled = new ArrayList<>();
        final List<Component> messages = new ArrayList<>();
        FakeHandle handle;
        int openEditorCalls = 0;

        record Scheduled(Runnable task, long ticks, boolean[] cancelled) {}

        @Override
        public void closeUi(Player player) {
            events.add("closeUi");
        }

        @Override
        public Optional<SignHandle> prepareSign(Player player, List<String> lines) {
            events.add("prepareSign");
            if (!prepareReturnsHandle) return Optional.empty();
            handle = new FakeHandle(handleLocation);
            return Optional.of(handle);
        }

        @Override
        public OpenResult openEditor(Player player, SignHandle h) {
            events.add("openEditor");
            openEditorCalls++;
            return openResult;
        }

        @Override
        public Cancellable runLater(Runnable task, long ticks) {
            boolean[] cancelled = {false};
            scheduled.add(new Scheduled(task, ticks, cancelled));
            return () -> cancelled[0] = true;
        }

        @Override
        public void runMain(Runnable task) {
            task.run();
        }

        @Override
        public void sendMessage(Player player, Component message) {
            messages.add(message);
        }

        void runScheduled(long ticks) {
            for (Scheduled s : new ArrayList<>(scheduled)) {
                if (s.ticks == ticks && !s.cancelled[0]) {
                    s.task.run();
                }
            }
        }

        boolean hasScheduled(long ticks) {
            return scheduled.stream().anyMatch(s -> s.ticks == ticks && !s.cancelled[0]);
        }
    }

    private static final class FakeHandle implements SignPromptTransport.SignHandle {
        private final Location location;
        int restoreCount = 0;

        FakeHandle(Location location) {
            this.location = location;
        }

        @Override
        public Location location() {
            return location;
        }

        @Override
        public void restore() {
            restoreCount++;
        }
    }
}
