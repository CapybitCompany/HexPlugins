package hexposterunki.persistence;

import hexposterunki.domain.KillEntry;
import hexposterunki.domain.RunSnapshot;

import java.util.List;
import java.util.Objects;

/**
 * One consistent bundle of everything that belongs together: the global pointer, the run, the
 * participant kill standings, the tracked entities and - after a victory - the frozen completion.
 *
 * <p>Captured on the main thread and written in a single database transaction, so a restart can
 * never observe a run whose participants or entity list come from a different moment in time, or a
 * completed run whose reward claims are missing. Its {@link RunSnapshot#revision()} orders all
 * writes and covers every part of the bundle.
 *
 * @param completion        frozen standings and claims of a won run that are not confirmed durable
 *                          yet; null otherwise
 * @param outpostDefinition the definition the run holds its location with, encoded by
 *                          {@link OutpostDefinitionCodec}; null when no location is held (cooldown).
 *                          A null never erases a definition already stored for the run.
 */
public record RunPersistenceSnapshot(
        RunSnapshot run,
        String lastOutpostId,
        List<KillEntry> participants,
        List<TrackedEntity> entities,
        CompletionRecord completion,
        String outpostDefinition
) {

    public RunPersistenceSnapshot {
        Objects.requireNonNull(run, "run");
        participants = participants == null ? List.of() : List.copyOf(participants);
        entities = entities == null ? List.of() : List.copyOf(entities);
    }

    /** Bundle without a completion and without a location definition to store. */
    public RunPersistenceSnapshot(RunSnapshot run, String lastOutpostId, List<KillEntry> participants,
                                  List<TrackedEntity> entities) {
        this(run, lastOutpostId, participants, entities, null, null);
    }

    /** Bundle without a location definition to store. */
    public RunPersistenceSnapshot(RunSnapshot run, String lastOutpostId, List<KillEntry> participants,
                                  List<TrackedEntity> entities, CompletionRecord completion) {
        this(run, lastOutpostId, participants, entities, completion, null);
    }

    public long revision() {
        return run.revision();
    }
}
