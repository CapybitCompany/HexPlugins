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
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Punkt #2: wyścig domknięcia promptu. Gdy {@code transport.runMain(...)} odrzuci zadanie (np. wpis z
 * AsyncChat przy wyłączaniu), prompt NIE może zostać zgubiony ani zostawić ghost-tabliczki: sesja
 * pozostaje zarejestrowana z ustalonym wynikiem, a reload/disable (na wątku głównym) dostarczają callback
 * i przywracają blok DOKŁADNIE raz.
 */
class SignPromptCompletionRaceTest {

    private ServerMock server;
    private PlayerMock player;
    private RaceTransport transport;
    private SignPrompt prompt;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        player = server.addPlayer("Tester");
        transport = new RaceTransport();
        MessageFactory messages = new MessageFactory(
                () -> new MessagesConfig(Map.of(
                        "common.input-sign-caret", "^^^",
                        "common.input-sign-cancel", "anuluj",
                        "common.input-chat-prompt", "Wpisz: <prompt>",
                        "common.input-cancel-hint", "anuluj = koniec",
                        "common.input-sign-fallback-hint", "Wpisz na czacie")),
                () -> "");
        prompt = new SignPrompt(transport, messages, () -> 80L, () -> 600L, true);
    }

    @AfterEach
    void tearDown() {
        if (MockBukkit.isMocked()) {
            MockBukkit.unmock();
        }
    }

    @Test
    void runMainRejectionFromChatIsRecoveredByReloadExactlyOnce() {
        AtomicReference<BigDecimal> got = new AtomicReference<>();
        AtomicInteger calls = new AtomicInteger();
        prompt.promptNumber(player, "cena", res -> { got.set(res.value()); calls.incrementAndGet(); });

        // Scheduler odrzuca domknięcie na wątku głównym (symulacja wyłączania).
        transport.runMainThrows = true;
        prompt.handleChatInput(player, "50");   // wpis z async-chat -> complete -> runMain odrzucony

        // Prompt NIE zgubiony i NIE dostarczony przedwcześnie: sesja żyje, callback i restore jeszcze nie.
        assertEquals(0, calls.get(), "callback nie odpalony, dopóki nie ma bezpiecznego domknięcia");
        assertEquals(0, transport.handle.restoreCount, "blok nie przywrócony poza wątkiem głównym");
        assertTrue(prompt.hasSession(player.getUniqueId()), "sesja zostaje do posprzątania przez reload/disable");

        // Reload (na wątku głównym) dokańcza: callback + restore DOKŁADNIE raz.
        prompt.cancelAll();
        assertEquals(1, calls.get(), "callback dostarczony dokładnie raz");
        assertEquals(new BigDecimal("50"), got.get(), "z poprawnym, wcześniej ustalonym wynikiem");
        assertEquals(1, transport.handle.restoreCount, "blok przywrócony dokładnie raz");
        assertFalse(prompt.hasSession(player.getUniqueId()));

        // Kolejne sprzątanie (disable) nie dubluje callbacku ani restore.
        prompt.shutdown();
        assertEquals(1, calls.get());
        assertEquals(1, transport.handle.restoreCount);
    }

    @Test
    void runMainRejectionRecoveredByDisableExactlyOnce() {
        AtomicInteger calls = new AtomicInteger();
        prompt.promptNumber(player, "cena", res -> calls.incrementAndGet());

        transport.runMainThrows = true;
        prompt.handleChatInput(player, "anuluj");   // CANCELLED, ale runMain odrzucony

        assertEquals(0, calls.get());
        assertTrue(prompt.hasSession(player.getUniqueId()));

        prompt.shutdown();   // disable dokańcza
        assertEquals(1, calls.get(), "callback dostarczony przez disable dokładnie raz");
        assertEquals(1, transport.handle.restoreCount);
    }

    @Test
    void normalChatCompletionStillDeliversOnce() {
        AtomicReference<BigDecimal> got = new AtomicReference<>();
        AtomicInteger calls = new AtomicInteger();
        prompt.promptNumber(player, "cena", res -> { got.set(res.value()); calls.incrementAndGet(); });

        prompt.handleChatInput(player, "42");   // runMain działa -> natychmiastowe domknięcie

        assertEquals(1, calls.get());
        assertEquals(new BigDecimal("42"), got.get());
        assertEquals(1, transport.handle.restoreCount);
        assertFalse(prompt.hasSession(player.getUniqueId()));

        // Późniejszy reload nie dubluje.
        prompt.cancelAll();
        assertEquals(1, calls.get());
        assertEquals(1, transport.handle.restoreCount);
    }

    // ---------------------------------------------------------------- fake transport

    private static final class RaceTransport implements SignPromptTransport {
        boolean runMainThrows = false;
        final List<Runnable> scheduled = new ArrayList<>();
        final FakeHandle handle = new FakeHandle();

        @Override
        public void closeUi(Player player) {
        }

        @Override
        public Optional<SignHandle> prepareSign(Player player, List<String> lines) {
            return Optional.of(handle);
        }

        @Override
        public OpenResult openEditor(Player player, SignHandle h) {
            return OpenResult.OPENED;
        }

        @Override
        public Cancellable runLater(Runnable task, long ticks) {
            scheduled.add(task);
            return () -> scheduled.remove(task);
        }

        @Override
        public void runMain(Runnable task) {
            if (runMainThrows) {
                throw new RuntimeException("scheduler odrzucił zadanie (test)");
            }
            task.run();
        }

        @Override
        public void sendMessage(Player player, Component message) {
        }
    }

    private static final class FakeHandle implements SignPromptTransport.SignHandle {
        int restoreCount = 0;

        @Override
        public Location location() {
            return null;
        }

        @Override
        public void restore() {
            restoreCount++;
        }
    }
}
