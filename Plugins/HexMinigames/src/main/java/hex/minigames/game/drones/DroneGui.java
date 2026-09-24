package hex.minigames.game.drones;

import hex.minigames.util.Text;
import org.bukkit.*;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.*;
import org.bukkit.inventory.*;
import java.util.*;
import java.util.function.Consumer;
import static hex.minigames.game.drones.DronesPuzzles.*;
import hex.minigames.game.drones.DronesPuzzles.Color;

/** An owner-bound vanilla inventory. All changes are executed by the round tick after the inventory event. */
public final class DroneGui implements InventoryHolder {
    private static final int[] CORE_SLOTS={11,12,13,14,15,29,30,31,32,33};
    private static final List<Color> CABLE_SOURCES=List.of(Color.GREEN,Color.RED,Color.BLUE,Color.YELLOW);
    private final Player owner;
    private final Inventory inventory;
    private final Puzzle puzzle;
    private final int checkpoint;
    private final Consumer<Runnable> deferred;
    private final DronesConfig config;
    private Color cursor;
    private boolean closed;
    private long lastPenalty=-1;
    private String lastFrame;
    public DroneGui(Player owner,Puzzle puzzle,int checkpoint,DronesConfig config,Consumer<Runnable> deferred) {
        this.owner=owner; this.puzzle=puzzle; this.checkpoint=checkpoint; this.config=config; this.deferred=deferred;
        String title=puzzle instanceof Cores cores?cores.order().stream().map(DroneGui::shortName).reduce((a,b)->a+" &7> "+b).orElse(""):
                puzzle instanceof Cables?"&dKABLE — przeciągnij od lewej do prawej":puzzle instanceof Calibration?"&dKALIBRACJA":puzzle instanceof Sequence?"&dSEKWENCJA":"&dGENERATOR — ZATRZYMAJ";
        inventory=Bukkit.createInventory(this,puzzle instanceof Generator||puzzle instanceof Sequence?27:54,Text.component(title));
    }
    @Override public Inventory getInventory() { return inventory; }
    public Puzzle puzzle() { return puzzle; }
    public int checkpoint() { return checkpoint; }
    public boolean open() { return !closed&&owner.getOpenInventory().getTopInventory()==inventory; }
    public void show(long tick) { if(puzzle instanceof Sequence sequence) sequence.reopen(tick); render(tick); owner.openInventory(inventory); }
    public void close() {
        if(closed) return; closed=true;
        if(puzzle instanceof Cores cores) cores.returnCursor(cursor);
        cursor=null; owner.setItemOnCursor(null);
        if(owner.getOpenInventory().getTopInventory()==inventory) owner.closeInventory();
    }
    public void click(InventoryClickEvent event,long tick) {
        event.setCancelled(true);
        if(!open()||event.getView().getTopInventory()!=inventory||!event.getWhoClicked().getUniqueId().equals(owner.getUniqueId())
                ||event.getClick()!=ClickType.LEFT||event.getRawSlot()<0||event.getRawSlot()>=inventory.getSize()) return;
        int slot=event.getRawSlot();
        deferred.accept(() -> {
            if(!open()||puzzle.done()||puzzle.locked(tick)) return;
            if(puzzle instanceof Cables cables) {
                int row=slot/9-1;
                if(slot%9==0&&row>=0&&row<4&&!cables.connected(CABLE_SOURCES.get(row))) {
                    cursor=CABLE_SOURCES.get(row);
                    ItemStack cable=item(Material.valueOf(cursor.name()+"_STAINED_GLASS_PANE"),shortName(cursor)+" KABEL");
                    cable.setAmount(64); owner.setItemOnCursor(cable);
                }
            } else if(puzzle instanceof Cores cores) {
                int index=coreIndex(slot);
                if(index>=0) { cursor=cores.exchange(index,cursor); setCoreCursor(); }
            } else if(puzzle instanceof Calibration calibration) {
                for(int i=0;i<3;i++) if(slot==48+i*2) calibration.increment(i,tick);
            } else if(puzzle instanceof Sequence sequence) sequence.click(slot,tick);
            else if(puzzle instanceof Generator generator && slot==22) generator.stop(tick);
            render(tick);
        });
    }
    public void drag(InventoryDragEvent event,long tick) {
        event.setCancelled(true);
        if(!open()||!event.getWhoClicked().getUniqueId().equals(owner.getUniqueId())||event.getView().getTopInventory()!=inventory||event.getRawSlots().isEmpty()
                ||event.getRawSlots().stream().anyMatch(slot -> slot<0||slot>=inventory.getSize())) return;
        Set<Integer> slots=Set.copyOf(event.getRawSlots());
        deferred.accept(() -> {
            if(!open()||puzzle.done()||puzzle.locked(tick)||cursor==null) return;
            if(puzzle instanceof Cables cables) {
                Set<Integer> endpoints=new HashSet<>();
                for(int slot:slots) {
                    int row=slot/9-1;
                    // All empty board slots accept a native drag; only the far-right column scores.
                    if(row>=0&&row<4&&slot%9==8) endpoints.add(row);
                }
                if(endpoints.isEmpty()) return;
                cables.drag(cursor,endpoints,tick); cursor=null; owner.setItemOnCursor(null);
            } else if(puzzle instanceof Cores cores) {
                if(slots.size()!=1) return;
                int index=coreIndex(slots.iterator().next());
                if(index<0||cores.at(index)!=null) return;
                cursor=cores.exchange(index,cursor); setCoreCursor();
            }
            render(tick);
        });
    }
    public void tick(long tick) {
        if(!open()) return;
        puzzle.tick(tick);
        if(puzzle.locked(tick)) {
            if(lastPenalty!=puzzle.lockedUntil()) {
                lastPenalty=puzzle.lockedUntil(); owner.playSound(owner.getLocation(),config.sound("error","minecraft:entity.villager.no"),1,.8f);
                cursor=null; owner.setItemOnCursor(null);
            }
        }
        render(tick);
    }
    private void setCoreCursor() { owner.setItemOnCursor(cursor==null?null:item(Material.valueOf(cursor.name()+"_CONCRETE"),shortName(cursor))); }
    private void render(long tick) {
        if(closed) return;
        String frame;
        if(puzzle instanceof Sequence sequence) frame="s"+sequence.green(tick);
        else if(puzzle instanceof Generator generator) frame="g"+generator.stage()+":"+generator.cursor(tick);
        else if(puzzle instanceof Calibration calibration) frame="a"+calibration.level(0)+":"+calibration.level(1)+":"+calibration.level(2);
        else if(puzzle instanceof Cores cores) {
            StringBuilder key=new StringBuilder("r"); for(int i=0;i<10;i++) key.append(cores.at(i)).append(','); frame=key.toString();
        } else {
            Cables cables=(Cables)puzzle; frame="c"+cursor;
            for(Color color:CABLE_SOURCES) frame+=cables.connected(color)?"1":"0";
        }
        if(frame.equals(lastFrame)) return;
        lastFrame=frame;
        Material background=puzzle instanceof Sequence?Material.RED_STAINED_GLASS_PANE:Material.BLACK_STAINED_GLASS_PANE;
        for(int i=0;i<inventory.getSize();i++) inventory.setItem(i,puzzle instanceof Cables?null:item(background," "));
        if(puzzle instanceof Cables cables) {
            for(int row=0;row<4;row++) {
                Color source=CABLE_SOURCES.get(row), target=cables.targets().get(row); int base=(row+1)*9;
                inventory.setItem(base,item(Material.valueOf(source.name()+"_STAINED_GLASS_PANE"),shortName(source)+(cables.connected(source)?" &a✔":" — weź kabel")));
                // Occupied incompatible stacks do not generate native drag slots. While carrying
                // a cable, move the color marker one slot left and expose its far-right socket.
                inventory.setItem(base+(cursor==null?8:7),item(Material.valueOf(target.name()+"_STAINED_GLASS_PANE"),shortName(target)+" — końcówka"));
                if(cables.connected(target)) inventory.setItem(base+8,item(Material.LIME_STAINED_GLASS_PANE,"&aPołączono"));
            }
        } else if(puzzle instanceof Cores cores) {
            for(int i=0;i<10;i++) inventory.setItem(CORE_SLOTS[i],cores.at(i)==null?null:item(Material.valueOf(cores.at(i).name()+"_CONCRETE"),shortName(cores.at(i))));
        } else if(puzzle instanceof Calibration calibration) {
            for(int column=0;column<4;column++) {
                int level=column==0?calibration.target():calibration.level(column-1);
                for(int row=0;row<5;row++) inventory.setItem(row*9+1+column*2,item(5-row<=level?Material.LIME_STAINED_GLASS_PANE:Material.GRAY_STAINED_GLASS_PANE,column==0?"&fWZÓR":"&f"+(char)('A'+column-1)));
                inventory.setItem(46+column*2,item(column==0?Material.PAPER:Material.STONE_BUTTON,column==0?"&fWZÓR":"&a+1"));
            }
        } else if(puzzle instanceof Sequence sequence) {
            int green=sequence.green(tick); if(green>=0) inventory.setItem(green,item(Material.GREEN_STAINED_GLASS_PANE,"&a●"));
        } else if(puzzle instanceof Generator generator) {
            for(int i=0;i<9;i++) {
                inventory.setItem(i,item(i>=generator.target()&&i<generator.target()+generator.width()?Material.LIME_STAINED_GLASS_PANE:Material.GRAY_STAINED_GLASS_PANE,"&fEtap "+Math.min(3,generator.stage()+1)+"/3"));
                inventory.setItem(i+9,i==generator.cursor(tick)?item(Material.NETHER_STAR,"&e▼"):item(Material.GRAY_STAINED_GLASS_PANE," "));
            }
            inventory.setItem(22,item(Material.STONE_BUTTON,"&aZATRZYMAJ"));
        }
    }
    private static int coreIndex(int slot) { for(int i=0;i<CORE_SLOTS.length;i++) if(CORE_SLOTS[i]==slot) return i; return -1; }
    public static ItemStack item(Material material,String name) {
        ItemStack item=new ItemStack(material); var meta=item.getItemMeta(); meta.displayName(Text.component(name)); meta.setHideTooltip(true); item.setItemMeta(meta); return item;
    }
    private static String shortName(Color color) { return switch(color) { case RED -> "&cCZERW"; case GREEN -> "&aZIEL"; case BLUE -> "&9NIEB"; case YELLOW -> "&eŻÓŁTY"; case WHITE -> "&fBIAŁY"; }; }
}
