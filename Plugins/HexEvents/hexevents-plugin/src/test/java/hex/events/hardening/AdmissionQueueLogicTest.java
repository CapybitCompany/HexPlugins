package hex.events.hardening;

import hex.events.registration.AdmissionEntry;
import hex.events.registration.AdmissionQueue;
import hex.events.registration.AdmissionStatus;
import hex.events.registration.EventQueuePriority;

import java.util.List;
import java.util.UUID;

public final class AdmissionQueueLogicTest {
    public static void main(String[] args) {
        long base = 1_000L;
        UUID vip = id(1);
        UUID elitaEarly = id(2);
        UUID normal = id(3);
        UUID mediaLate = id(4);
        UUID elitaLate = id(5);

        AdmissionQueue queue = new AdmissionQueue();
        queue.add(entry(vip, base, EventQueuePriority.VIP));
        queue.add(entry(elitaEarly, base + 100, EventQueuePriority.ELITA));
        queue.add(entry(normal, base - 500, EventQueuePriority.NORMAL));
        queue.add(entry(mediaLate, base + 1_000, EventQueuePriority.MEDIA));
        queue.add(entry(elitaLate, base + 200, EventQueuePriority.ELITA));

        List<UUID> order = queue.ordered().stream().map(AdmissionEntry::playerId).toList();
        check(order.equals(List.of(mediaLate, elitaEarly, elitaLate, vip, normal)), "priority/FIFO order: " + order);
        check(queue.position(mediaLate) == 1, "MEDIA position");
        check(queue.position(elitaLate) == 3, "ELITA FIFO position");
        check(queue.position(normal) == 5, "NORMAL position");

        // Simulate an already admitted NORMAL. A later MEDIA may lead the remaining queue,
        // but must never put the admitted player back into it or displace that participant.
        queue.remove(normal);
        UUID admittedNormal = normal;
        UUID laterMedia = id(6);
        queue.add(entry(laterMedia, base + 2_000, EventQueuePriority.MEDIA));
        check(!queue.contains(admittedNormal), "admitted participant cannot be displaced/requeued by priority");
        check(queue.position(laterMedia) > 0, "later MEDIA may only compete for future free capacity");

        System.out.println("AdmissionQueueLogicTest OK");
    }

    private static AdmissionEntry entry(UUID id, long registeredAt, EventQueuePriority priority) {
        return new AdmissionEntry(id, id.toString(), registeredAt, priority, AdmissionStatus.QUEUED, "", registeredAt);
    }

    private static UUID id(long n) { return new UUID(0L, n); }
    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
