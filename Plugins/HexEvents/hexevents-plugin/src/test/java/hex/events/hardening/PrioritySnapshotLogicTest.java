package hex.events.hardening;

import hex.events.registration.AdmissionEntry;
import hex.events.registration.AdmissionQueue;
import hex.events.registration.AdmissionStatus;
import hex.events.registration.EventQueuePriority;

import java.util.List;
import java.util.UUID;

/** Verifies that an offline player keeps the priority captured at registration time. */
public final class PrioritySnapshotLogicTest {
    public static void main(String[] args) {
        UUID offlineElita = new UUID(0, 1);
        UUID onlineNormal = new UUID(0, 2);

        AdmissionEntry snapshottedOffline = new AdmissionEntry(
                offlineElita, "OfflineElita", 2_000L, EventQueuePriority.ELITA,
                AdmissionStatus.REGISTERED, "PRIORITY_SNAPSHOT_AT_REGISTRATION", 2_000L);
        AdmissionEntry normal = new AdmissionEntry(
                onlineNormal, "Normal", 1_000L, EventQueuePriority.NORMAL,
                AdmissionStatus.REGISTERED, "", 1_000L);

        List<AdmissionEntry> ordered = new java.util.ArrayList<>(List.of(normal, snapshottedOffline));
        ordered.sort(AdmissionQueue.ORDER);
        if (!ordered.getFirst().playerId().equals(offlineElita)) {
            throw new AssertionError("Offline ELITA lost snapshotted priority: " + ordered);
        }
        System.out.println("PrioritySnapshotLogicTest OK");
    }
}
