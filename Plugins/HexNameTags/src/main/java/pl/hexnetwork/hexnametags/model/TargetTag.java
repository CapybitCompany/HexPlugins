package pl.hexnetwork.hexnametags.model;

import net.kyori.adventure.text.Component;
import org.bukkit.entity.Entity;

import java.util.List;
import java.util.UUID;

public final class TargetTag {
    private final UUID targetUuid;
    private final List<Component> lines;
    private final NameTagStyle style;

    public TargetTag(Entity target, List<Component> lines, NameTagStyle style) {
        this.targetUuid = target.getUniqueId();
        this.lines = List.copyOf(lines);
        this.style = style;
    }

    public UUID targetUuid() {
        return targetUuid;
    }

    public List<Component> lines() {
        return lines;
    }

    public NameTagStyle style() {
        return style;
    }
}
