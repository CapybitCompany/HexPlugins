package pl.hexnetwork.hexnametags.api;

import net.kyori.adventure.text.Component;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;

import java.util.List;

public interface HexNameTagsApi {
    void setPlayerTag(Player target, List<Component> lines);

    void setEntityTag(Entity target, List<Component> lines);

    void clearTag(Entity target);

    boolean hasTag(Entity target);
}
