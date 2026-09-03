package hexchat.service;

import hexchat.config.HexChatConfig;
import hexchat.config.HexChatConfig.FilterAction;
import hexchat.support.TestConfigs;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ChatContentFilterServiceTest {

    private static Player player() {
        Player player = mock(Player.class);
        lenient().when(player.getUniqueId()).thenReturn(UUID.randomUUID());
        return player;
    }

    private static HexChatConfig.AntiAdvertising ad(boolean enabled, FilterAction action, List<String> allowed) {
        return new HexChatConfig.AntiAdvertising(enabled, action, "<red>ad</red>", allowed, List.of());
    }

    /** Blacklista z domyślnie włączonym utwardzeniem (separatory + końcówki słów). */
    private static HexChatConfig.Blacklist blacklist(boolean enabled, FilterAction action, boolean leet, List<String> words) {
        return blacklist(enabled, action, leet, true, true, words);
    }

    private static HexChatConfig.Blacklist blacklist(
            boolean enabled,
            FilterAction action,
            boolean leet,
            boolean ignoreSeparators,
            boolean matchWordEndings,
            List<String> words
    ) {
        return new HexChatConfig.Blacklist(
                enabled, action, "<red>bl</red>", leet, ignoreSeparators, matchWordEndings, words
        );
    }

    private static HexChatConfig.AntiSpam spam(boolean enabled, int maxRepeated, int maxCaps, int minLen) {
        return new HexChatConfig.AntiSpam(enabled, "<red>spam</red>", maxRepeated, maxCaps, minLen);
    }

    private static HexChatConfig config(HexChatConfig.AntiAdvertising ad,
                                        HexChatConfig.Blacklist blacklist,
                                        HexChatConfig.AntiSpam spam) {
        return TestConfigs.withContentFilter(new HexChatConfig.ContentFilter(
                true, "hexchat.filter.bypass", "***", ad, blacklist, spam
        ));
    }

    private static ChatContentFilterService.Decision decision(ChatContentFilterService service, Player player, String msg) {
        return service.inspect(player, msg).decision();
    }

    // --- Anti-reklama ---

    @Test
    void blocksDomainsIpsAndInvites() {
        ChatContentFilterService service = new ChatContentFilterService(config(
                ad(true, FilterAction.BLOCK, List.of()),
                blacklist(false, FilterAction.BLOCK, true, List.of()),
                spam(false, 3, 70, 8)
        ));

        assertEquals(ChatContentFilterService.Decision.BLOCK, decision(service, player(), "wejdz na example.com teraz"));
        assertEquals(ChatContentFilterService.Decision.BLOCK, decision(service, player(), "ip 51.83.12.9 graj"));
        assertEquals(ChatContentFilterService.Decision.BLOCK, decision(service, player(), "discord.gg/abcdef"));
    }

    @Test
    void allowedDomainIsNotBlocked() {
        ChatContentFilterService service = new ChatContentFilterService(config(
                ad(true, FilterAction.BLOCK, List.of("twojserwer.pl")),
                blacklist(false, FilterAction.BLOCK, true, List.of()),
                spam(false, 3, 70, 8)
        ));

        assertEquals(ChatContentFilterService.Decision.ALLOWED, decision(service, player(), "wejdz na twojserwer.pl"));
    }

    @Test
    void allowlistAllowsExactAndRealSubdomainsOnly() {
        ChatContentFilterService service = new ChatContentFilterService(config(
                ad(true, FilterAction.BLOCK, List.of("twojserwer.pl")),
                blacklist(false, FilterAction.BLOCK, true, List.of()),
                spam(false, 3, 70, 8)
        ));
        Player player = player();

        // Dozwolone: dokładne dopasowanie i prawdziwe subdomeny.
        assertEquals(ChatContentFilterService.Decision.ALLOWED, decision(service, player, "graj na twojserwer.pl"));
        assertEquals(ChatContentFilterService.Decision.ALLOWED, decision(service, player, "sklep shop.twojserwer.pl tu"));
        assertEquals(ChatContentFilterService.Decision.ALLOWED, decision(service, player, "www.twojserwer.pl dziala"));
    }

    @Test
    void allowlistDoesNotAllowLookalikeOrSuffixTricks() {
        ChatContentFilterService service = new ChatContentFilterService(config(
                ad(true, FilterAction.BLOCK, List.of("twojserwer.pl")),
                blacklist(false, FilterAction.BLOCK, true, List.of()),
                spam(false, 3, 70, 8)
        ));
        Player player = player();

        // Domena dozwolona jako część większej domeny -> BLOCK.
        assertEquals(ChatContentFilterService.Decision.BLOCK, decision(service, player, "wejdz na twojserwer.pl.evil.com"));
        // Domena dozwolona jako sufiks bez granicy kropki -> BLOCK.
        assertEquals(ChatContentFilterService.Decision.BLOCK, decision(service, player, "scam evil-twojserwer.pl"));
        // Invite z dozwoloną domeną tylko w ścieżce -> BLOCK (liczy się host, nie ścieżka).
        assertEquals(ChatContentFilterService.Decision.BLOCK, decision(service, player, "discord.gg/twojserwer.pl-scam"));
    }

    @Test
    void advertisingCensorMasksTheUrl() {
        ChatContentFilterService service = new ChatContentFilterService(config(
                ad(true, FilterAction.CENSOR, List.of()),
                blacklist(false, FilterAction.BLOCK, true, List.of()),
                spam(false, 3, 70, 8)
        ));

        ChatContentFilterService.InspectionResult result = service.inspect(player(), "link example.com tu");
        assertEquals(ChatContentFilterService.Decision.CENSOR, result.decision());
        assertEquals("link *** tu", result.censoredText());
    }

    // --- Blacklista ---

    @Test
    void blocksBlacklistedWordCaseInsensitive() {
        ChatContentFilterService service = new ChatContentFilterService(config(
                ad(false, FilterAction.BLOCK, List.of()),
                blacklist(true, FilterAction.BLOCK, true, List.of("brzydkie")),
                spam(false, 3, 70, 8)
        ));

        assertEquals(ChatContentFilterService.Decision.BLOCK, decision(service, player(), "to jest BRZYDKIE slowo"));
    }

    @Test
    void blacklistMatchesLeetspeakVariant() {
        ChatContentFilterService service = new ChatContentFilterService(config(
                ad(false, FilterAction.BLOCK, List.of()),
                blacklist(true, FilterAction.BLOCK, true, List.of("test")),
                spam(false, 3, 70, 8)
        ));

        assertEquals(ChatContentFilterService.Decision.BLOCK, decision(service, player(), "napis t3st tutaj"));
    }

    @Test
    void blacklistMatchesWordEndingWhenEnabled() {
        ChatContentFilterService service = new ChatContentFilterService(config(
                ad(false, FilterAction.BLOCK, List.of()),
                blacklist(true, FilterAction.BLOCK, false, true, true, List.of("brzydkie")),
                spam(false, 3, 70, 8)
        ));

        // Wpis "brzydkie" blokuje też dłuższą formę "brzydkiego" (match-word-endings = true).
        assertEquals(ChatContentFilterService.Decision.BLOCK, decision(service, player(), "cos brzydkiego"));
    }

    @Test
    void blacklistIgnoresWordEndingWhenDisabled() {
        ChatContentFilterService service = new ChatContentFilterService(config(
                ad(false, FilterAction.BLOCK, List.of()),
                blacklist(true, FilterAction.BLOCK, false, true, false, List.of("brzydkie")),
                spam(false, 3, 70, 8)
        ));

        // Przy match-word-endings = false liczy się tylko pełne słowo.
        assertEquals(ChatContentFilterService.Decision.ALLOWED, decision(service, player(), "cos brzydkiego"));
        assertEquals(ChatContentFilterService.Decision.BLOCK, decision(service, player(), "cos brzydkie"));
    }

    @Test
    void blacklistRequiresLeftWordBoundary() {
        ChatContentFilterService service = new ChatContentFilterService(config(
                ad(false, FilterAction.BLOCK, List.of()),
                blacklist(true, FilterAction.BLOCK, true, List.of("test")),
                spam(false, 3, 70, 8)
        ));

        // "protest" zawiera "test", ale nie na granicy słowa -> brak blokady.
        assertEquals(ChatContentFilterService.Decision.ALLOWED, decision(service, player(), "to protest graczy"));
    }

    @Test
    void blacklistMatchesShortEntryOnlyAsWholeWord() {
        ChatContentFilterService service = new ChatContentFilterService(config(
                ad(false, FilterAction.BLOCK, List.of()),
                blacklist(true, FilterAction.BLOCK, false, List.of("kot")),
                spam(false, 3, 70, 8)
        ));

        // Wpisy krótsze niż 4 znaki nie łapią końcówek (za duże ryzyko fałszywych trafień).
        assertEquals(ChatContentFilterService.Decision.ALLOWED, decision(service, player(), "duzy kotek"));
        assertEquals(ChatContentFilterService.Decision.BLOCK, decision(service, player(), "duzy kot"));
    }

    @Test
    void blacklistMatchesSymbolicLeetspeakVariants() {
        ChatContentFilterService service = new ChatContentFilterService(config(
                ad(false, FilterAction.BLOCK, List.of()),
                blacklist(true, FilterAction.BLOCK, true, List.of("test", "hass")),
                spam(false, 3, 70, 8)
        ));

        assertEquals(ChatContentFilterService.Decision.BLOCK, decision(service, player(), "napis 7e57 tutaj"));
        assertEquals(ChatContentFilterService.Decision.BLOCK, decision(service, player(), "napis te$t tutaj"));
        assertEquals(ChatContentFilterService.Decision.BLOCK, decision(service, player(), "napis h@55 tutaj"));
    }

    @Test
    void blacklistMatchesFullwidthVariant() {
        ChatContentFilterService service = new ChatContentFilterService(config(
                ad(false, FilterAction.BLOCK, List.of()),
                blacklist(true, FilterAction.BLOCK, true, List.of("test")),
                spam(false, 3, 70, 8)
        ));

        // Warianty fullwidth sprowadzamy normalizacją NFKC do zwykłych liter.
        assertEquals(ChatContentFilterService.Decision.BLOCK, decision(service, player(), "napis \uFF34\uFF25\uFF33\uFF34 tutaj"));
    }

    @Test
    void blacklistMatchesSeparatedLetters() {
        ChatContentFilterService service = new ChatContentFilterService(config(
                ad(false, FilterAction.BLOCK, List.of()),
                blacklist(true, FilterAction.BLOCK, true, List.of("test")),
                spam(false, 3, 70, 8)
        ));
        Player player = player();

        assertEquals(ChatContentFilterService.Decision.BLOCK, decision(service, player, "to t e s t koniec"));
        assertEquals(ChatContentFilterService.Decision.BLOCK, decision(service, player, "to t   e\ts  t koniec"));
        assertEquals(ChatContentFilterService.Decision.BLOCK, decision(service, player, "to t.e.s.t koniec"));
        assertEquals(ChatContentFilterService.Decision.BLOCK, decision(service, player, "to t---e---s---t koniec"));
    }

    @Test
    void blacklistMatchesZeroWidthSeparatedLetters() {
        ChatContentFilterService service = new ChatContentFilterService(config(
                ad(false, FilterAction.BLOCK, List.of()),
                blacklist(true, FilterAction.BLOCK, true, List.of("test")),
                spam(false, 3, 70, 8)
        ));

        // Zero-width space i zero-width joiner między literami.
        assertEquals(
                ChatContentFilterService.Decision.BLOCK,
                decision(service, player(), "to t\u200Be\u200Cs\u200Dt koniec")
        );
    }

    @Test
    void leetspeakVariantsAreAllowedWhenLeetspeakDisabled() {
        ChatContentFilterService service = new ChatContentFilterService(config(
                ad(false, FilterAction.BLOCK, List.of()),
                blacklist(true, FilterAction.BLOCK, false, List.of("test", "hass")),
                spam(false, 3, 70, 8)
        ));
        Player player = player();

        assertEquals(ChatContentFilterService.Decision.ALLOWED, decision(service, player, "napis t3st tutaj"));
        assertEquals(ChatContentFilterService.Decision.ALLOWED, decision(service, player, "napis te$t tutaj"));
        assertEquals(ChatContentFilterService.Decision.ALLOWED, decision(service, player, "napis h@55 tutaj"));
        // Pełne słowo nadal blokowane.
        assertEquals(ChatContentFilterService.Decision.BLOCK, decision(service, player, "napis test tutaj"));
    }

    @Test
    void separatedLettersAreAllowedWhenSeparatorsAreNotIgnored() {
        ChatContentFilterService service = new ChatContentFilterService(config(
                ad(false, FilterAction.BLOCK, List.of()),
                blacklist(true, FilterAction.BLOCK, true, false, true, List.of("test")),
                spam(false, 3, 70, 8)
        ));
        Player player = player();

        assertEquals(ChatContentFilterService.Decision.ALLOWED, decision(service, player, "to t e s t koniec"));
        assertEquals(ChatContentFilterService.Decision.ALLOWED, decision(service, player, "to t.e.s.t koniec"));
        // Leetspeak w zwartym słowie działa nadal.
        assertEquals(ChatContentFilterService.Decision.BLOCK, decision(service, player, "to t3st koniec"));
    }

    @Test
    void blockReturnsConfiguredBlacklistMessage() {
        ChatContentFilterService service = new ChatContentFilterService(config(
                ad(false, FilterAction.BLOCK, List.of()),
                blacklist(true, FilterAction.BLOCK, true, List.of("test")),
                spam(false, 3, 70, 8)
        ));

        ChatContentFilterService.InspectionResult result = service.inspect(player(), "to t 3 s t!");
        assertEquals(ChatContentFilterService.Decision.BLOCK, result.decision());
        assertEquals("<red>bl</red>", result.blockMessage());
    }

    @Test
    void censorMasksWholeBypassIncludingSeparators() {
        ChatContentFilterService service = new ChatContentFilterService(config(
                ad(false, FilterAction.BLOCK, List.of()),
                blacklist(true, FilterAction.CENSOR, true, List.of("test")),
                spam(false, 3, 70, 8)
        ));

        // Maskujemy całe obejście, ale końcowy wykrzyknik jest interpunkcją, nie literą.
        ChatContentFilterService.InspectionResult result = service.inspect(player(), "to t 3 s t!");
        assertEquals(ChatContentFilterService.Decision.CENSOR, result.decision());
        assertEquals("to ***!", result.censoredText());
    }

    @Test
    void censorMasksWholeWordIncludingEnding() {
        ChatContentFilterService service = new ChatContentFilterService(config(
                ad(false, FilterAction.BLOCK, List.of()),
                blacklist(true, FilterAction.CENSOR, true, List.of("brzydkie")),
                spam(false, 3, 70, 8)
        ));

        ChatContentFilterService.InspectionResult result = service.inspect(player(), "to brzydkiego slowo");
        assertEquals(ChatContentFilterService.Decision.CENSOR, result.decision());
        assertEquals("to *** slowo", result.censoredText());
    }

    @Test
    void censorMergesMultipleAndOverlappingMatches() {
        ChatContentFilterService service = new ChatContentFilterService(config(
                ad(false, FilterAction.BLOCK, List.of()),
                blacklist(true, FilterAction.CENSOR, true, List.of("test", "testowe")),
                spam(false, 3, 70, 8)
        ));

        // "testowe" pasuje do obu wpisów (nakładające się zakresy) -> jedna maska.
        ChatContentFilterService.InspectionResult result = service.inspect(player(), "test i testowe slowo");
        assertEquals(ChatContentFilterService.Decision.CENSOR, result.decision());
        assertEquals("*** i *** slowo", result.censoredText());
    }

    @Test
    void updateConfigAppliesNewBlacklistOptions() {
        HexChatConfig strict = config(
                ad(false, FilterAction.BLOCK, List.of()),
                blacklist(true, FilterAction.BLOCK, true, true, true, List.of("brzydkie")),
                spam(false, 3, 70, 8)
        );
        HexChatConfig relaxed = config(
                ad(false, FilterAction.BLOCK, List.of()),
                blacklist(true, FilterAction.BLOCK, false, false, false, List.of("brzydkie")),
                spam(false, 3, 70, 8)
        );

        ChatContentFilterService service = new ChatContentFilterService(strict);
        assertEquals(ChatContentFilterService.Decision.BLOCK, decision(service, player(), "cos brzydkiego"));

        // Reload konfiguracji przebudowuje niemutowalny stan filtra.
        service.updateConfig(relaxed);
        assertEquals(ChatContentFilterService.Decision.ALLOWED, decision(service, player(), "cos brzydkiego"));

        service.updateConfig(strict);
        assertEquals(ChatContentFilterService.Decision.BLOCK, decision(service, player(), "cos brzydkiego"));
    }

    @Test
    void blacklistCensorMasksWord() {
        ChatContentFilterService service = new ChatContentFilterService(config(
                ad(false, FilterAction.BLOCK, List.of()),
                blacklist(true, FilterAction.CENSOR, true, List.of("brzydkie")),
                spam(false, 3, 70, 8)
        ));

        ChatContentFilterService.InspectionResult result = service.inspect(player(), "to brzydkie slowo");
        assertEquals(ChatContentFilterService.Decision.CENSOR, result.decision());
        assertEquals("to *** slowo", result.censoredText());
    }

    // --- Blacklista: leetspeak razem z separatorami ---

    /** Serwis z blacklistą w domyślnym, utwardzonym trybie (leet + separatory + końcówki). */
    private static ChatContentFilterService blacklistService(FilterAction action, String... words) {
        return blacklistService(action, true, true, true, words);
    }

    private static ChatContentFilterService blacklistService(
            FilterAction action,
            boolean leet,
            boolean ignoreSeparators,
            boolean matchWordEndings,
            String... words
    ) {
        return new ChatContentFilterService(config(
                ad(false, FilterAction.BLOCK, List.of()),
                blacklist(true, action, leet, ignoreSeparators, matchWordEndings, List.of(words)),
                spam(false, 3, 70, 8)
        ));
    }

    @Test
    void leetSymbolsAreDetectedAtWordEdgesAndAcrossSeparators() {
        // Symbol leet na początku słowa.
        assertEquals(
                ChatContentFilterService.Decision.BLOCK,
                decision(blacklistService(FilterAction.BLOCK, "shit"), player(), "to $hit koniec")
        );
        assertEquals(
                ChatContentFilterService.Decision.BLOCK,
                decision(blacklistService(FilterAction.BLOCK, "shit"), player(), "$hit na starcie")
        );
        // Symbol leet w wariancie rozstrzelonym separatorami.
        assertEquals(
                ChatContentFilterService.Decision.BLOCK,
                decision(blacklistService(FilterAction.BLOCK, "test"), player(), "to t e $ t koniec")
        );
        assertEquals(
                ChatContentFilterService.Decision.BLOCK,
                decision(blacklistService(FilterAction.BLOCK, "hass"), player(), "to h @ 5 5 koniec")
        );
        assertEquals(
                ChatContentFilterService.Decision.BLOCK,
                decision(blacklistService(FilterAction.BLOCK, "ass"), player(), "to a $ $ koniec")
        );
        // Symbol leet obok znaku zero-width.
        assertEquals(
                ChatContentFilterService.Decision.BLOCK,
                decision(blacklistService(FilterAction.BLOCK, "test"), player(), "to te$​t koniec")
        );
    }

    @Test
    void compactLeetAndSeparatorVariantsStillMatch() {
        ChatContentFilterService service = blacklistService(FilterAction.BLOCK, "test", "hass");
        Player player = player();

        assertEquals(ChatContentFilterService.Decision.BLOCK, decision(service, player, "to te$t koniec"));
        assertEquals(ChatContentFilterService.Decision.BLOCK, decision(service, player, "to h@55 koniec"));
        assertEquals(ChatContentFilterService.Decision.BLOCK, decision(service, player, "to 7e57 koniec"));
        assertEquals(ChatContentFilterService.Decision.BLOCK, decision(service, player, "to t.e.s.t koniec"));
        assertEquals(ChatContentFilterService.Decision.BLOCK, decision(service, player, "to t e s t koniec"));
    }

    @Test
    void trailingPunctuationStaysOutsideCensorSpan() {
        ChatContentFilterService service = blacklistService(FilterAction.CENSOR, "test");

        // Wykrzyknik jest interpunkcją, nie literą 'i' — nie wchodzi do maskowanego zakresu.
        assertEquals("to ***!", service.inspect(player(), "to t e $ t!").censoredText());
        assertEquals("to ***!", service.inspect(player(), "to test!").censoredText());
        assertEquals("to *** i tyle", service.inspect(player(), "to te$t i tyle").censoredText());
    }

    @Test
    void leetSymbolVariantsAreAllowedWhenLeetspeakDisabled() {
        ChatContentFilterService service = blacklistService(FilterAction.BLOCK, false, true, true, "test", "shit", "ass");
        Player player = player();

        assertEquals(ChatContentFilterService.Decision.ALLOWED, decision(service, player, "to $hit koniec"));
        assertEquals(ChatContentFilterService.Decision.ALLOWED, decision(service, player, "to t e $ t koniec"));
        assertEquals(ChatContentFilterService.Decision.ALLOWED, decision(service, player, "to a $ $ koniec"));
        assertEquals(ChatContentFilterService.Decision.BLOCK, decision(service, player, "to test koniec"));
    }

    @Test
    void separatedLeetVariantsAreAllowedWhenSeparatorsAreNotIgnored() {
        ChatContentFilterService service = blacklistService(FilterAction.BLOCK, true, false, true, "test", "ass");
        Player player = player();

        assertEquals(ChatContentFilterService.Decision.ALLOWED, decision(service, player, "to t e $ t koniec"));
        assertEquals(ChatContentFilterService.Decision.ALLOWED, decision(service, player, "to a $ $ koniec"));
        // Zwarty wariant leet nadal jest wykrywany.
        assertEquals(ChatContentFilterService.Decision.BLOCK, decision(service, player, "to te$t koniec"));
    }

    @Test
    void updateConfigAppliesNewLeetAndSeparatorOptions() {
        HexChatConfig strict = config(
                ad(false, FilterAction.BLOCK, List.of()),
                blacklist(true, FilterAction.BLOCK, true, true, true, List.of("test")),
                spam(false, 3, 70, 8)
        );
        HexChatConfig withoutLeet = config(
                ad(false, FilterAction.BLOCK, List.of()),
                blacklist(true, FilterAction.BLOCK, false, true, true, List.of("test")),
                spam(false, 3, 70, 8)
        );

        ChatContentFilterService service = new ChatContentFilterService(strict);
        assertEquals(ChatContentFilterService.Decision.BLOCK, decision(service, player(), "to t e $ t koniec"));

        service.updateConfig(withoutLeet);
        assertEquals(ChatContentFilterService.Decision.ALLOWED, decision(service, player(), "to t e $ t koniec"));

        service.updateConfig(strict);
        assertEquals(ChatContentFilterService.Decision.BLOCK, decision(service, player(), "to t e $ t koniec"));
    }

    // --- Blacklista: alternatywne ścieżki dopasowania ---

    @Test
    void rejectedTerminalPathDoesNotKillAlternativePaths() {
        // "tes+t": '+' czytany jako litera 't' domyka słowo o jedną jednostkę za wcześnie
        // (dalej biegnie litera, a końcówki są wyłączone). Ta sama jednostka pominięta jako
        // separator daje poprawne trafienie — odrzucenie pierwszej ścieżki nie może go zgubić.
        ChatContentFilterService block = blacklistService(FilterAction.BLOCK, true, true, false, "test");
        assertEquals(ChatContentFilterService.Decision.BLOCK, decision(block, player(), "tes+t"));

        ChatContentFilterService censor = blacklistService(FilterAction.CENSOR, true, true, false, "test");
        assertEquals("***", censor.inspect(player(), "tes+t").censoredText());
    }

    @Test
    void shortEntryMatchesWhenSymbolIsSkippedAsSeparator() {
        // Wpis krótszy niż cztery znaki nigdy nie łapie końcówek, więc ścieżka z '$'
        // czytanym jako litera odpada — zostać musi ścieżka z '$' jako separatorem.
        ChatContentFilterService block = blacklistService(FilterAction.BLOCK, "as");
        assertEquals(ChatContentFilterService.Decision.BLOCK, decision(block, player(), "a$s"));

        ChatContentFilterService censor = blacklistService(FilterAction.CENSOR, "as");
        assertEquals("***", censor.inspect(player(), "a$s").censoredText());
    }

    @Test
    void symbolsSkippedAsSeparatorsCountAsObfuscation() {
        ChatContentFilterService censor = blacklistService(FilterAction.CENSOR, "test");
        Player player = player();

        // Symbole pominięte jako separatory zaciemniają trafienie tak samo jak myślniki,
        // więc rozstrzelona końcówka też należy do obejścia.
        assertEquals("***!", censor.inspect(player, "t!e!s!t i n g!").censoredText());
        assertEquals("***!", censor.inspect(player, "t-e-s-t i n g!").censoredText());
        // Zwarte trafienie nadal nie wciąga interpunkcji ani kolejnych słów.
        assertEquals("***!", censor.inspect(player, "test!").censoredText());
        assertEquals("*** i n g!", censor.inspect(player, "test i n g!").censoredText());
    }

    // --- Blacklista: normalizacja Unicode ---

    /** "żaba" w postaci złożonej: U+017C. */
    private static final String PRECOMPOSED_WORD = "żaba";
    /** To samo słowo w postaci rozłożonej: z + COMBINING DOT ABOVE (U+0307). */
    private static final String DECOMPOSED_WORD = "z\u0307aba";

    @Test
    void unicodeFixturesAreReallyDifferentRepresentations() {
        // Zabezpieczenie przed cichą normalizacją pliku źródłowego przez edytor:
        // oba literały muszą pozostać różnymi ciągami UTF-16.
        assertNotEquals(PRECOMPOSED_WORD, DECOMPOSED_WORD);
        assertEquals(4, PRECOMPOSED_WORD.length());
        assertEquals(5, DECOMPOSED_WORD.length());
    }

    @Test
    void precomposedEntryMatchesDecomposedMessage() {
        ChatContentFilterService service = blacklistService(FilterAction.BLOCK, PRECOMPOSED_WORD);

        assertEquals(
                ChatContentFilterService.Decision.BLOCK,
                decision(service, player(), "to " + DECOMPOSED_WORD + " tutaj")
        );
    }

    @Test
    void decomposedEntryMatchesPrecomposedMessage() {
        ChatContentFilterService service = blacklistService(FilterAction.BLOCK, DECOMPOSED_WORD);

        assertEquals(
                ChatContentFilterService.Decision.BLOCK,
                decision(service, player(), "to " + PRECOMPOSED_WORD + " tutaj")
        );
    }

    @Test
    void censorMasksWholeDecomposedSequence() {
        ChatContentFilterService service = blacklistService(FilterAction.CENSOR, PRECOMPOSED_WORD);

        // Maska musi objąć również znak łączący, inaczej zostałby osierocony w tekście.
        ChatContentFilterService.InspectionResult result =
                service.inspect(player(), "to " + DECOMPOSED_WORD + " tutaj");
        assertEquals(ChatContentFilterService.Decision.CENSOR, result.decision());
        assertEquals("to *** tutaj", result.censoredText());
    }

    @Test
    void supplementaryCodePointsAreNormalizedNotSplit() {
        // Matematyczne pogrubione "test" (U+1D42D...) to pary zastępcze poza BMP.
        ChatContentFilterService service = blacklistService(FilterAction.BLOCK, "test");

        assertEquals(
                ChatContentFilterService.Decision.BLOCK,
                decision(service, player(), "to 𝐭𝐞𝐬𝐭 koniec")
        );
    }

    @Test
    void censorKeepsSupplementaryCodePointsIntact() {
        ChatContentFilterService service = blacklistService(FilterAction.CENSOR, "brzydkie");

        // Emoji poza BMP nie może zostać rozcięte ani zjedzone przez maskowanie.
        ChatContentFilterService.InspectionResult result =
                service.inspect(player(), "😀 brzydkie 😀");
        assertEquals(ChatContentFilterService.Decision.CENSOR, result.decision());
        assertEquals("😀 *** 😀", result.censoredText());
    }

    // --- Blacklista: rozstrzelona końcówka słowa ---

    @Test
    void separatedWordEndingBelongsToTheMatch() {
        ChatContentFilterService censor = blacklistService(FilterAction.CENSOR, "brzydkie");
        Player player = player();

        assertEquals("to ***!", censor.inspect(player, "to b r z y d k i e g o!").censoredText());
        assertEquals("to ***!", censor.inspect(player, "to b.r.z.y.d.k.i.e.g.o!").censoredText());
        assertEquals("to ***!", censor.inspect(player, "to b-r-z-y-d-k-i-e-g-o!").censoredText());
        assertEquals(
                "to ***!",
                censor.inspect(player, "to b​r​z​y​d​k​i​e​g​o!").censoredText()
        );

        // BLOCK rozpoznaje dokładnie te same trafienia.
        ChatContentFilterService block = blacklistService(FilterAction.BLOCK, "brzydkie");
        assertEquals(ChatContentFilterService.Decision.BLOCK, decision(block, player, "to b r z y d k i e g o!"));
    }

    @Test
    void separatedEndingDoesNotSwallowFollowingWords() {
        ChatContentFilterService service = blacklistService(FilterAction.CENSOR, "brzydkie");
        Player player = player();

        // Zwarte trafienie: kolejne słowa zostają nietknięte, nawet jednoliterowe.
        assertEquals("to *** go teraz", service.inspect(player, "to brzydkie go teraz").censoredText());
        assertEquals("to *** o tym", service.inspect(player, "to brzydkie o tym").censoredText());
        // Zaciemnione trafienie, ale po nim zwarta grupa liter = nowe słowo.
        assertEquals("to *** go teraz", service.inspect(player, "to b r z y d k i e go teraz").censoredText());
    }

    @Test
    void separatedEndingRequiresEnabledWordEndings() {
        ChatContentFilterService service = blacklistService(FilterAction.CENSOR, true, true, false, "brzydkie");

        // Przy match-word-endings = false maskujemy wyłącznie sam wpis.
        assertEquals("to *** g o!", service.inspect(player(), "to b r z y d k i e g o!").censoredText());
    }

    // --- Anti-spam ---

    @Test
    void blocksRepeatedMessagesAfterThreshold() {
        ChatContentFilterService service = new ChatContentFilterService(config(
                ad(false, FilterAction.BLOCK, List.of()),
                blacklist(false, FilterAction.BLOCK, true, List.of()),
                spam(true, 3, 100, 100)
        ));
        Player player = player();

        assertEquals(ChatContentFilterService.Decision.ALLOWED, decision(service, player, "hej"));
        assertEquals(ChatContentFilterService.Decision.ALLOWED, decision(service, player, "hej"));
        assertEquals(ChatContentFilterService.Decision.BLOCK, decision(service, player, "hej"));
    }

    @Test
    void blocksExcessiveCaps() {
        ChatContentFilterService service = new ChatContentFilterService(config(
                ad(false, FilterAction.BLOCK, List.of()),
                blacklist(false, FilterAction.BLOCK, true, List.of()),
                spam(true, 100, 70, 8)
        ));

        assertEquals(ChatContentFilterService.Decision.BLOCK, decision(service, player(), "TO JEST KRZYK NA CZACIE"));
        assertEquals(ChatContentFilterService.Decision.ALLOWED, decision(service, player(), "to jest normalne zdanie"));
    }

    // --- Ogólne ---

    @Test
    void disabledFilterAllowsEverything() {
        HexChatConfig config = TestConfigs.withContentFilter(new HexChatConfig.ContentFilter(
                false, "hexchat.filter.bypass", "***",
                ad(true, FilterAction.BLOCK, List.of()),
                blacklist(true, FilterAction.BLOCK, true, List.of("brzydkie")),
                spam(true, 2, 50, 5)
        ));
        ChatContentFilterService service = new ChatContentFilterService(config);

        assertEquals(ChatContentFilterService.Decision.ALLOWED, decision(service, player(), "brzydkie example.com"));
    }

    @Test
    void blacklistTakesPrecedenceOverAdvertising() {
        ChatContentFilterService service = new ChatContentFilterService(config(
                ad(true, FilterAction.CENSOR, List.of()),
                blacklist(true, FilterAction.BLOCK, true, List.of("brzydkie")),
                spam(false, 3, 70, 8)
        ));

        // Zawiera i słowo z blacklisty (BLOCK) i URL (CENSOR) -> wygrywa BLOCK.
        assertEquals(ChatContentFilterService.Decision.BLOCK, decision(service, player(), "brzydkie example.com"));
    }
}
