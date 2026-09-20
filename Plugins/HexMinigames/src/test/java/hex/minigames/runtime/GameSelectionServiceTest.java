package hex.minigames.runtime;

import hex.minigames.game.Minigame;
import hex.minigames.game.MinigameAvailability;
import hex.minigames.game.MinigameDefinition;
import hex.minigames.game.MinigameFactory;
import hex.minigames.game.MinigameRegistry;
import hex.minigames.game.RoundContext;
import hex.minigames.model.BlockPosition;
import hex.minigames.model.CuboidRegion;
import hex.minigames.model.LocationSpec;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class GameSelectionServiceTest {
    @Test
    void selectionDoesNotDuplicateGames() {
        List<MinigameDefinition> pool = List.of(
                definition("one"),
                definition("two"),
                definition("three")
        );

        List<MinigameDefinition> selected = new GameSelectionService().select(pool, 5);

        assertEquals(3, selected.size());
        assertEquals(3, selected.stream().map(MinigameDefinition::id).distinct().count());
    }

    @Test
    void plannedCountAndSelectableCountUseImplementedEnabledAvailableGamesOnly() {
        MinigameRegistry registry = registry("super_memory", "red_light_green_light", "hot_head", "dalgona", "glass_bridge", "popcorn", "breeze_tower", "tag", "jump_rope", "disco_floor");
        registry.rebuild(Map.ofEntries(
                Map.entry("super_memory", definition("super_memory", true)),
                Map.entry("red_light_green_light", definition("red_light_green_light", true)),
                Map.entry("hot_head", definition("hot_head", true)),
                Map.entry("dalgona", definition("dalgona", true)),
                Map.entry("glass_bridge", definition("glass_bridge", true)),
                Map.entry("popcorn", definition("popcorn", true)),
                Map.entry("tag", definition("tag", true)),
                Map.entry("jump_rope", definition("jump_rope", true)),
                Map.entry("elytra", definition("elytra", true)),
                Map.entry("breeze_tower", definition("breeze_tower", true)),
                Map.entry("monkey_run", definition("monkey_run", true)),
                Map.entry("disco_floor", definition("disco_floor", true)),
                Map.entry("mingle", definition("mingle", true))
        ));

        List<MinigameDefinition> eligible = registry.eligible(5, false, false);
        List<MinigameDefinition> selected = new GameSelectionService().select(eligible, 5);

        assertEquals(13, registry.configuredRealGameCount());
        assertEquals(10, eligible.size());
        assertEquals(5, selected.size());
        assertEquals(5, selected.stream().map(MinigameDefinition::id).distinct().count());
        assertEquals(0, selected.stream().filter(def -> List.of("elytra", "monkey_run", "mingle").contains(def.id())).count());
    }

    @Test
    void disabledAndUnavailableGamesAreSkipped() {
        MinigameRegistry registry = registry("one", "two", "bad");
        registry.rebuild(Map.of(
                "one", definition("one", true),
                "two", definition("two", false),
                "bad", definition("bad", true)
        ));

        List<MinigameDefinition> eligible = registry.eligible(1, false, false);

        assertEquals(List.of("one"), eligible.stream().map(MinigameDefinition::id).toList());
    }

    private MinigameRegistry registry(String... ids) {
        MinigameRegistry registry = new MinigameRegistry();
        for (String id : ids) {
            registry.register(new MinigameFactory() {
                @Override
                public String id() {
                    return id;
                }

                @Override
                public boolean internal() {
                    return false;
                }

                @Override
                public Minigame create() {
                    return "bad".equals(id) ? new UnavailableMinigame(id) : new TestMinigame(id);
                }
            });
        }
        return registry;
    }

    private MinigameDefinition definition(String id) {
        return definition(id, true);
    }

    private MinigameDefinition definition(String id, boolean enabled) {
        return new MinigameDefinition(
                id,
                id,
                enabled,
                true,
                false,
                1,
                0,
                1,
                Optional.of(new CuboidRegion("world", new BlockPosition(0, 0, 0), new BlockPosition(10, 10, 10))),
                List.of(new LocationSpec(0, 1, 0, 0, 0, true)),
                Optional.of(new LocationSpec(0, 1, 0, 0, 0, true)),
                10,
                Map.of(),
                "test"
        );
    }

    private record TestMinigame(String id) implements Minigame {
    }

    private record UnavailableMinigame(String id) implements Minigame {
        @Override
        public MinigameAvailability availability(MinigameDefinition definition, int players) {
            return MinigameAvailability.unavailable("test unavailable");
        }
    }
}
