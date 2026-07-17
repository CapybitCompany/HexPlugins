package hexchat.service;

import hexchat.config.HexChatConfig;
import hexchat.support.TestConfigs;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class GlobalChatMuteServiceTest {

    private static Player normalPlayer() {
        Player player = mock(Player.class);
        lenient().when(player.isOp()).thenReturn(false);
        lenient().when(player.hasPermission(org.mockito.ArgumentMatchers.anyString())).thenReturn(false);
        return player;
    }

    @Test
    void normalPlayerIsMutedWhenGlobalMuteActive() {
        HexChatConfig config = TestConfigs.config();
        GlobalChatMuteService service = new GlobalChatMuteService(() -> config, true);

        assertTrue(service.isMuted());
        assertTrue(service.isMutedFor(normalPlayer()), "Normalny gracz powinien być wyciszony");
    }

    @Test
    void opAndAdminAndBypassCanStillChat() {
        HexChatConfig config = TestConfigs.config();
        GlobalChatMuteService service = new GlobalChatMuteService(() -> config, true);

        Player op = normalPlayer();
        when(op.isOp()).thenReturn(true);
        assertFalse(service.isMutedFor(op), "OP nie powinien być wyciszony");

        Player admin = normalPlayer();
        when(admin.hasPermission("hexchat.admin")).thenReturn(true);
        assertFalse(service.isMutedFor(admin), "Admin nie powinien być wyciszony");

        Player bypass = normalPlayer();
        when(bypass.hasPermission("hexchat.chatmute.bypass")).thenReturn(true);
        assertFalse(service.isMutedFor(bypass), "Gracz z bypass nie powinien być wyciszony");
    }

    @Test
    void nobodyIsMutedWhenChatIsNotMuted() {
        HexChatConfig config = TestConfigs.config();
        GlobalChatMuteService service = new GlobalChatMuteService(() -> config, false);

        assertFalse(service.isMuted());
        assertFalse(service.isMutedFor(normalPlayer()));
    }

    @Test
    void nobodyIsMutedWhenModuleDisabled() {
        HexChatConfig config = TestConfigs.withChat(
                TestConfigs.chat(true, TestConfigs.DEFAULT_FORMAT, TestConfigs.globalMute(false, true))
        );
        GlobalChatMuteService service = new GlobalChatMuteService(() -> config, true);

        // Moduł wyłączony -> nawet gdy runtime muted=true, nikt nie jest blokowany.
        assertTrue(service.isMuted());
        assertFalse(service.isMutedFor(normalPlayer()));
    }

    @Test
    void setMutedReturnsPreviousState() {
        HexChatConfig config = TestConfigs.config();
        GlobalChatMuteService service = new GlobalChatMuteService(() -> config, false);

        assertFalse(service.setMuted(true), "setMuted zwraca poprzedni stan (false)");
        assertTrue(service.isMuted());
        assertTrue(service.setMuted(false), "setMuted zwraca poprzedni stan (true)");
        assertFalse(service.isMuted());
    }

    @Test
    void toggleMutedFlipsStateAndReturnsNewValue() {
        HexChatConfig config = TestConfigs.config();
        GlobalChatMuteService service = new GlobalChatMuteService(() -> config, false);

        assertTrue(service.toggleMuted(), "toggle z false -> true");
        assertTrue(service.isMuted());
        assertFalse(service.toggleMuted(), "toggle z true -> false");
        assertFalse(service.isMuted());
    }

    @Test
    void runtimeMuteStateIsPreservedAcrossConfigReload() {
        // Udokumentowanie decyzji: reload NIE resetuje runtime mute state.
        // Supplier zwraca zaktualizowaną konfigurację, ale flaga muted trwa w serwisie.
        AtomicReference<HexChatConfig> configRef = new AtomicReference<>(
                TestConfigs.withChat(TestConfigs.chat(true, TestConfigs.DEFAULT_FORMAT, TestConfigs.globalMute(true, false)))
        );
        GlobalChatMuteService service = new GlobalChatMuteService(configRef::get, false);

        service.setMuted(true);
        assertTrue(service.isMuted());

        // Symulacja reloadu: nowa konfiguracja z initially-muted=false.
        configRef.set(
                TestConfigs.withChat(TestConfigs.chat(true, TestConfigs.DEFAULT_FORMAT, TestConfigs.globalMute(true, false)))
        );

        assertTrue(service.isMuted(), "Reload konfiguracji zachowuje runtime mute state");
    }

    @Test
    void updateInitialStateOnlyAppliesWhenNotAlreadyMuted() {
        AtomicReference<HexChatConfig> configRef = new AtomicReference<>(
                TestConfigs.withChat(TestConfigs.chat(true, TestConfigs.DEFAULT_FORMAT, TestConfigs.globalMute(true, true)))
        );
        GlobalChatMuteService service = new GlobalChatMuteService(configRef::get, false);

        service.updateInitialStateFromConfigIfNeeded();
        assertTrue(service.isMuted(), "initially-muted=true powinno ustawić mute, gdy było false");

        // Gdy już wyciszony, zmiana initially-muted na false nie odmutuje.
        configRef.set(
                TestConfigs.withChat(TestConfigs.chat(true, TestConfigs.DEFAULT_FORMAT, TestConfigs.globalMute(true, false)))
        );
        service.updateInitialStateFromConfigIfNeeded();
        assertTrue(service.isMuted());
    }
}
