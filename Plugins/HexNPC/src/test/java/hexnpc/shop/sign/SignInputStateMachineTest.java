package hexnpc.shop.sign;

import hexnpc.HexNpcPlugin;
import hexnpc.shop.config.ShopConfig;
import hexnpc.shop.config.ShopMessages;
import hexnpc.shop.model.ShopLayout;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Maszyna stanów wejścia ilości oraz odporność transportu wirtualnej tabliczki:
 * pozycja (nie blok stóp), etapy pakietów, brak ghost-bloku przy błędach,
 * dual sign+czat, jednokrotny cleanup, failover, timeout.
 */
class SignInputStateMachineTest {

    private ServerMock server;
    private HexNpcPlugin plugin;
    private PlayerMock player;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        server.addSimpleWorld("world");
        plugin = MockBukkit.load(HexNpcPlugin.class);
        player = server.addPlayer("Signer");
    }

    @AfterEach
    void tearDown() {
        if (MockBukkit.isMocked()) {
            MockBukkit.unmock();
        }
    }

    private Supplier<ShopConfig> cfg(int timeout, int failover) {
        return () -> new ShopConfig(true, true, "&8x", true, ShopLayout.defaults(54),
                List.of(1, 64), true, true, true, timeout, failover, 2,
                ShopConfig.Confirmation.defaults(), ShopConfig.AuditLog.defaults(), ShopMessages.defaults());
    }

    /** Atrapa transportu: konfigurowalny etap, śledzi ghost-blok i przywracanie. */
    private static final class FakeTransport implements SignTransport {
        boolean available = true;
        Stage stage = Stage.OPEN_EDITOR;
        int openCalls = 0;
        int restoreCalls = 0;
        boolean ghostPresent = false;
        int lastX, lastY, lastZ;
        SignInputSink instantSink;
        UUID instantUuid;
        String instantReply;

        @Override
        public boolean isAvailable() {
            return available;
        }

        @Override
        public OpenResult openEditor(Player p, int x, int y, int z) {
            openCalls++;
            lastX = x;
            lastY = y;
            lastZ = z;
            if (stage != Stage.NONE) {
                ghostPresent = true; // wysłano fałszywy blok
            }
            if (instantSink != null) {
                instantSink.onSignUpdate(instantUuid, x, y, z, new String[]{instantReply});
            }
            return stage == Stage.NONE ? OpenResult.none("brak") : new OpenResult(stage, null);
        }

        @Override
        public void restore(Player p, int x, int y, int z) {
            restoreCalls++;
            ghostPresent = false;
        }
    }

    private SignInputService svc(FakeTransport t, int timeout, int failover) {
        return new SignInputService(plugin, cfg(timeout, failover), t);
    }

    private void chat(SignInputService svc, String message) {
        AsyncPlayerChatEvent event = new AsyncPlayerChatEvent(false, player, message,
                new HashSet<>(server.getOnlinePlayers()));
        svc.onChat(event);
    }

    private void request(SignInputService svc, List<String> inputs, AtomicBoolean timedOut) {
        svc.request(player, u -> { }, () -> { }, inputs::add, () -> timedOut.set(true));
    }

    // --- Pozycja ---

    @Test
    void virtualPositionIsNotFootBlockAndInBounds() {
        World world = player.getWorld();
        int feet = player.getLocation().getBlockY();
        int y = SignInputService.virtualY(world, feet);
        assertNotEquals(feet, y, "pozycja tabliczki nie może być blokiem stóp gracza");
        assertEquals(feet + 2, y, "domyślnie 2 bloki nad blokiem gracza");
        assertTrue(y > world.getMinHeight() && y < world.getMaxHeight(), "w granicach świata");

        FakeTransport t = new FakeTransport();
        request(svc(t, 30, 4), new ArrayList<>(), new AtomicBoolean());
        assertNotEquals(feet, t.lastY, "wysłana pozycja nie jest blokiem stóp");
    }

    @Test
    void virtualPositionClampsAtWorldCeiling() {
        World world = player.getWorld();
        int max = world.getMaxHeight() - 1;
        int y = SignInputService.virtualY(world, max);
        assertTrue(y <= max && y >= world.getMinHeight());
        assertNotEquals(max, y, "przy suficie schodzimy poniżej, nie zostajemy na bloku stóp");
    }

    // --- Etapy pakietów / ghost-blok ---

    @Test
    void fullSuccessActivatesSignModeAndRevertsOnComplete() {
        FakeTransport t = new FakeTransport();
        t.stage = SignTransport.Stage.OPEN_EDITOR;
        SignInputService s = svc(t, 30, 4);
        List<String> inputs = new ArrayList<>();
        request(s, inputs, new AtomicBoolean());
        assertTrue(t.ghostPresent, "po pełnym otwarciu istnieje fałszywy blok");
        s.onSignUpdate(player.getUniqueId(), t.lastX, t.lastY, t.lastZ, new String[]{"64"});
        assertEquals(List.of("64"), inputs, "pełny sukces aktywuje tryb sign");
        assertEquals(1, t.restoreCalls, "zakończenie przywraca realny blok");
        assertFalse(t.ghostPresent, "brak ghost-bloku po zakończeniu");
    }

    @Test
    void blockEntityFailureIsNotSuccessAndRevertsImmediately() {
        FakeTransport t = new FakeTransport();
        t.stage = SignTransport.Stage.BLOCK_CHANGE; // block-entity padło
        SignInputService s = svc(t, 30, 4);
        List<String> inputs = new ArrayList<>();
        request(s, inputs, new AtomicBoolean());
        // Częściowy błąd: natychmiastowe przywrócenie, żaden ghost nie zostaje.
        assertEquals(1, t.restoreCalls);
        assertFalse(t.ghostPresent, "brak ghost-bloku po częściowym błędzie");
        // Nie sukces -> UPDATE_SIGN ignorowane, ale czat działa.
        s.onSignUpdate(player.getUniqueId(), t.lastX, t.lastY, t.lastZ, new String[]{"9"});
        assertTrue(inputs.isEmpty(), "częściowy etap nie aktywuje trybu sign");
        chat(s, "5");
        assertEquals(List.of("5"), inputs);
    }

    @Test
    void openEditorFailureRevertsFakeBlock() {
        FakeTransport t = new FakeTransport();
        t.stage = SignTransport.Stage.BLOCK_ENTITY; // OpenSignEditor padło
        SignInputService s = svc(t, 30, 4);
        List<String> inputs = new ArrayList<>();
        request(s, inputs, new AtomicBoolean());
        assertEquals(1, t.restoreCalls, "błąd OpenSignEditor przywraca fałszywy blok");
        assertFalse(t.ghostPresent);
        s.onSignUpdate(player.getUniqueId(), t.lastX, t.lastY, t.lastZ, new String[]{"9"});
        assertTrue(inputs.isEmpty());
    }

    @Test
    void firstPacketFailureLeavesNoGhostAndUsesChat() {
        FakeTransport t = new FakeTransport();
        t.stage = SignTransport.Stage.NONE; // nic nie wysłano
        SignInputService s = svc(t, 30, 4);
        List<String> inputs = new ArrayList<>();
        request(s, inputs, new AtomicBoolean());
        assertFalse(t.ghostPresent, "nic nie wysłano — brak ghost-bloku");
        assertEquals(0, t.restoreCalls, "nie ma czego przywracać");
        chat(s, "3");
        assertEquals(List.of("3"), inputs, "natychmiastowy fallback na czat");
    }

    @Test
    void onlyOpenEditorStageActivatesSignMode() {
        for (SignTransport.Stage st : new SignTransport.Stage[]{
                SignTransport.Stage.NONE, SignTransport.Stage.BLOCK_CHANGE, SignTransport.Stage.BLOCK_ENTITY}) {
            FakeTransport t = new FakeTransport();
            t.stage = st;
            SignInputService s = svc(t, 30, 4);
            List<String> inputs = new ArrayList<>();
            request(s, inputs, new AtomicBoolean());
            s.onSignUpdate(player.getUniqueId(), t.lastX, t.lastY, t.lastZ, new String[]{"7"});
            assertTrue(inputs.isEmpty(), "etap " + st + " nie może aktywować trybu sign");
        }
    }

    // --- Akceptacja / cleanup ---

    @Test
    void acceptsOnlyExpectedPositionAndFiresOnce() {
        FakeTransport t = new FakeTransport();
        SignInputService s = svc(t, 30, 4);
        List<String> inputs = new ArrayList<>();
        request(s, inputs, new AtomicBoolean());
        s.onSignUpdate(player.getUniqueId(), t.lastX + 1, t.lastY, t.lastZ, new String[]{"9"});
        assertTrue(inputs.isEmpty(), "obca pozycja jest ignorowana");
        s.onSignUpdate(player.getUniqueId(), t.lastX, t.lastY, t.lastZ, new String[]{"64"});
        assertEquals(List.of("64"), inputs);
        s.onSignUpdate(player.getUniqueId(), t.lastX, t.lastY, t.lastZ, new String[]{"128"});
        assertEquals(List.of("64"), inputs, "callback tylko raz");
        assertEquals(1, t.restoreCalls, "cleanup dokładnie raz");
    }

    @Test
    void timeoutCleansUpOnce() {
        FakeTransport t = new FakeTransport();
        SignInputService s = svc(t, 1, 1);
        List<String> inputs = new ArrayList<>();
        AtomicBoolean timedOut = new AtomicBoolean(false);
        request(s, inputs, timedOut);
        server.getScheduler().performTicks(25L);
        assertTrue(timedOut.get(), "twardy timeout woła onTimeout");
        assertEquals(1, t.restoreCalls, "timeout przywraca blok raz");
        s.onSignUpdate(player.getUniqueId(), t.lastX, t.lastY, t.lastZ, new String[]{"5"});
        assertTrue(inputs.isEmpty());
    }

    @Test
    void silentEditorStillAcceptsChat() {
        FakeTransport t = new FakeTransport();
        SignInputService s = svc(t, 30, 4);
        List<String> inputs = new ArrayList<>();
        request(s, inputs, new AtomicBoolean());
        chat(s, "7");
        assertEquals(List.of("7"), inputs);
    }

    @Test
    void pendingRegisteredBeforePacketSend() {
        FakeTransport t = new FakeTransport();
        SignInputService s = svc(t, 30, 4);
        t.instantSink = s;
        t.instantUuid = player.getUniqueId();
        t.instantReply = "42";
        List<String> inputs = new ArrayList<>();
        request(s, inputs, new AtomicBoolean());
        assertEquals(List.of("42"), inputs, "pending istnieje przed wysyłką pakietów");
    }

    @Test
    void failoverSendsChatHintThenChatWorks() {
        FakeTransport t = new FakeTransport();
        SignInputService s = svc(t, 3, 1);
        List<String> inputs = new ArrayList<>();
        AtomicBoolean failover = new AtomicBoolean(false);
        s.request(player, u -> { }, () -> failover.set(true), inputs::add, () -> { });
        server.getScheduler().performTicks(25L);
        assertTrue(failover.get(), "krótki failover wysyła podpowiedź o czacie");
        assertTrue(inputs.isEmpty(), "failover nie kończy sesji");
        chat(s, "9");
        assertEquals(List.of("9"), inputs);
    }

    @Test
    void cancelStopsFurtherInput() {
        FakeTransport t = new FakeTransport();
        SignInputService s = svc(t, 30, 4);
        List<String> inputs = new ArrayList<>();
        request(s, inputs, new AtomicBoolean());
        s.cancel(player.getUniqueId());
        assertEquals(1, t.restoreCalls, "anulowanie przywraca blok");
        s.onSignUpdate(player.getUniqueId(), t.lastX, t.lastY, t.lastZ, new String[]{"5"});
        assertTrue(inputs.isEmpty());
    }
}
