package hex.minigames.game.drones;

import java.util.*;

/** Pure puzzle states; each instance survives inventory close/reopen. No scheduled tasks. */
public final class DronesPuzzles {
    private DronesPuzzles() { }
    public enum Color { RED, GREEN, BLUE, YELLOW, WHITE }
    public abstract static class Puzzle {
        protected long lockedUntil;
        protected boolean done;
        public boolean done() { return done; }
        public boolean locked(long tick) { return tick<lockedUntil; }
        public long lockedUntil() { return lockedUntil; }
        protected void penalty(long tick) { lockedUntil=tick+40; }
        public void tick(long tick) { }
    }
    public static final class Cables extends Puzzle {
        private final List<Color> targets;
        private final Set<Color> connected=new HashSet<>();
        public Cables(Random random) { targets=new ArrayList<>(List.of(Color.GREEN,Color.RED,Color.BLUE,Color.YELLOW)); Collections.shuffle(targets,random); }
        public List<Color> targets() { return List.copyOf(targets); }
        public boolean connected(Color color) { return connected.contains(color); }
        public boolean drag(Color source,Set<Integer> endpoints,long tick) {
            if(done||locked(tick)||connected(source)||source==Color.WHITE||endpoints.isEmpty()) return false;
            if(endpoints.stream().anyMatch(i -> i<0||i>=4||targets.get(i)!=source)) { penalty(tick); return false; }
            connected.add(source); done=connected.size()==4; return true;
        }
    }
    public static final class Cores extends Puzzle {
        private final List<Color> order, sources;
        private final Color[] slots=new Color[10];
        public Cores(Random random) {
            order=new ArrayList<>(List.of(Color.values())); sources=new ArrayList<>(order);
            Collections.shuffle(order,random); Collections.shuffle(sources,random);
            for(int i=0;i<5;i++) slots[5+i]=sources.get(i);
        }
        public List<Color> order() { return List.copyOf(order); }
        public List<Color> sources() { return List.copyOf(sources); }
        public Color at(int slot) { return slots[slot]; }
        public Color exchange(int slot,Color cursor) {
            if(done||slot<0||slot>=10) return cursor;
            Color previous=slots[slot]; slots[slot]=cursor;
            done=true; for(int i=0;i<5;i++) if(slots[i]!=order.get(i)) done=false;
            return previous;
        }
        public void returnCursor(Color cursor) { if(cursor!=null) for(int i=5;i<10;i++) if(slots[i]==null) { slots[i]=cursor; return; } }
    }
    public static final class Calibration extends Puzzle {
        private final int target;
        private final int[] original=new int[3], levels=new int[3];
        private boolean resetPending;
        public Calibration(Random random) {
            target=2+random.nextInt(4);
            for(int i=0;i<3;i++) original[i]=levels[i]=1+random.nextInt(target-1);
        }
        public int target() { return target; }
        public int level(int column) { return levels[column]; }
        public int original(int column) { return original[column]; }
        @Override public void tick(long tick) { if(resetPending&&!locked(tick)) { System.arraycopy(original,0,levels,0,3); resetPending=false; } }
        public boolean increment(int column,long tick) {
            tick(tick); if(done||locked(tick)||column<0||column>=3) return false;
            if(++levels[column]>target) { penalty(tick); resetPending=true; return false; }
            done=Arrays.stream(levels).allMatch(v -> v==target); return true;
        }
    }
    public static final class Sequence extends Puzzle {
        private final List<Integer> base;
        private final int show,gap;
        private int length=2,index,feedback=-1;
        private long showStart,feedbackUntil;
        public Sequence(Random random,int show,int gap,long tick) {
            List<Integer> slots=new ArrayList<>(); for(int i=0;i<27;i++) slots.add(i); Collections.shuffle(slots,random);
            base=List.copyOf(slots.subList(0,5)); this.show=show; this.gap=gap; showStart=tick;
        }
        public List<Integer> base() { return base; }
        public int length() { return length; }
        public boolean showing(long tick) { return !done&&tick<showStart+(long)length*(show+gap); }
        public int green(long tick) {
            if(locked(tick)||done) return -1;
            if(showing(tick)) { long elapsed=tick-showStart; if(elapsed<0) return -1; return elapsed%(show+gap)<show?base.get((int)(elapsed/(show+gap))):-1; }
            return tick<feedbackUntil?feedback:-1;
        }
        public void reopen(long tick) { if(!done) { index=0; feedback=-1; showStart=Math.max(tick,lockedUntil); } }
        public boolean click(int slot,long tick) {
            if(done||locked(tick)||showing(tick)) return false;
            if(slot!=base.get(index)) { penalty(tick); index=0; feedback=-1; showStart=lockedUntil; return false; }
            feedback=slot; feedbackUntil=tick+4;
            if(++index==length) {
                if(length==5) done=true;
                else { length++; index=0; showStart=tick+6; }
            }
            return true;
        }
    }
    public static final class Generator extends Puzzle {
        private final List<DronesConfig.Stage> stages;
        private final int[] targets=new int[3];
        private int stage;
        private long start;
        public Generator(Random random,List<DronesConfig.Stage> stages,long tick) {
            this.stages=List.copyOf(stages); start=tick;
            for(int i=0;i<3;i++) targets[i]=random.nextInt(10-stages.get(i).width());
        }
        public int stage() { return stage; }
        public int target() { return targets[Math.min(stage,2)]; }
        public int width() { return stages.get(Math.min(stage,2)).width(); }
        public int cursor(long tick) { long phase=Math.max(0,tick-start)/stages.get(Math.min(stage,2)).stepTicks()%16; return (int)(phase<=8?phase:16-phase); }
        public boolean stop(long tick) {
            if(done||locked(tick)) return false;
            int cursor=cursor(tick);
            if(cursor<target()||cursor>=target()+width()) { penalty(tick); start=lockedUntil; return false; }
            if(++stage==3) done=true;
            start=tick; return true;
        }
    }
}
