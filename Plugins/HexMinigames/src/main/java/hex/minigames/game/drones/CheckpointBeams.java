package hex.minigames.game.drones;

import hex.minigames.model.BlockPosition;
import org.bukkit.*;
import org.bukkit.block.BlockState;
import org.bukkit.block.TileState;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import java.util.*;

/** Native client-side beacons: no shared map edits and no global checkpoint colors. */
public final class CheckpointBeams {
    private final Player owner;
    private final World world;
    private final Map<BlockPosition,BlockData> overlay=new LinkedHashMap<>();
    private final List<BlockPosition> colors=new ArrayList<>();
    private final BlockData beacon=Material.BEACON.createBlockData();
    private boolean removed;
    public CheckpointBeams(Plugin plugin,Player owner,DronesConfig config) {
        this.owner=owner; world=owner.getWorld();
        try {
            BlockData iron=Material.IRON_BLOCK.createBlockData(),air=Material.AIR.createBlockData();
            for(var cp:config.checkpoints) {
                var at=cp.block();
                for(int dx=-1;dx<=1;dx++) for(int dz=-1;dz<=1;dz++)
                    overlay.put(new BlockPosition(at.x()+dx,at.y()-1,at.z()+dz),iron);
                overlay.put(at,beacon);
                var color=new BlockPosition(at.x(),at.y()+1,at.z()); colors.add(color);
                overlay.put(color,Material.RED_STAINED_GLASS.createBlockData());
                // The vanilla client traces the entire column to sky, even for underground beacons.
                // Clear only the visual column; physical server blocks and checkpoint coordinates remain intact.
                for(int y=at.y()+2;y<world.getMaxHeight();y++) overlay.put(new BlockPosition(at.x(),y,at.z()),air);
            }
            refresh();
        } catch(RuntimeException error) { remove(); throw error; }
    }
    private Location location(BlockPosition at) { return new Location(world,at.x(),at.y(),at.z()); }
    public void refresh() {
        if(removed||!owner.isOnline()||!owner.getWorld().equals(world)) return;
        List<BlockState> changes=new ArrayList<>();
        overlay.forEach((at,data) -> {
            if(owner.isChunkSent(Chunk.getChunkKey(at.x()>>4,at.z()>>4))) changes.add(data.createBlockState().copy(location(at)));
        });
        owner.sendBlockChanges(changes);
        // Block changes create the native client block entity. Do not resend a zero-level tile
        // snapshot: that would reset the client's periodically computed pyramid level and flicker.
    }
    public void complete(int index) {
        BlockPosition at=colors.get(index);
        BlockData green=Material.GREEN_STAINED_GLASS.createBlockData(); overlay.put(at,green);
        owner.sendBlockChange(location(at),green);
    }
    public void remove() {
        if(removed) return; removed=true;
        if(owner.isOnline()&&owner.getWorld().equals(world)) {
            List<BlockState> actual=new ArrayList<>();
            for(var at:overlay.keySet()) if(owner.isChunkSent(Chunk.getChunkKey(at.x()>>4,at.z()>>4)))
                actual.add(world.getBlockAt(at.x(),at.y(),at.z()).getState());
            owner.sendBlockChanges(actual);
            for(BlockState state:actual) if(state instanceof TileState tile) owner.sendBlockUpdate(state.getLocation(),tile);
        }
        overlay.clear(); colors.clear();
    }
}
