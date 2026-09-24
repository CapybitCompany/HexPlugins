package hex.minigames.game.drones;

import hex.minigames.config.*;
import hex.minigames.game.*;
import hex.minigames.runtime.*;
import hex.minigames.score.*;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class DronesRegistryTest {
    @Test void actualLoaderDerivesFourteenSpawnsAndRegistrySelectsForOneOrFourPlayers() throws Exception {
        Plugin plugin=plugin(); List<String> errors=new ArrayList<>(); var definition=load(plugin,DronesFixtures.yaml(),errors);
        assertTrue(errors.isEmpty()); assertEquals(14,definition.participantSpawns().size());
        assertEquals(-73.5,definition.participantSpawns().getFirst().x()); assertEquals(90,definition.participantSpawns().getFirst().yaw());
        assertEquals(-213.5,definition.participantSpawns().get(7).x()); assertEquals(-90,definition.participantSpawns().get(7).yaw());
        MinigameRegistry registry=new MinigameRegistry(); registry.register(new MinigameFactory() {
            public String id() { return "drones"; } public boolean internal() { return false; }
            public Minigame create() { return new DronesMinigame(plugin); }
        });
        registry.rebuild(Map.of("drones",definition));
        assertEquals(1,registry.eligible(1,false,false).size()); assertEquals(1,registry.eligible(4,false,false).size());
        registry.rebuild(Map.of("drones",definition.withEnabled(false))); assertTrue(registry.eligible(4,false,false).isEmpty());
    }
    @Test void invalidDronesFileDoesNotInvalidateGlobalConfiguration() throws Exception {
        Plugin plugin=plugin(); List<String> globalErrors=new ArrayList<>(); var yaml=DronesFixtures.yaml();
        yaml.set("round-time-seconds",0); yaml.set("region.pos1.x",null);
        var definition=load(plugin,yaml,globalErrors);
        assertTrue(globalErrors.isEmpty()); assertFalse(new DronesMinigame(plugin).availability(definition,1).available());
    }
    @Test void pointsFlowThroughPendingSeriesScoreExactlyOnceWithoutPermanentWrite() {
        Plugin plugin=plugin(); MinigamesScoreRepository repository=mock(MinigamesScoreRepository.class);
        when(repository.allScores()).thenReturn(List.of());
        try(ScoreService scores=new ScoreService(plugin,repository)) {
            UUID id=UUID.randomUUID(); var definition=DronesFixtures.definition();
            MinigamesSession session=new MinigamesSession(UUID.randomUUID(),SessionMode.ADMIN_TEST,null,Set.of(id),List.of(definition));
            RoundSession round=new RoundSession(1,definition,new DronesMinigame(plugin),Set.of(id),6000);
            RoundResult result=new RoundResult(Map.of(id,new PlayerRoundResult(4,OptionalInt.of(1),true,false,Map.of("completion_time_ms","1"))),Map.of("game","drones"));
            scores.applyRoundResult(session,round,result); scores.applyRoundResult(session,round,result);
            assertEquals(4,session.seriesScore().points(id)); verify(repository,never()).commitSeriesPoints(any(),any(),anyInt());
        }
    }
    private MinigameDefinition load(Plugin plugin,YamlConfiguration yaml,List<String> errors) throws Exception {
        var method=MinigamesConfigLoader.class.getDeclaredMethod("loadGame",String.class,YamlConfiguration.class,GlobalConfig.class,List.class); method.setAccessible(true);
        GlobalConfig global=mock(GlobalConfig.class); when(global.worldName()).thenReturn("Hex_Minigames"); when(global.defaultRoundSeconds()).thenReturn(300);
        return (MinigameDefinition)method.invoke(new MinigamesConfigLoader(plugin),"drones.yml",yaml,global,errors);
    }
    private Plugin plugin() { Plugin plugin=mock(Plugin.class); when(plugin.namespace()).thenReturn("hexminigames"); when(plugin.getLogger()).thenReturn(java.util.logging.Logger.getAnonymousLogger()); return plugin; }
}
