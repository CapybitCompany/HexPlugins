package hexcasino.machine;

/** Audit record for one STOP resolved against a client container revision. */
public record ResolvedStop(
        int stopUnit,
        long frameSeq,
        int containerStateId,
        long frameStartNano,
        long packetReceiveNano,
        int resolvedPosition
) {
}
