package hex.minigames.game.drones;

import hex.minigames.game.*;
import java.util.*;
import java.util.function.LongSupplier;

/** Monotonic finish times and once-only progress. Points are returned to the existing series flow. */
public final class DronesRuntime {
    public enum Phase { BOX, DRONE, GENERATOR, FINISHED, LEFT }
    public static final class Progress {
        private Phase phase=Phase.BOX;
        private final Set<Integer> completed=new HashSet<>();
        private final Map<Integer,DronesPuzzles.Puzzle> puzzles=new HashMap<>();
        private DronesPuzzles.Generator generator;
        private Long nanos;
        public Phase phase() { return phase; }
        public int count() { return completed.size(); }
        public boolean completed(int cp) { return completed.contains(cp); }
    }
    private final Map<UUID,Progress> players=new LinkedHashMap<>();
    private final DronesConfig config;
    private final LongSupplier clock;
    private final Random random;
    private long started;
    private boolean running;
    private RoundResult result;
    public DronesRuntime(Collection<UUID> ids,DronesConfig config,LongSupplier clock,Random random) {
        ids.forEach(id -> players.put(id,new Progress())); this.config=config; this.clock=clock; this.random=random;
    }
    public void start() { if(!running&&result==null) { started=clock.getAsLong(); running=true; } }
    public boolean expired() { return running&&clock.getAsLong()-started>=config.duration*1_000_000_000L; }
    public int remainingSeconds() {
        long remaining=config.duration*1_000_000_000L-(running?Math.max(0,clock.getAsLong()-started):0);
        return (int)Math.max(0,(remaining+999_999_999L)/1_000_000_000L);
    }
    public Progress player(UUID id) { return players.get(id); }
    public boolean launch(UUID id) { Progress p=players.get(id); if(!running||expired()||p==null||p.phase!=Phase.BOX) return false; p.phase=Phase.DRONE; return true; }
    public DronesPuzzles.Puzzle puzzle(UUID id,int cp,long tick) {
        Progress p=players.get(id);
        if(!running||expired()||p==null||p.phase!=Phase.DRONE||cp<0||cp>=4||p.completed(cp)) return null;
        return p.puzzles.computeIfAbsent(cp,key -> switch(config.checkpoints.get(key).puzzle()) {
            case "cables" -> new DronesPuzzles.Cables(random);
            case "cores" -> new DronesPuzzles.Cores(random);
            case "calibration" -> new DronesPuzzles.Calibration(random);
            case "sequence" -> new DronesPuzzles.Sequence(random,config.showTicks,config.gapTicks,tick);
            default -> throw new IllegalStateException("Unknown puzzle");
        });
    }
    public boolean complete(UUID id,int cp) {
        Progress p=players.get(id);
        if(!running||p==null||p.phase!=Phase.DRONE||expired()||!p.puzzles.containsKey(cp)||!p.puzzles.get(cp).done()||!p.completed.add(cp)) return false;
        if(p.completed.size()==4) p.phase=Phase.GENERATOR;
        return true;
    }
    public DronesPuzzles.Generator generator(UUID id,long tick) {
        Progress p=players.get(id); if(!running||expired()||p==null||p.phase!=Phase.GENERATOR) return null;
        if(p.generator==null) p.generator=new DronesPuzzles.Generator(random,config.stages,tick);
        return p.generator;
    }
    public boolean finish(UUID id) {
        Progress p=players.get(id); if(!running||p==null||p.phase!=Phase.GENERATOR||p.generator==null||!p.generator.done()||expired()) return false;
        p.nanos=Math.max(0,clock.getAsLong()-started); p.phase=Phase.FINISHED; return true;
    }
    public void leave(UUID id) { Progress p=players.get(id); if(p!=null) p.phase=Phase.LEFT; }
    public boolean allFinished() { return running&&players.values().stream().allMatch(p -> p.phase==Phase.FINISHED||p.phase==Phase.LEFT); }
    public long nanos(UUID id) { return players.get(id).nanos; }
    public RoundResult result() {
        if(result!=null) return result;
        running=false;
        List<Map.Entry<UUID,Progress>> ordered=new ArrayList<>(players.entrySet());
        ordered.removeIf(e -> e.getValue().phase==Phase.LEFT);
        ordered.sort(Comparator.comparingLong(e -> e.getValue().nanos==null?Long.MAX_VALUE:e.getValue().nanos));
        Map<UUID,PlayerRoundResult> out=new LinkedHashMap<>(); int place=0;
        for(var entry:ordered) {
            Progress p=entry.getValue(); boolean finished=p.nanos!=null;
            out.put(entry.getKey(),new PlayerRoundResult(finished?config.points(++place):0,finished?OptionalInt.of(place):OptionalInt.empty(),finished,!finished,
                    finished?Map.of("completion_time_ms",Long.toString(p.nanos/1_000_000),"completion_time_ns",Long.toString(p.nanos),"time_display",format(p.nanos)):Map.of("time_display","Niezaliczono")));
        }
        return result=new RoundResult(out,Map.of("game","drones"));
    }
    public static String format(long nanos) { long ms=nanos/1_000_000; return String.format(Locale.ROOT,"%02d:%02d.%03d",ms/60000,ms/1000%60,ms%1000); }
}
