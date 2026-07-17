package hexchat.service;

import hexchat.config.HexChatConfig;
import hexchat.mute.InMemoryMuteStorage;
import hexchat.mute.MuteEntry;
import hexchat.mute.MuteStorage;
import hexchat.support.TestConfigs;
import hexchat.util.DurationUtil;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PlayerMuteServiceTest {

    private static Player normalPlayer(UUID uuid) {
        Player player = mock(Player.class);
        lenient().when(player.getUniqueId()).thenReturn(uuid);
        lenient().when(player.isOp()).thenReturn(false);
        lenient().when(player.hasPermission(anyString())).thenReturn(false);
        return player;
    }

    private static HexChatConfig enabledConfig() {
        return TestConfigs.config();
    }

    @Test
    void permanentMuteBlocksNormalPlayer() {
        UUID uuid = UUID.randomUUID();
        PlayerMuteService service = new PlayerMuteService(TestConfigs::config, new InMemoryMuteStorage());

        service.mute(uuid, "Steve", DurationUtil.PERMANENT, "obraza");

        assertTrue(service.activeMute(uuid).isPresent());
        assertTrue(service.activeMute(uuid).get().permanent());
        assertTrue(service.isMutedFor(normalPlayer(uuid)));
    }

    @Test
    void temporaryMuteExpiresAfterDuration() {
        UUID uuid = UUID.randomUUID();
        AtomicLong now = new AtomicLong(1_000L);
        LongSupplier clock = now::get;
        PlayerMuteService service = new PlayerMuteService(TestConfigs::config, new InMemoryMuteStorage(), clock);

        service.mute(uuid, "Steve", 60_000L, "spam");
        assertTrue(service.activeMute(uuid).isPresent());

        now.set(1_000L + 60_001L);
        assertFalse(service.activeMute(uuid).isPresent(), "Wyciszenie powinno wygasnąć");
        assertFalse(service.isMutedFor(normalPlayer(uuid)));
    }

    @Test
    void unmuteRemovesActiveMute() {
        UUID uuid = UUID.randomUUID();
        PlayerMuteService service = new PlayerMuteService(TestConfigs::config, new InMemoryMuteStorage());
        service.mute(uuid, "Steve", DurationUtil.PERMANENT, "x");

        assertTrue(service.unmute(uuid), "unmute zwraca true dla aktywnego wyciszenia");
        assertFalse(service.activeMute(uuid).isPresent());
        assertFalse(service.unmute(uuid), "unmute zwraca false, gdy gracz nie był wyciszony");
    }

    @Test
    void opAdminAndBypassAreNeverMuted() {
        UUID uuid = UUID.randomUUID();
        PlayerMuteService service = new PlayerMuteService(TestConfigs::config, new InMemoryMuteStorage());
        service.mute(uuid, "Steve", DurationUtil.PERMANENT, "x");

        Player op = normalPlayer(uuid);
        when(op.isOp()).thenReturn(true);
        assertFalse(service.isMutedFor(op));

        Player admin = normalPlayer(uuid);
        when(admin.hasPermission("hexchat.admin")).thenReturn(true);
        assertFalse(service.isMutedFor(admin));

        Player bypass = normalPlayer(uuid);
        when(bypass.hasPermission("hexchat.mute.bypass")).thenReturn(true);
        assertFalse(service.isMutedFor(bypass));
    }

    @Test
    void disabledModuleDoesNotMute() {
        UUID uuid = UUID.randomUUID();
        HexChatConfig disabled = TestConfigs.withPlayerMute(
                new HexChatConfig.PlayerMute(false, "hexchat.mute.bypass", "powód")
        );
        PlayerMuteService service = new PlayerMuteService(() -> disabled, new InMemoryMuteStorage());
        service.mute(uuid, "Steve", DurationUtil.PERMANENT, "x");

        assertFalse(service.isMutedFor(normalPlayer(uuid)), "Wyłączony moduł nie wycisza");
    }

    @Test
    void existingMutesAreLoadedFromStorageOnConstruction() {
        UUID uuid = UUID.randomUUID();
        MuteStorage storage = new InMemoryMuteStorage();
        storage.save(new MuteEntry(uuid, "Steve", 0L, "poprzednie", 500L));

        PlayerMuteService service = new PlayerMuteService(TestConfigs::config, storage);

        assertTrue(service.activeMute(uuid).isPresent());
        assertEquals("poprzednie", service.activeMute(uuid).get().reason());
    }

    @Test
    void muteIsPersistedToStorage() {
        UUID uuid = UUID.randomUUID();
        MuteStorage storage = new InMemoryMuteStorage();
        PlayerMuteService service = new PlayerMuteService(() -> enabledConfig(), storage);

        service.mute(uuid, "Steve", DurationUtil.PERMANENT, "x");

        assertTrue(storage.loadAll().containsKey(uuid), "Wyciszenie powinno zostać zapisane w storage");
    }
}
