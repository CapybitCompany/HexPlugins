package hexnpc.service;

import hexnpc.HexNpcPlugin;
import hexnpc.action.NpcActionHandler;
import hexnpc.model.Dialogue;
import hexnpc.model.DialogueLine;
import hexnpc.model.InteractionSettings;
import hexnpc.model.InteractionTrigger;
import hexnpc.model.NpcAction;
import hexnpc.model.NpcActions;
import hexnpc.model.NpcDefinition;
import hexnpc.model.NpcId;
import hexnpc.model.NpcLocation;
import hexnpc.model.NpcSkin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * Verifiziert, dass nach einem verzögerten Dialog die Actions nur dann
 * laufen, wenn der Spieler noch online ist. Vorher hat speak() einen
 * Runnable nach offset Ticks gefeuert — auch wenn der Spieler in der
 * Zwischenzeit ausgeloggt hatte.
 */
class DialogueServiceOfflineTest {

    private ServerMock server;
    private HexNpcPlugin plugin;
    private PlayerMock player;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        server.addSimpleWorld("world");
        plugin = MockBukkit.load(HexNpcPlugin.class);
        player = server.addPlayer("Tester");
    }

    @AfterEach
    void tearDown() {
        if (MockBukkit.isMocked()) {
            MockBukkit.unmock();
        }
    }

    @Test
    void afterAllSkipsWhenPlayerLogsOutDuringDelay() {
        AtomicInteger count = new AtomicInteger();
        plugin.actionRegistry().register(handler("after-offline", (p, n, a) -> count.incrementAndGet()));

        // Dialog mit zwei Zeilen, jede 40 Ticks Delay → afterAll feuert nach 80.
        NpcDefinition npc = npcWith(
                new NpcActions(List.of(new NpcAction("after-offline", Map.of())), List.of()),
                new Dialogue(List.of(
                        new DialogueLine("first", 40),
                        new DialogueLine("second", 40)
                ), 0)
        );

        plugin.interactionService().trigger(player, npc, InteractionTrigger.CLICK);

        // Halb durch — Spieler loggt aus.
        server.getScheduler().performTicks(40);
        player.disconnect();
        assertEquals(0, count.get(), "Vor dem Ende des Dialogs darf nichts laufen");

        // Restlichen Ticks abspielen — afterAll feuert, darf aber nicht ausführen.
        server.getScheduler().performTicks(60);
        assertEquals(0, count.get(),
                "Actions dürfen NICHT laufen, wenn Spieler offline ist");
    }

    @Test
    void noLinesPathRunsImmediatelyForOnlinePlayer() {
        AtomicInteger count = new AtomicInteger();
        AtomicReference<String> playerName = new AtomicReference<>();
        plugin.actionRegistry().register(handler("immediate-action", (p, n, a) -> {
            playerName.set(p.getName());
            count.incrementAndGet();
        }));

        NpcDefinition npc = npcWith(
                new NpcActions(List.of(new NpcAction("immediate-action", Map.of())), List.of()),
                Dialogue.empty()
        );

        plugin.interactionService().trigger(player, npc, InteractionTrigger.CLICK);
        // Keine Lines → Sofort-Pfad. Kein Tick-Vorlauf nötig.
        assertEquals(1, count.get(), "Sofort-Pfad muss für online Spieler genau einmal laufen");
        assertEquals("Tester", playerName.get(), "Action bekommt aktuellen Player");
    }

    @Test
    void delayedAfterAllRunsForStillOnlinePlayer() {
        AtomicInteger count = new AtomicInteger();
        AtomicReference<Object> handedPlayer = new AtomicReference<>();
        plugin.actionRegistry().register(handler("delayed-action", (p, n, a) -> {
            handedPlayer.set(p);
            count.incrementAndGet();
        }));

        NpcDefinition npc = npcWith(
                new NpcActions(List.of(new NpcAction("delayed-action", Map.of())), List.of()),
                new Dialogue(List.of(new DialogueLine("hi", 20)), 0)
        );

        plugin.interactionService().trigger(player, npc, InteractionTrigger.CLICK);
        assertEquals(0, count.get(), "vor dem Ende der Dialogzeilen darf nichts laufen");

        // Genau ausreichend Ticks für Line + afterAll (offset = 20).
        server.getScheduler().performTicks(20);
        assertEquals(1, count.get(), "afterAll muss für noch online verbundenen Spieler laufen");
        assertNotNull(handedPlayer.get(), "Action erhält Player-Referenz");
        assertSame(player, handedPlayer.get(),
                "Callback bekommt frisch aufgelöste Player-Instanz aus der UUID");
    }

    private NpcDefinition npcWith(NpcActions actions, Dialogue dialogue) {
        return new NpcDefinition(
                new NpcId("npc"),
                NpcSkin.ofName("npc"),
                new NpcLocation("world", 0, 64, 0, 0f, 0f),
                InteractionSettings.defaultClick(),
                dialogue,
                actions
        );
    }

    private static NpcActionHandler handler(String id, TriConsumer fn) {
        return new NpcActionHandler() {
            @Override
            public String id() {
                return id;
            }

            @Override
            public void execute(org.bukkit.entity.Player p, NpcDefinition n, NpcAction a) {
                fn.accept(p, n, a);
            }
        };
    }

    @FunctionalInterface
    private interface TriConsumer {
        void accept(org.bukkit.entity.Player p, NpcDefinition n, NpcAction a);
    }
}
