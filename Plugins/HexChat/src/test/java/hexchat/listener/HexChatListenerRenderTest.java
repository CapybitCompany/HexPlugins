package hexchat.listener;

import io.papermc.paper.chat.ChatRenderer;
import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiFunction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;

class HexChatListenerRenderTest {

    // Format zwraca po prostu przekazane "body" — dzięki temu łatwo sprawdzić, jaka treść trafia do renderu.
    private static final BiFunction<Component, Component, Component> IDENTITY_FORMAT =
            (displayName, body) -> body;

    private final Player source = mock(Player.class);
    private final Component displayName = Component.text("Steve");
    private final Component original = Component.text("wiadomość");

    @Test
    void ownFormatUsesOriginalMessageWhenNotCensored() {
        ChatRenderer renderer = HexChatListener.buildRenderer(true, null, null, IDENTITY_FORMAT);

        Component result = renderer.render(source, displayName, original, Audience.empty());

        assertSame(original, result, "Bez cenzury format dostaje oryginalną treść");
    }

    @Test
    void ownFormatUsesCensoredMessageWhenCensored() {
        Component censored = Component.text("***");
        ChatRenderer renderer = HexChatListener.buildRenderer(true, censored, null, IDENTITY_FORMAT);

        Component result = renderer.render(source, displayName, original, Audience.empty());

        assertSame(censored, result, "Format powinien dostać ocenzurowaną treść zamiast oryginału");
    }

    @Test
    void yieldingWithCensorWrapsExistingRendererWithCensoredMessage() {
        Component censored = Component.text("***");
        AtomicReference<Component> passedToExisting = new AtomicReference<>();
        ChatRenderer existing = (src, dn, message, viewer) -> {
            passedToExisting.set(message);
            return dn;
        };

        ChatRenderer renderer = HexChatListener.buildRenderer(false, censored, existing, IDENTITY_FORMAT);
        renderer.render(source, displayName, original, Audience.empty());

        // Istniejący renderer (np. innego pluginu) zostaje użyty, ale z ocenzurowaną treścią.
        assertSame(censored, passedToExisting.get(), "Wrapper podaje istniejącemu rendererowi ocenzurowaną treść");
    }

    @Test
    void yieldingWithoutCensorDoesNotTouchRenderer() {
        ChatRenderer renderer = HexChatListener.buildRenderer(false, null, mock(ChatRenderer.class), IDENTITY_FORMAT);

        assertNull(renderer, "Bez cenzury i bez własnego formatu nie ustawiamy renderera");
    }

    @Test
    void muteTimeTextUsesConfiguredPermanentLabel() {
        // Tekst "na zawsze" nie jest już zaszyty w kodzie — pochodzi z messages.mute-time-permanent.
        assertEquals("na wieki wieków", HexChatListener.muteTimeText(true, 0L, "na wieki wieków"));
        assertEquals("30m", HexChatListener.muteTimeText(false, 1_800_000L, "na wieki wieków"));
    }
}
