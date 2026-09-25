package hexposterunki.support;

import org.bukkit.plugin.Plugin;
import org.mockbukkit.mockbukkit.world.WorldMock;

import java.util.HashSet;
import java.util.Set;

/**
 * A MockBukkit world that implements plugin chunk tickets, which {@code WorldMock} leaves
 * unimplemented. Nothing else is changed. It lets tests observe that the production
 * {@code ChunkTicketService} really holds tickets during a run and releases them after the reset.
 */
public final class TicketTrackingWorld extends WorldMock {

    private final Set<Long> tickets = new HashSet<>();

    public TicketTrackingWorld(String name) {
        super();
        setName(name);
    }

    private static long key(int x, int z) {
        return ((long) x << 32) ^ (z & 0xffffffffL);
    }

    @Override
    public boolean addPluginChunkTicket(int x, int z, Plugin plugin) {
        return tickets.add(key(x, z));
    }

    @Override
    public boolean removePluginChunkTicket(int x, int z, Plugin plugin) {
        return tickets.remove(key(x, z));
    }

    @Override
    public void removePluginChunkTickets(Plugin plugin) {
        tickets.clear();
    }

    public int ticketCount() {
        return tickets.size();
    }
}
