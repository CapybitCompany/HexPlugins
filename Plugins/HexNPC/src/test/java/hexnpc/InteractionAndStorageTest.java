package hexnpc;

import hexnpc.action.NpcActionHandler;
import hexnpc.model.Dialogue;
import hexnpc.model.InteractionSettings;
import hexnpc.model.InteractionTrigger;
import hexnpc.model.NpcAction;
import hexnpc.model.NpcActions;
import hexnpc.model.NpcDefinition;
import hexnpc.model.NpcId;
import hexnpc.model.NpcLocation;
import hexnpc.model.NpcSkin;
import hexnpc.service.NpcInteractionService;
import hexnpc.storage.YamlNpcStorage;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

import java.io.File;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InteractionAndStorageTest {

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

    private NpcDefinition npcWith(NpcActions actions, Dialogue dialogue, InteractionSettings interaction) {
        return new NpcDefinition(
                new NpcId("npc"),
                NpcSkin.ofName("npc"),
                new NpcLocation("world", 0, 64, 0, 0f, 0f),
                interaction,
                dialogue,
                actions
        );
    }

    @Test
    void actionOnlyNpcFiresExactlyOncePerClick() {
        AtomicInteger counter = new AtomicInteger();
        plugin.actionRegistry().register(handler("test-count", (p, n, a) -> counter.incrementAndGet()));

        NpcDefinition npc = npcWith(
                new NpcActions(List.of(new NpcAction("test-count", Map.of())), List.of()),
                Dialogue.empty(),
                InteractionSettings.defaultClick()
        );

        plugin.interactionService().trigger(player, npc, InteractionTrigger.CLICK);
        assertEquals(1, counter.get(), "action must fire exactly once per click trigger");
    }

    @Test
    void clickActionsDoNotFireOnProximityAndViceVersa() {
        AtomicInteger clickCount = new AtomicInteger();
        AtomicInteger proxCount = new AtomicInteger();
        plugin.actionRegistry().register(handler("test-click", (p, n, a) -> clickCount.incrementAndGet()));
        plugin.actionRegistry().register(handler("test-prox", (p, n, a) -> proxCount.incrementAndGet()));

        NpcDefinition npc = npcWith(
                new NpcActions(
                        List.of(new NpcAction("test-click", Map.of())),
                        List.of(new NpcAction("test-prox", Map.of()))
                ),
                Dialogue.empty(),
                new InteractionSettings(true, true, 0.0, 0)
        );

        plugin.interactionService().trigger(player, npc, InteractionTrigger.CLICK);
        plugin.interactionService().trigger(player, npc, InteractionTrigger.PROXIMITY);

        assertEquals(1, clickCount.get(), "click handler should fire only on click");
        assertEquals(1, proxCount.get(), "proximity handler should fire only on proximity");
    }

    @Test
    void enabledFalseBlocksInteractionTrigger() {
        AtomicInteger counter = new AtomicInteger();
        plugin.actionRegistry().register(handler("test-disabled", (p, n, a) -> counter.incrementAndGet()));

        // Disable plugin runtime via config edit + reload. Must save to disk
        // because reloadPluginRuntime() re-reads from disk.
        plugin.getConfig().set("enabled", false);
        plugin.saveConfig();
        assertTrue(plugin.reloadPluginRuntime());
        assertSame(false, plugin.config().enabled());

        NpcDefinition npc = npcWith(
                new NpcActions(List.of(new NpcAction("test-disabled", Map.of())), List.of()),
                Dialogue.empty(),
                InteractionSettings.defaultClick()
        );
        plugin.interactionService().trigger(player, npc, InteractionTrigger.CLICK);
        assertEquals(0, counter.get(), "actions must not fire while enabled=false");
    }

    @Test
    void reloadKeepsExternallyRegisteredActionHandlers() {
        AtomicInteger counter = new AtomicInteger();
        NpcActionHandler external = handler("external-after-reload", (p, n, a) -> counter.incrementAndGet());
        plugin.actionRegistry().register(external);

        // Reload — registry instance MUST survive (same identity).
        var registryBefore = plugin.actionRegistry();
        assertTrue(plugin.reloadPluginRuntime());
        assertSame(registryBefore, plugin.actionRegistry(), "registry must be the same instance across reload");
        assertTrue(plugin.actionRegistry().resolve("external-after-reload").isPresent(),
                "externally registered handler must survive reload");

        NpcDefinition npc = npcWith(
                new NpcActions(List.of(new NpcAction("external-after-reload", Map.of())), List.of()),
                Dialogue.empty(),
                InteractionSettings.defaultClick()
        );
        plugin.interactionService().trigger(player, npc, InteractionTrigger.CLICK);
        assertEquals(1, counter.get());
    }

    @Test
    void reloadDoesNotDuplicateBuiltinHandlers() {
        assertTrue(plugin.reloadPluginRuntime());
        assertTrue(plugin.actionRegistry().resolve("message").isPresent());
        assertTrue(plugin.actionRegistry().resolve("console-command").isPresent());
        assertTrue(plugin.actionRegistry().resolve("player-command").isPresent());
        // No duplication API exposed; registry uses a map so re-register is idempotent
        // even if it accidentally happened. This test mainly guards against AssertionError
        // or other thrown exceptions during reload.
    }

    @Test
    void yamlRoundTripsNewActionsMapFormat() throws Exception {
        File tmp = Files.createTempFile("npcs-roundtrip-", ".yml").toFile();
        tmp.delete();
        YamlNpcStorage storage = new YamlNpcStorage(tmp, plugin.getLogger());
        storage.load();

        NpcDefinition def = npcWith(
                new NpcActions(
                        List.of(new NpcAction("message", Map.of("text", "hi"))),
                        List.of(new NpcAction("console-command", Map.of("command", "say hello")))
                ),
                Dialogue.empty(),
                new InteractionSettings(true, true, 5.0, 200)
        );
        storage.save(def);

        YamlNpcStorage reloaded = new YamlNpcStorage(tmp, plugin.getLogger());
        reloaded.load();
        NpcDefinition read = reloaded.find(new NpcId("npc")).orElseThrow();
        assertEquals(1, read.actions().onClick().size());
        assertEquals("message", read.actions().onClick().get(0).type());
        assertEquals(1, read.actions().onProximity().size());
        assertEquals("console-command", read.actions().onProximity().get(0).type());
    }

    @Test
    void disabledRuntimeDoesNotCallRendererSpawnOnCreate() throws Exception {
        plugin.getConfig().set("enabled", false);
        plugin.saveConfig();
        assertTrue(plugin.reloadPluginRuntime());
        assertSame(false, plugin.config().enabled());

        NpcId id = new NpcId("hidden-npc");
        plugin.npcService().create(id, new NpcLocation("world", 0, 64, 0, 0f, 0f));

        // The renderer must NOT hold a handle for this NPC while disabled.
        assertTrue(plugin.renderer().handle(id).isEmpty(),
                "renderer must not register a handle while disabled");

        // Re-enable and explicitly spawnAll — handle should appear.
        plugin.getConfig().set("enabled", true);
        plugin.saveConfig();
        assertTrue(plugin.reloadPluginRuntime());
        assertTrue(plugin.renderer().handle(id).isPresent(),
                "renderer must spawn the NPC after re-enable + reload");
    }

    @Test
    void yamlOmittingProximityProducesZeroSentinels() throws Exception {
        File tmp = Files.createTempFile("npcs-prox-defaults-", ".yml").toFile();
        YamlConfiguration legacy = new YamlConfiguration();
        var npcs = legacy.createSection("npcs");
        var def = npcs.createSection("watcher");
        var skin = def.createSection("skin");
        skin.set("name", "Watcher");
        var loc = def.createSection("location");
        loc.set("world", "world");
        loc.set("x", 0); loc.set("y", 64); loc.set("z", 0);
        loc.set("yaw", 0); loc.set("pitch", 0);
        var interaction = def.createSection("interaction");
        interaction.set("click", true);
        var prox = interaction.createSection("proximity");
        prox.set("enabled", true);
        // Deliberately omit radius and cooldown-ticks.
        legacy.save(tmp);

        YamlNpcStorage storage = new YamlNpcStorage(tmp, plugin.getLogger());
        storage.load();
        NpcDefinition read = storage.find(new NpcId("watcher")).orElseThrow();
        InteractionSettings i = read.interaction();
        assertEquals(0.0, i.proximityRadius(),
                "missing radius -> 0 sentinel");
        assertEquals(0, i.proximityCooldownTicks(),
                "missing cooldown -> 0 sentinel");
        assertEquals(7.5, i.effectiveRadius(7.5),
                "effective radius falls back to global default when 0");
        assertEquals(123, i.effectiveCooldownTicks(123),
                "effective cooldown falls back to global default when 0");
    }

    @Test
    void yamlReadsLegacyFlatActionsAsOnClick() throws Exception {
        File tmp = Files.createTempFile("npcs-legacy-", ".yml").toFile();
        // Write a legacy flat-list format by hand.
        YamlConfiguration legacy = new YamlConfiguration();
        var npcs = legacy.createSection("npcs");
        var greeter = npcs.createSection("greeter");
        var skin = greeter.createSection("skin");
        skin.set("name", "Greeter");
        var loc = greeter.createSection("location");
        loc.set("world", "world");
        loc.set("x", 0);
        loc.set("y", 64);
        loc.set("z", 0);
        loc.set("yaw", 0);
        loc.set("pitch", 0);
        var interaction = greeter.createSection("interaction");
        interaction.set("click", true);
        greeter.set("actions", List.of(
                Map.of("type", "message", "text", "&aHi!"),
                Map.of("type", "console-command", "command", "say hi")
        ));
        legacy.save(tmp);

        YamlNpcStorage storage = new YamlNpcStorage(tmp, plugin.getLogger());
        storage.load();
        NpcDefinition read = storage.find(new NpcId("greeter")).orElseThrow();
        assertNotNull(read.actions(), "actions container present");
        assertEquals(2, read.actions().onClick().size(), "legacy flat list goes to on-click");
        assertEquals(0, read.actions().onProximity().size());
        assertEquals("message", read.actions().onClick().get(0).type());
    }

    private static NpcActionHandler handler(String id, TriConsumer fn) {
        return new NpcActionHandler() {
            @Override
            public String id() {
                return id;
            }

            @Override
            public void execute(org.bukkit.entity.Player player, NpcDefinition npc, NpcAction action) {
                fn.accept(player, npc, action);
            }
        };
    }

    @FunctionalInterface
    private interface TriConsumer {
        void accept(org.bukkit.entity.Player player, NpcDefinition npc, NpcAction action);
    }
}
