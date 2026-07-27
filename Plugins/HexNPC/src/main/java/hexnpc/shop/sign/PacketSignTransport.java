package hexnpc.shop.sign;

import hexnpc.render.packet.PacketEventsBootstrap;
import org.bukkit.Location;
import org.bukkit.entity.Player;

import java.util.function.BooleanSupplier;
import java.util.logging.Logger;

/**
 * Produkcyjny transport wirtualnej tabliczki oparty na PacketEvents. Ładuje
 * typy PacketEvents dopiero przy użyciu — instancjonować wyłącznie, gdy
 * PacketEvents jest dostępny.
 */
public final class PacketSignTransport implements SignTransport {

    private final Logger logger;
    private final BooleanSupplier debug;

    public PacketSignTransport(Logger logger, BooleanSupplier debug) {
        this.logger = logger;
        this.debug = debug == null ? () -> false : debug;
    }

    @Override
    public boolean isAvailable() {
        try {
            return PacketEventsBootstrap.isAvailable();
        } catch (Throwable t) {
            return false;
        }
    }

    @Override
    public OpenResult openEditor(Player player, int x, int y, int z) {
        return VirtualSign.openEditor(player, x, y, z, logger, debug.getAsBoolean());
    }

    @Override
    public void restore(Player player, int x, int y, int z) {
        if (player == null || !player.isOnline() || player.getWorld() == null) {
            return;
        }
        try {
            // Wysyła graczowi REALNY stan bloku (Bukkit) — usuwa ghost-tabliczkę.
            Location loc = new Location(player.getWorld(), x, y, z);
            player.sendBlockChange(loc, loc.getBlock().getBlockData());
        } catch (Throwable ignored) {
            // Rewert to tylko kosmetyka po stronie klienta.
        }
    }
}
