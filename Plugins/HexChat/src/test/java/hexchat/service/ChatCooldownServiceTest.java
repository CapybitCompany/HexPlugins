package hexchat.service;

import hexchat.config.HexChatConfig;
import hexchat.support.TestConfigs;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ChatCooldownServiceTest {

    private static Player player() {
        Player player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(UUID.randomUUID());
        lenient().when(player.isOp()).thenReturn(false);
        return player;
    }

    private static HexChatConfig.Cooldown cooldown(int defaultSeconds,
                                                   List<HexChatConfig.GroupCooldown> ranks,
                                                   List<HexChatConfig.PermissionCooldown> overrides) {
        return new HexChatConfig.Cooldown(
                true,
                "hexchat.cooldown.bypass",
                true,
                defaultSeconds,
                ranks,
                overrides
        );
    }

    @Test
    void defaultCooldownBlocksSecondMessage() {
        HexChatConfig config = TestConfigs.withCooldown(cooldown(10, List.of(), List.of()));
        ChatCooldownService service = new ChatCooldownService(() -> config, new NoopChatRankResolver());
        Player player = player();

        assertFalse(service.checkAndApply(player).blocked(), "Pierwsza wiadomość powinna przejść");
        assertTrue(service.checkAndApply(player).blocked(), "Druga wiadomość powinna zostać zablokowana");
    }

    @Test
    void bypassPermissionAllowsImmediately() {
        HexChatConfig config = TestConfigs.withCooldown(cooldown(10, List.of(), List.of()));
        ChatCooldownService service = new ChatCooldownService(() -> config, new NoopChatRankResolver());
        Player player = player();
        when(player.hasPermission("hexchat.cooldown.bypass")).thenReturn(true);

        assertFalse(service.checkAndApply(player).blocked());
        assertFalse(service.checkAndApply(player).blocked(), "Z bypass nie ma cooldownu");
    }

    @Test
    void opBypassesCooldown() {
        HexChatConfig config = TestConfigs.withCooldown(cooldown(10, List.of(), List.of()));
        ChatCooldownService service = new ChatCooldownService(() -> config, new NoopChatRankResolver());
        Player player = player();
        when(player.isOp()).thenReturn(true);

        assertFalse(service.checkAndApply(player).blocked());
        assertFalse(service.checkAndApply(player).blocked());
    }

    @Test
    void permissionOverrideReducesCooldown() {
        HexChatConfig withoutOverride = TestConfigs.withCooldown(cooldown(10, List.of(), List.of()));
        HexChatConfig withOverride = TestConfigs.withCooldown(
                cooldown(10, List.of(), List.of(new HexChatConfig.PermissionCooldown("group.vip", 1)))
        );

        ChatCooldownService baseline = new ChatCooldownService(() -> withoutOverride, new NoopChatRankResolver());
        Player normal = player();
        baseline.checkAndApply(normal);
        long baselineLeft = baseline.checkAndApply(normal).secondsLeft();

        ChatCooldownService overridden = new ChatCooldownService(() -> withOverride, new NoopChatRankResolver());
        Player vip = player();
        when(vip.hasPermission("group.vip")).thenReturn(true);
        overridden.checkAndApply(vip);
        long overriddenLeft = overridden.checkAndApply(vip).secondsLeft();

        assertTrue(baselineLeft >= 9, "Bez override cooldown ~10s, było: " + baselineLeft);
        assertTrue(overriddenLeft <= 2, "Override powinien skrócić cooldown do ~1s, było: " + overriddenLeft);
    }

    @Test
    void rankCooldownZeroDisablesCooldown() {
        HexChatConfig config = TestConfigs.withCooldown(
                cooldown(10, List.of(new HexChatConfig.GroupCooldown("Free", 0)), List.of())
        );
        ChatRankResolver resolver = new ChatRankResolver() {
            @Override
            public Optional<String> resolveRank(Player player) {
                return Optional.of("Free");
            }

            @Override
            public Optional<Boolean> hasPermission(Player player, String permission) {
                return Optional.empty();
            }
        };
        ChatCooldownService service = new ChatCooldownService(() -> config, resolver);
        Player player = player();

        assertFalse(service.checkAndApply(player).blocked());
        assertFalse(service.checkAndApply(player).blocked(), "Ranga z 0s wyłącza cooldown");
    }

    @Test
    void defaultSecondsZeroDisablesCooldown() {
        HexChatConfig config = TestConfigs.withCooldown(cooldown(0, List.of(), List.of()));
        ChatCooldownService service = new ChatCooldownService(() -> config, new NoopChatRankResolver());
        Player player = player();

        assertFalse(service.checkAndApply(player).blocked());
        assertFalse(service.checkAndApply(player).blocked(), "default-seconds=0 wyłącza cooldown");
    }

    @Test
    void disabledCooldownAlwaysAllows() {
        HexChatConfig.Cooldown disabled = new HexChatConfig.Cooldown(
                false, "hexchat.cooldown.bypass", true, 10, List.of(), List.of()
        );
        HexChatConfig config = TestConfigs.withCooldown(disabled);
        ChatCooldownService service = new ChatCooldownService(() -> config, new NoopChatRankResolver());
        Player player = player();

        assertFalse(service.checkAndApply(player).blocked());
        assertFalse(service.checkAndApply(player).blocked());
    }

    @Test
    void clearRemovesCooldown() {
        HexChatConfig config = TestConfigs.withCooldown(cooldown(10, List.of(), List.of()));
        ChatCooldownService service = new ChatCooldownService(() -> config, new NoopChatRankResolver());
        Player player = player();

        assertFalse(service.checkAndApply(player).blocked());
        assertTrue(service.checkAndApply(player).blocked());

        service.clear(player);
        assertFalse(service.checkAndApply(player).blocked(), "Po clear() cooldown powinien zniknąć");
    }
}
