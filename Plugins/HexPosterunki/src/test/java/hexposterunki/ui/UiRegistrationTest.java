package hexposterunki.ui;

import hex.core.api.ui.TemplateDefinition;
import hex.core.api.ui.UiService;
import hex.core.api.ui.UiTokens;
import hexposterunki.support.TestUiService;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Finding 1 regression: the Polish defaults must actually resolve through the real HexCore UI
 * service.
 *
 * <p>These tests register {@link PosterunkiUi#defaults()} through the genuine
 * {@code UiServiceImpl}/{@code TemplateRegistry} and render every key the plugin uses. A test
 * against a separately built map would have passed while the server still showed
 * "Missing template".
 */
class UiRegistrationTest {

    private static final PlainTextComponentSerializer PLAIN = PlainTextComponentSerializer.plainText();

    private TestUiService harness;
    private UiService ui;

    @BeforeEach
    void setUp() {
        harness = new TestUiService();
        ui = harness.service();
        ui.registerDefaultsWithArgs(UiKeys.NAMESPACE, PosterunkiUi.defaults());
    }

    private String render(String key) {
        UiTokens tokens = new UiTokens();
        for (String arg : ui.expectedArgs(key)) {
            tokens.put(arg, "X");
        }
        return PLAIN.serialize(ui.render(key, tokens));
    }

    @Test
    void everyKeyThePluginRendersIsRegistered() {
        List<String> missing = new ArrayList<>();
        for (String key : UiKeys.ALL) {
            if (render(key).contains("Missing template")) {
                missing.add(key);
            }
        }
        assertTrue(missing.isEmpty(), "Nierozwiązane klucze UI: " + missing);
    }

    @Test
    void defaultsAndKeysCoverExactlyTheSameSet() {
        assertEquals(PosterunkiUi.defaults().keySet(), java.util.Set.copyOf(UiKeys.ALL),
                "UiKeys.ALL i zarejestrowane szablony muszą się pokrywać");
    }

    @Test
    void shortKeysWouldNotResolveAndAreTheBugThisTestGuardsAgainst() {
        TestUiService other = new TestUiService();
        Map<String, TemplateDefinition> shortKeys = Map.of(
                "announce.activated", TemplateDefinition.of("<white>test</white>", List.of()));
        // HexCore treats a key that already contains a dot as fully qualified, so this registers
        // under "announce.activated" and the plugin's "posterunki.announce.activated" stays missing.
        other.service().registerDefaultsWithArgs(UiKeys.NAMESPACE, shortKeys);
        String rendered = PLAIN.serialize(other.service().render(UiKeys.ANNOUNCE_ACTIVATED, new UiTokens()));
        assertTrue(rendered.contains("Missing template"),
                "krótkie klucze nie są uzupełniane o namespace - dlatego rejestrujemy pełne");
    }

    @Test
    void tokensAreSubstitutedInTheAnnouncement() {
        String rendered = PLAIN.serialize(ui.render(UiKeys.ANNOUNCE_ACTIVATED,
                UiTokens.of("name", "Fort Północny").put("distance", "1234")));
        assertTrue(rendered.contains("Fort Północny"));
        assertTrue(rendered.contains("1234"));
        assertFalse(rendered.contains("<name>"));
        assertFalse(rendered.contains("<distance>"));
    }

    @Test
    void polishDiacriticsSurviveRendering() {
        String rendered = PLAIN.serialize(ui.render(UiKeys.PROGRESS_LOST_LEAVE, new UiTokens()));
        assertEquals("Opuściłeś posterunek — twój postęp został wyzerowany.", rendered);
    }

    @Test
    void everyDefaultUsesPolishDiacriticsWhereTheWordNeedsThem() {
        // Whole words that are simply wrong without their diacritics - "zostal" for "został",
        // "twoj" for "twój". Word boundaries matter: "twoje" and "twoja" are correct as they are.
        List<String> banned = List.of("zostal", "zostala", "zostaly", "opusciles", "twoj",
                "druzyna", "druzyny", "zabojstwa", "odleglosc", "poludniowy", "polnocny", "lupy");
        List<String> suspicious = new ArrayList<>();
        for (Map.Entry<String, TemplateDefinition> entry : PosterunkiUi.defaults().entrySet()) {
            String template = entry.getValue().template().toLowerCase(java.util.Locale.ROOT);
            for (String bad : banned) {
                if (java.util.regex.Pattern.compile("\\b" + bad + "\\b").matcher(template).find()) {
                    suspicious.add(entry.getKey() + " -> " + bad);
                }
            }
        }
        assertTrue(suspicious.isEmpty(), "Uproszczone polskie słowa bez znaków diakrytycznych: " + suspicious);
    }

    @Test
    void theWordBoundaryCheckItselfCatchesARealMistake() {
        // Guards the guard: a template written as "zostal" must be reported.
        String broken = "<white>Posterunek zostal aktywowany</white>".toLowerCase(java.util.Locale.ROOT);
        assertTrue(java.util.regex.Pattern.compile("\\bzostal\\b").matcher(broken).find());
        String correct = "<white>Posterunek został aktywowany</white>".toLowerCase(java.util.Locale.ROOT);
        assertFalse(java.util.regex.Pattern.compile("\\bzostal\\b").matcher(correct).find());
    }

    @Test
    void adminMessagesCarryNoRawEnumNames() {
        // Phase and boss status reach the admin as Polish labels, not as WAITING / PENDING.
        for (hexposterunki.domain.RunPhase phase : hexposterunki.domain.RunPhase.values()) {
            assertFalse(phase.polishLabel().isBlank(), phase + " nie ma polskiej etykiety");
            assertNotEquals(phase.name(), phase.polishLabel());
        }
        for (hexposterunki.domain.BossStatus status : hexposterunki.domain.BossStatus.values()) {
            assertFalse(status.polishLabel().isBlank(), status + " nie ma polskiej etykiety");
            assertNotEquals(status.name(), status.polishLabel());
        }
    }

    @Test
    void adminOverrideStillWinsOverTheRegisteredDefault() {
        harness.override(UiKeys.ADMIN_RESET, "<white>nadpisane</white>");
        assertEquals("nadpisane", PLAIN.serialize(ui.render(UiKeys.ADMIN_RESET, new UiTokens())));
    }

    private static void assertNotEquals(String unexpected, String actual) {
        assertFalse(unexpected.equals(actual), "oczekiwano innej wartości niż " + unexpected);
    }
}
