package pl.hexnetwork.hexnametags.packet;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.protocol.entity.type.EntityTypes;
import com.github.retrooper.packetevents.protocol.world.Location;
import com.github.retrooper.packetevents.util.Vector3d;
import com.github.retrooper.packetevents.wrapper.PacketWrapper;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerDestroyEntities;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityMetadata;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityTeleport;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSetPassengers;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSpawnEntity;
import io.github.retrooper.packetevents.util.SpigotConversionUtil;
import io.github.retrooper.packetevents.util.SpigotReflectionUtil;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import pl.hexnetwork.hexnametags.model.NameTagStyle;
import pl.hexnetwork.hexnametags.model.RenderedTag;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public final class PacketNameTagService {
    private final JavaPlugin plugin;
    private final TextDisplayMetadata_1_21_1 metadataAdapter = new TextDisplayMetadata_1_21_1();

    public PacketNameTagService(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    public RenderedTag spawn(Player viewer,
                             Entity target,
                             List<Component> lines,
                             NameTagStyle style,
                             boolean mountAsPassenger,
                             int teleportDurationTicks) {
        int entityId = SpigotReflectionUtil.generateEntityId();
        UUID fakeUuid = UUID.randomUUID();
        Location spawnLocation = SpigotConversionUtil.fromBukkitLocation(anchorLocation(target));

        WrapperPlayServerSpawnEntity spawn = new WrapperPlayServerSpawnEntity(
                entityId,
                fakeUuid,
                EntityTypes.TEXT_DISPLAY,
                spawnLocation,
                0.0F,
                0,
                null
        );

        send(viewer, spawn);
        updateText(viewer, entityId, lines, style, teleportDurationTicks);

        RenderedTag renderedTag = new RenderedTag(entityId, signature(lines, style), mountAsPassenger);
        rememberCurrentPosition(renderedTag, target);

        if (mountAsPassenger) {
            mount(viewer, target, entityId);
        }

        return renderedTag;
    }

    public void updateText(Player viewer,
                           int fakeEntityId,
                           List<Component> lines,
                           NameTagStyle style,
                           int teleportDurationTicks) {
        Component text = joinLines(lines);
        WrapperPlayServerEntityMetadata metadata = new WrapperPlayServerEntityMetadata(
                fakeEntityId,
                metadataAdapter.create(text, style, countDisplayLines(lines), teleportDurationTicks)
        );
        send(viewer, metadata);
    }

    public void follow(Player viewer,
                       Entity target,
                       RenderedTag renderedTag,
                       double minMoveDistanceSquared) {
        if (viewer == null || target == null || renderedTag == null || !target.isValid()) {
            return;
        }

        org.bukkit.Location anchor = anchorLocation(target);
        double x = anchor.getX();
        double y = anchor.getY();
        double z = anchor.getZ();

        if (renderedTag.hasLastPosition()) {
            double dx = x - renderedTag.lastX();
            double dy = y - renderedTag.lastY();
            double dz = z - renderedTag.lastZ();
            if ((dx * dx + dy * dy + dz * dz) < minMoveDistanceSquared) {
                return;
            }
        }

        WrapperPlayServerEntityTeleport teleport = new WrapperPlayServerEntityTeleport(
                renderedTag.entityId(),
                new Vector3d(x, y, z),
                target.getLocation().getYaw(),
                target.getLocation().getPitch(),
                target.isOnGround()
        );
        send(viewer, teleport);
        renderedTag.lastPosition(x, y, z);
    }

    /**
     * Optional fallback mode only. In normal smooth-follow mode we do not use SetPassengers at all.
     */
    public void refreshMount(Player viewer, Entity target, int fakeEntityId) {
        if (viewer == null || target == null || !target.isValid()) {
            return;
        }
        mount(viewer, target, fakeEntityId);
    }

    public void destroy(Player viewer, Entity target, RenderedTag renderedTag) {
        if (renderedTag == null) {
            return;
        }
        destroy(viewer, target, renderedTag.entityId(), renderedTag.mounted());
    }

    public void destroy(Player viewer, Entity target, int fakeEntityId, boolean mounted) {
        if (mounted && target != null && target.isValid()) {
            send(viewer, new WrapperPlayServerSetPassengers(target.getEntityId(), realPassengerIds(target)));
        }
        send(viewer, new WrapperPlayServerDestroyEntities(fakeEntityId));
    }

    public String signature(List<Component> lines, NameTagStyle style) {
        StringBuilder builder = new StringBuilder();
        PlainTextComponentSerializer plain = PlainTextComponentSerializer.plainText();
        for (Component line : lines) {
            builder.append(plain.serialize(line)).append('\n');
        }
        builder.append("|style=").append(style.hashCode());
        return builder.toString();
    }

    private void mount(Player viewer, Entity target, int fakeEntityId) {
        int[] passengers = appendFakePassenger(target, fakeEntityId);
        send(viewer, new WrapperPlayServerSetPassengers(target.getEntityId(), passengers));
    }

    private int[] appendFakePassenger(Entity target, int fakeEntityId) {
        List<Entity> realPassengers = target.getPassengers();
        List<Integer> passengerIds = new ArrayList<>(realPassengers.size() + 1);
        for (Entity passenger : realPassengers) {
            if (passenger != null && passenger.isValid() && passenger.getEntityId() != fakeEntityId) {
                passengerIds.add(passenger.getEntityId());
            }
        }
        passengerIds.add(fakeEntityId);

        int[] result = new int[passengerIds.size()];
        for (int i = 0; i < passengerIds.size(); i++) {
            result[i] = passengerIds.get(i);
        }
        return result;
    }

    private int[] realPassengerIds(Entity target) {
        List<Entity> realPassengers = target.getPassengers();
        int[] result = new int[realPassengers.size()];
        int index = 0;
        for (Entity passenger : realPassengers) {
            if (passenger != null && passenger.isValid()) {
                result[index++] = passenger.getEntityId();
            }
        }
        if (index == result.length) {
            return result;
        }
        int[] trimmed = new int[index];
        System.arraycopy(result, 0, trimmed, 0, index);
        return trimmed;
    }

    private Component joinLines(List<Component> lines) {
        if (lines == null || lines.isEmpty()) {
            return Component.empty();
        }

        Component result = Component.empty();
        for (int i = 0; i < lines.size(); i++) {
            if (i > 0) {
                result = result.append(Component.newline());
            }
            result = result.append(lines.get(i));
        }
        return result;
    }

    private int countDisplayLines(List<Component> lines) {
        if (lines == null || lines.isEmpty()) {
            return 1;
        }

        PlainTextComponentSerializer plain = PlainTextComponentSerializer.plainText();
        int count = 0;
        for (Component line : lines) {
            String serialized = plain.serialize(line);
            count += Math.max(1, serialized.split("\\R", -1).length);
        }
        return Math.max(1, count);
    }

    /**
     * The fake TextDisplay follows this anchor. Additional vertical spacing is still handled
     * by Display.translation metadata from NameTagStyle, so this method points to the top of
     * the target's hitbox/head area rather than the final text baseline.
     */
    private org.bukkit.Location anchorLocation(Entity target) {
        org.bukkit.Location location = target.getLocation().clone();
        location.setY(location.getY() + Math.max(0.0D, target.getHeight()));
        return location;
    }

    private void rememberCurrentPosition(RenderedTag renderedTag, Entity target) {
        org.bukkit.Location anchor = anchorLocation(target);
        renderedTag.lastPosition(anchor.getX(), anchor.getY(), anchor.getZ());
    }

    private void send(Player viewer, PacketWrapper<?> packet) {
        if (viewer == null || !viewer.isOnline()) {
            return;
        }
        // Ensure packet sending remains on the main thread when called from external plugin APIs.
        if (!Bukkit.isPrimaryThread()) {
            Bukkit.getScheduler().runTask(plugin, () -> send(viewer, packet));
            return;
        }
        PacketEvents.getAPI().getPlayerManager().sendPacket(viewer, packet);
    }
}
