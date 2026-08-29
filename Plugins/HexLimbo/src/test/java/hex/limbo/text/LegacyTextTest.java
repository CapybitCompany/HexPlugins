package hex.limbo.text;

import hex.limbo.config.MessagesConfig;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the single legacy-colour parser every player-facing HexLimbo string goes through.
 */
class LegacyTextTest {

    private static String plain(Component component) {
        return PlainTextComponentSerializer.plainText().serialize(component);
    }

    @Test
    void parsesASingleColourCode() {
        Component c = LegacyText.parse("&cNieprawidłowe hasło.");
        assertEquals(NamedTextColor.RED, c.color());
        assertEquals("Nieprawidłowe hasło.", plain(c));
    }

    @Test
    void parsesMultipleColoursIntoSeparateStyledParts() {
        Component c = LegacyText.parse("&7Zaloguj się: &f/login <hasło>");

        // Two colour runs become two styled children under a plain container.
        assertEquals(2, c.children().size());
        assertEquals(NamedTextColor.GRAY, c.children().get(0).color(), "explanations are grey");
        assertEquals(NamedTextColor.WHITE, c.children().get(1).color(), "commands are white");
        assertEquals("Zaloguj się: /login <hasło>", plain(c));
        assertFalse(plain(c).contains("&"), "no ampersand may survive into the rendered text");
    }

    @Test
    void parsesBoldTitles() {
        Component c = LegacyText.parse("&a&lZalogowano pomyślnie!");
        assertEquals(NamedTextColor.GREEN, c.color());
        assertEquals(TextDecoration.State.TRUE, c.decoration(TextDecoration.BOLD));
        assertEquals("Zalogowano pomyślnie!", plain(c));
    }

    @Test
    void parsesTheBrandTitle() {
        Component c = LegacyText.parse("&6&lHEX");
        assertEquals(NamedTextColor.GOLD, c.color());
        assertEquals(TextDecoration.State.TRUE, c.decoration(TextDecoration.BOLD));
        assertEquals("HEX", plain(c));
    }

    @Test
    void keepsAngleBracketPlaceholdersLiteral() {
        // MiniMessage would treat <hasło> as an (unknown) tag. The legacy parser must not.
        Component c = LegacyText.parse("&8» &7Nie masz jeszcze konta. Użyj: &f/register <hasło> <hasło>");
        assertTrue(plain(c).contains("<hasło> <hasło>"));
        assertEquals("» Nie masz jeszcze konta. Użyj: /register <hasło> <hasło>", plain(c));
    }

    @Test
    void keepsPolishDiacriticsIntact() {
        Component c = LegacyText.parse("&7ąćęłńóśźż &6ĄĆĘŁŃÓŚŹŻ");
        assertEquals("ąćęłńóśźż ĄĆĘŁŃÓŚŹŻ", plain(c));
    }

    @Test
    void supportsHexColours() {
        Component c = LegacyText.parse("&#ff8800Hex");
        assertEquals(TextColor.color(0xff, 0x88, 0x00), c.color());
        assertEquals("Hex", plain(c));
    }

    @Test
    void nullAndEmptyBecomeEmptyComponents() {
        assertEquals(Component.empty(), LegacyText.parse(null));
        assertEquals(Component.empty(), LegacyText.parse(""));
        assertEquals("", LegacyText.serialize(null));
    }

    @Test
    void roundTripsThroughSerialize() {
        String legacy = "&8» &aZalogowano pomyślnie. &7Miłej gry na &6Hex&7!";
        assertEquals(legacy, LegacyText.serialize(LegacyText.parse(legacy)));
    }

    @Test
    void messagesConfigStillSubstitutesPlaceholdersBeforeParsing() {
        MessagesConfig messages = new MessagesConfig(Map.of(
                "register.password-too-short", "&cHasło musi mieć co najmniej &f{0} &cznaków.",
                "admin.info.field", "&8 • &7{0}: &f{1}"));

        assertEquals("Hasło musi mieć co najmniej 8 znaków.",
                plain(messages.component("register.password-too-short", 8)));
        assertEquals(" • type: PREMIUM",
                plain(messages.component("admin.info.field", "type", "PREMIUM")));
        // format() keeps working untouched for the raw-string call sites.
        assertEquals("&cHasło musi mieć co najmniej &f8 &cznaków.",
                messages.format("register.password-too-short", 8));
    }

    @Test
    void missingKeyFallsBackToTheKeyItself() {
        MessagesConfig messages = new MessagesConfig(Map.of());
        assertEquals("some.missing.key", plain(messages.component("some.missing.key")));
    }
}
