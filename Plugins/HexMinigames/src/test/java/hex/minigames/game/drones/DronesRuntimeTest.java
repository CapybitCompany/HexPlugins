package hex.minigames.game.drones;

import hex.minigames.game.*;
import hex.minigames.model.*;
import org.bukkit.*;
import org.bukkit.plugin.Plugin;
import org.bukkit.util.Vector;
import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static hex.minigames.game.drones.DronesPuzzles.*;

class DronesRuntimeTest {
    @Test void fourteenStationsHaveCorrectSpawnsFacingAndIndependentNormalizedGates() {
        DronesConfig config=new DronesConfig(DronesFixtures.definition());
        assertEquals(14,config.stations.size());
        for(int i=0;i<14;i++) {
            boolean west=i<7; var station=config.stations.get(i); int z=west?81-i*3:61+(i-7)*3;
            assertEquals(new BlockPosition(west?-74:-214,9,z),station.spawn());
            assertEquals(west,station.west()); assertEquals(west?-73:-215,station.gate().minX());
            assertEquals(z,station.gate().minZ()); assertEquals(9,station.gate().minY()); assertEquals(11,station.gate().maxY());
        }
        var yaml=DronesFixtures.yaml(); yaml.set("settings.stations.1.gate-region.pos1.y",11); yaml.set("settings.stations.1.gate-region.pos2.y",9);
        assertEquals(9,new DronesConfig(DronesFixtures.definition(yaml)).stations.getFirst().gate().minY());
    }
    @Test void launchUsesStationFacingNeverCameraYaw() {
        var config=new DronesConfig(DronesFixtures.definition());
        World world=mock(World.class);
        for(int i=0;i<14;i++) {
            var station=config.stations.get(i);
            Location camera=station.location(world); camera.setYaw(180);
            Location launch=station.launch(world,3);
            assertEquals(i<7?-76.5:-210.5,launch.getX()); assertEquals(station.spawn().z()+.5,launch.getZ()); assertEquals(9,launch.getY());
        }
    }
    @Test void badConfigurationDisablesOnlyGameAndOnePlayerIsEligible() {
        Plugin plugin=mock(Plugin.class); when(plugin.namespace()).thenReturn("hexminigames");
        DronesMinigame game=new DronesMinigame(plugin);
        assertTrue(game.availability(DronesFixtures.definition(),1).available());
        assertFalse(game.availability(DronesFixtures.definition(),15).available());
        var yaml=DronesFixtures.yaml(); yaml.set("settings.drone.drag",1.5);
        assertFalse(game.availability(DronesFixtures.definition(yaml),1).available());
        yaml=DronesFixtures.yaml(); yaml.set("settings.stations.14",null);
        assertFalse(game.availability(DronesFixtures.definition(yaml),1).available());
    }
    @Test void physicsHasDragSpeedCapsAndBoostCooldown() {
        var config=new DronesConfig(DronesFixtures.definition()).physics; var physics=new DronePhysics();
        var forward=new DronePhysics.Controls(true,false,false,false,false,false,false);
        Vector value=physics.tick(forward,new Vector(0,0,1),0,config);
        Vector decayed=physics.tick(DronePhysics.Controls.NONE,new Vector(0,0,1),1,config);
        assertEquals(value.getZ()*config.drag(),decayed.getZ(),1e-10);
        var boost=new DronePhysics.Controls(true,false,false,false,true,false,true);
        physics.tick(boost,new Vector(0,0,1),2,config); assertEquals(52,physics.readyAt());
        physics.tick(DronePhysics.Controls.NONE,new Vector(0,0,1),3,config);
        physics.tick(boost,new Vector(0,0,1),4,config); assertEquals(52,physics.readyAt());
        for(int i=5;i<100;i++) { var v=physics.tick(boost,new Vector(0,0,1),i,config); assertTrue(v.getY()<=config.maxVerticalSpeed()*config.boostMultiplier()); assertTrue(v.length()<2); }
        physics.tick(DronePhysics.Controls.NONE,new Vector(0,0,1),100,config);
        physics.tick(boost,new Vector(0,0,1),101,config); assertEquals(151,physics.readyAt());
        physics.stop(); assertEquals(new Vector(),physics.tick(DronePhysics.Controls.NONE,new Vector(0,0,1),102,config));
    }
    @Test void regionConfinementIncludesBodyAtWorldEdges() {
        var region=new DronesConfig(DronesFixtures.definition()).region; World world=mock(World.class); when(world.getName()).thenReturn("Hex_Minigames");
        assertTrue(DronePhysics.confined(region,new Location(world,-144,9,70),.35));
        assertFalse(DronePhysics.confined(region,new Location(world,-241.99,9,70),.35));
        assertFalse(DronePhysics.confined(region,new Location(world,-144,45.9,70),.35));
        assertFalse(DronePhysics.confined(region,new Location(world,-243,9,70),.35));
    }
    @Test void cablesRequireCorrectDragAndHoldPenaltyFortyTicks() {
        Cables puzzle=new Cables(new Random(7)); var source=puzzle.targets().get(0);
        assertFalse(puzzle.drag(source,Set.of(1),10)); assertTrue(puzzle.locked(49)); assertFalse(puzzle.locked(50));
        assertFalse(puzzle.drag(source,Set.of(0),49)); assertTrue(puzzle.drag(source,Set.of(0),50));
        assertFalse(puzzle.drag(source,Set.of(0),51));
        for(int i=1;i<4;i++) assertTrue(puzzle.drag(puzzle.targets().get(i),Set.of(i),52));
        assertTrue(puzzle.done());
    }
    @Test void coresRequirePhysicalFiveSlotArrangementWithoutPenalty() {
        Cores puzzle=new Cores(new Random(5));
        assertEquals(5,new HashSet<>(puzzle.order()).size()); assertEquals(5,new HashSet<>(puzzle.sources()).size());
        var color=puzzle.exchange(5,null); puzzle.exchange(0,color); assertFalse(puzzle.done()); assertFalse(puzzle.locked(0));
        color=puzzle.exchange(0,null); puzzle.exchange(5,color);
        DronesFixtures.solve(puzzle,0); assertTrue(puzzle.done());
        for(int i=0;i<5;i++) assertEquals(puzzle.order().get(i),puzzle.at(i));
        assertNotEquals(new Cores(new Random(1)).order(),new Cores(new Random(2)).order());
    }
    @Test void calibrationOvershootRestoresAllOriginalValuesWithoutReroll() {
        for(int seed=0;seed<20;seed++) {
            Calibration puzzle=new Calibration(new Random(seed)); assertTrue(puzzle.target()>=2&&puzzle.target()<=5);
            for(int i=0;i<3;i++) assertTrue(puzzle.level(i)<puzzle.target());
            while(puzzle.level(0)<puzzle.target()) puzzle.increment(0,0);
            assertFalse(puzzle.increment(0,10)); assertTrue(puzzle.locked(49));
            puzzle.tick(50); for(int i=0;i<3;i++) assertEquals(puzzle.original(i),puzzle.level(i));
            DronesFixtures.solve(puzzle,50); assertTrue(puzzle.done());
        }
    }
    @Test void sequenceGrowsSamePrefixAndErrorRepeatsCurrentStage() {
        Sequence puzzle=new Sequence(new Random(3),12,5,0); assertEquals(5,new HashSet<>(puzzle.base()).size());
        assertFalse(puzzle.click(puzzle.base().getFirst(),0));
        long tick=34;
        for(int length=2;length<5;length++) {
            assertEquals(length,puzzle.length()); while(puzzle.showing(tick)) tick++;
            for(int i=0;i<length;i++) assertTrue(puzzle.click(puzzle.base().get(i),tick++));
        }
        assertEquals(5,puzzle.length()); while(puzzle.showing(tick)) tick++;
        assertFalse(puzzle.click((puzzle.base().getFirst()+1)%54,tick));
        assertTrue(puzzle.locked(tick+39)); assertEquals(5,puzzle.length());
        tick+=40; assertTrue(puzzle.showing(tick)); var base=puzzle.base(); puzzle.reopen(tick);
        assertEquals(base,puzzle.base()); DronesFixtures.solve(puzzle,tick); assertTrue(puzzle.done());
    }
    @Test void generatorKeepsTargetAndStageOnMissAndFinishesAfterThreeSuccesses() {
        var config=new DronesConfig(DronesFixtures.definition()); Generator puzzle=new Generator(new Random(3),config.stages,0);
        long tick=0; while(puzzle.cursor(tick)>=puzzle.target()&&puzzle.cursor(tick)<puzzle.target()+puzzle.width()) tick++;
        int target=puzzle.target(); assertFalse(puzzle.stop(tick)); assertEquals(0,puzzle.stage()); assertEquals(target,puzzle.target()); assertTrue(puzzle.locked(tick+39));
        DronesFixtures.solve(puzzle,tick+40); assertTrue(puzzle.done()); assertEquals(3,puzzle.stage()); assertFalse(puzzle.stop(10000));
    }
    @Test void checkpointsAnyOrderLaunchOnceReopenSameInstanceAndGeneratorRequiresFour() {
        UUID id=UUID.randomUUID(); AtomicLong clock=new AtomicLong(100); var runtime=runtime(List.of(id),clock);
        assertFalse(runtime.launch(id)); runtime.start(); assertTrue(runtime.launch(id)); assertFalse(runtime.launch(id)); assertNull(runtime.generator(id,0));
        long tick=0;
        for(int cp:new int[]{3,0,2,1}) {
            var puzzle=runtime.puzzle(id,cp,tick); assertSame(puzzle,runtime.puzzle(id,cp,tick+1));
            tick=DronesFixtures.solve(puzzle,tick); assertTrue(runtime.complete(id,cp)); assertFalse(runtime.complete(id,cp)); assertNull(runtime.puzzle(id,cp,tick));
        }
        assertEquals(DronesRuntime.Phase.GENERATOR,runtime.player(id).phase()); assertEquals(4,runtime.player(id).count());
        var generator=runtime.generator(id,tick); assertSame(generator,runtime.generator(id,tick)); tick=DronesFixtures.solve(generator,tick);
        clock.set(102_381_000_100L); assertTrue(runtime.finish(id)); assertFalse(runtime.finish(id)); assertTrue(runtime.allFinished()); assertNull(runtime.generator(id,tick));
        assertEquals("01:42.381",DronesRuntime.format(runtime.nanos(id)));
    }
    @Test void finalOrderUsesMonotonicTimeAwardsRangesAndDnfZeroOnlyOnce() {
        List<UUID> ids=new ArrayList<>(); for(int i=0;i<14;i++) ids.add(UUID.randomUUID()); AtomicLong clock=new AtomicLong(1); var runtime=runtime(ids,clock); runtime.start();
        for(int index=0;index<13;index++) {
            UUID id=ids.get(index); runtime.launch(id); long tick=0;
            for(int cp=0;cp<4;cp++) { tick=DronesFixtures.solve(runtime.puzzle(id,cp,tick),tick); runtime.complete(id,cp); }
            DronesFixtures.solve(runtime.generator(id,tick),tick); clock.addAndGet(1_000_000_000L); assertTrue(runtime.finish(id));
        }
        assertFalse(runtime.allFinished()); var result=runtime.result(); assertSame(result,runtime.result());
        for(int i=0;i<13;i++) assertEquals(i==0?4:i<=2?3:i<=8?2:1,result.players().get(ids.get(i)).points());
        assertEquals(0,result.players().get(ids.get(13)).points()); assertTrue(result.players().get(ids.get(13)).failed());
    }
    @Test void exactThreeHundredSecondsTimeoutDoesNotIncludeTutorialOrStopAtOneActive() {
        AtomicLong clock=new AtomicLong(); UUID id=UUID.randomUUID(); var runtime=runtime(List.of(id),clock);
        clock.set(20_000_000_000L); runtime.start(); assertEquals(300,runtime.remainingSeconds()); assertFalse(runtime.allFinished()); assertTrue(runtime.launch(id));
        clock.set(319_999_999_999L); assertEquals(1,runtime.remainingSeconds()); assertFalse(runtime.expired()); clock.incrementAndGet(); assertEquals(0,runtime.remainingSeconds()); assertTrue(runtime.expired());
        assertNull(runtime.puzzle(id,0,6000)); assertEquals(0,runtime.result().players().get(id).points());
    }
    private DronesRuntime runtime(List<UUID> ids,AtomicLong clock) { return new DronesRuntime(ids,new DronesConfig(DronesFixtures.definition()),clock::get,new Random(7)); }
}
