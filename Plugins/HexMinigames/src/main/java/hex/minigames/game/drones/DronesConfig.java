package hex.minigames.game.drones;

import hex.minigames.game.MinigameDefinition;
import hex.minigames.game.common.*;
import hex.minigames.model.*;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import java.util.*;
import static hex.minigames.game.common.GameSettings.*;

/** Validated, round-local configuration; a malformed file only disables Drones. */
public final class DronesConfig {
    public static final String ID = "drones";
    public record Station(BlockPosition spawn, boolean west, CuboidRegion gate) {
        public Location location(World world) { return new Location(world, spawn.x()+0.5, spawn.y(), spawn.z()+0.5, west ? 90 : -90, 0); }
        public Location launch(World world, double distance) { return location(world).add(west ? -distance : distance, 0, 0); }
    }
    public record Checkpoint(BlockPosition block, String puzzle) { }
    public record Stage(int stepTicks, int width) { }
    public record Physics(double acceleration, double verticalAcceleration, double maxSpeed, double maxVerticalSpeed,
                          double drag, double boostMultiplier, int boostDuration, int boostCooldown) { }
    public final Object settings;
    public final String world;
    public final CuboidRegion region;
    public final List<Station> stations;
    public final List<Checkpoint> checkpoints;
    public final List<BlockPosition> generators;
    public final List<Stage> stages;
    public final Physics physics;
    public final int duration, showTicks, gapTicks;
    public final double distance, scale, beamHeight, visualScale;
    public final Material controller;
    public final BossBarSettings bossbar;
    public final TutorialSettings tutorial;

    public DronesConfig(MinigameDefinition definition) {
        settings = definition.settings();
        if (child(settings, "validation-errors") != null) throw new IllegalArgumentException(String.valueOf(child(settings,"validation-errors")));
        world = string(settings,"world","Hex_Minigames");
        region = definition.region().orElseThrow(() -> new IllegalArgumentException("Brak regionu Dronów"));
        require(world.equals(region.worldName()), "world nie odpowiada regionowi");
        duration = definition.roundTimeSeconds(); require(duration > 0 && duration <= 3600, "duration 1..3600");
        List<String> errors = new ArrayList<>();
        bossbar = CommonGameConfig.bossBar(settings,definition.sourcePath(),"&d&lDRONY",errors);
        tutorial = CommonGameConfig.tutorial(settings,definition.sourcePath(),List.of("&d&lDRONY"),errors);
        require(errors.isEmpty(), String.join("; ",errors));
        require(tutorial.durationSeconds() >= 0 && tutorial.durationSeconds() <= 120, "tutorial 0..120");
        stations = new ArrayList<>();
        Object stationRoot = child(settings,"stations");
        require(size(stationRoot) == 14, "Wymagane dokładnie 14 stanowisk");
        Set<BlockPosition> gateBlocks = new HashSet<>();
        for (int i=1;i<=14;i++) {
            Object entry = child(stationRoot,String.valueOf(i));
            BlockPosition spawn = requiredBlock(child(entry,"spawn"));
            String facing = string(entry,"facing","");
            require(facing.equals("WEST") || facing.equals("EAST"), "facing musi być WEST/EAST");
            Object gateRaw=child(entry,"gate-region");
            CuboidRegion gate = new CuboidRegion(world,requiredBlock(child(gateRaw,"pos1")),requiredBlock(child(gateRaw,"pos2")));
            require(gate != null && region.contains(gate) && region.contains(spawn), "Stanowisko poza regionem");
            require((long)(gate.maxX()-gate.minX()+1)*(gate.maxY()-gate.minY()+1)*(gate.maxZ()-gate.minZ()+1) <= 64, "Za duża furtka");
            for (int x=gate.minX();x<=gate.maxX();x++) for (int y=gate.minY();y<=gate.maxY();y++) for(int z=gate.minZ();z<=gate.maxZ();z++)
                require(gateBlocks.add(new BlockPosition(x,y,z)), "Nakładające się furtki");
            stations.add(new Station(spawn,facing.equals("WEST"),gate));
        }
        checkpoints = new ArrayList<>();
        List<String> types = List.of("cables","cores","calibration","sequence");
        Object cps = child(settings,"checkpoints");
        require(size(cps)==4,"Wymagane 4 checkpointy");
        Set<BlockPosition> locations = new HashSet<>();
        for (int i=1;i<=4;i++) {
            Object cp = child(cps,"checkpoint-"+i);
            BlockPosition block = requiredBlock(child(cp,"location"));
            String type = string(cp,"puzzle","");
            require(types.get(i-1).equals(type), "Niepoprawny typ checkpointu " + i);
            require(region.contains(block) && locations.add(block), "Checkpoint poza regionem lub duplikat");
            checkpoints.add(new Checkpoint(block,type));
        }
        generators = new ArrayList<>();
        for (Object raw : list(child(settings,"generators.locations"))) generators.add(requiredBlock(raw));
        require(generators.size()==2 && new HashSet<>(generators).size()==2, "Wymagane dwa różne generatory");
        for (BlockPosition generator : generators) require(region.contains(generator) && locations.add(generator), "Błędny generator");
        stages = new ArrayList<>();
        for(int i=1;i<=3;i++) stages.add(new Stage(integerRange("generator-puzzle.stage"+i+".step-ticks",5-i,1,100),
                integerRange("generator-puzzle.stage"+i+".target-width",i==1?2:1,1,8)));
        distance = number("drone.spawn-distance",3,0.5,10);
        scale = number("drone.scale",0.35,0.1,1);
        beamHeight = number("beam.height",45,1,128);
        visualScale = number("drone.visual.scale",0.35,0.1,0.5);
        for (Station station:stations) {
            BlockPosition launch = new BlockPosition((int)Math.floor(station.spawn.x()+0.5+(station.west?-distance:distance)),station.spawn.y(),station.spawn.z());
            require(region.contains(launch),"Start drona poza regionem");
        }
        controller = Material.matchMaterial(string(settings,"drone.item.material","RECOVERY_COMPASS"));
        require(controller!=null && controller!=Material.AIR && controller!=Material.CAVE_AIR && controller!=Material.VOID_AIR,"Błędny drone.item.material");
        // ItemType is server-registry backed in Paper 1.21.11; pure config tests have no server.
        if(org.bukkit.Bukkit.getServer()!=null) require(controller.isItem(),"Materiał kontrolera nie jest przedmiotem");
        physics = new Physics(number("drone.acceleration",.045,.001,1),number("drone.vertical-acceleration",.04,.001,1),
                number("drone.max-speed",.55,.01,2),number("drone.max-vertical-speed",.35,.01,2),number("drone.drag",.88,0,.9999),
                number("drone.boost-multiplier",1.55,1,3),integerRange("drone.boost-duration",12,1,200),integerRange("drone.boost-cooldown",50,1,1200));
        require(physics.boostCooldown>=physics.boostDuration,"Boost cooldown krótszy od czasu boosta");
        showTicks=integerRange("puzzles.sequence.green-show-ticks",32,1,100);
        gapTicks=integerRange("puzzles.sequence.gap-ticks",5,1,100);
        for(String range:List.of("first","second-third","fourth-ninth","tenth-plus")) integerRange("scores."+range,1,0,100);
    }
    public int points(int place) { return integer(settings,"scores."+(place==1?"first":place<=3?"second-third":place<=9?"fourth-ninth":"tenth-plus"),place==1?4:place<=3?3:place<=9?2:1); }
    public String message(String key,String fallback) { return string(settings,"messages."+key,fallback); }
    public String sound(String key,String fallback) { return string(settings,"sounds."+key,fallback); }
    private double number(String path,double fallback,double min,double max) {
        Object raw=child(settings,path);
        double value=raw==null?fallback:doubleValue(raw,Double.NaN);
        require(Double.isFinite(value)&&value>=min&&value<=max,"Niepoprawne "+path); return value;
    }
    private int integerRange(String path,int fallback,int min,int max) {
        double value=number(path,fallback,min,max); require(value==Math.rint(value),"Wymagana liczba całkowita: "+path); return (int)value;
    }
    private static BlockPosition requiredBlock(Object raw) {
        for(String axis:List.of("x","y","z")) {
            double v=doubleValue(child(raw,axis),Double.NaN);
            require(Double.isFinite(v)&&v==Math.rint(v)&&Math.abs(v)<30000000,"Błędna pozycja "+axis);
        }
        return block(raw);
    }
    private static void require(boolean value,String message) { if(!value) throw new IllegalArgumentException("Drony: "+message); }
    private static int size(Object value) {
        if(value instanceof Map<?,?> map) return map.size();
        if(value instanceof org.bukkit.configuration.ConfigurationSection section) return section.getKeys(false).size();
        return -1;
    }
}
