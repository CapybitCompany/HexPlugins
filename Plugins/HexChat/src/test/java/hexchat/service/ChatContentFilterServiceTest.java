package hexchat.service;

import hexchat.config.HexChatConfig;
import hexchat.config.HexChatConfig.FilterAction;
import hexchat.support.TestConfigs;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
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

    private static HexChatConfig.Blacklist blacklist(boolean enabled, FilterAction action, boolean leet, List<String> words) {
        return new HexChatConfig.Blacklist(enabled, action, "<red>bl</red>", leet, words);
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
    void blacklistDoesNotMatchSubstringInsideLargerWord() {
        ChatContentFilterService service = new ChatContentFilterService(config(
                ad(false, FilterAction.BLOCK, List.of()),
                blacklist(true, FilterAction.BLOCK, false, List.of("brzydkie")),
                spam(false, 3, 70, 8)
        ));

        // "brzydkiego" nie jest dokładnym słowem "brzydkie" -> brak blokady (granice słów).
        assertEquals(ChatContentFilterService.Decision.ALLOWED, decision(service, player(), "cos brzydkiego"));
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
