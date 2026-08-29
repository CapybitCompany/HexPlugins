package hex.events.model;

import hex.events.api.EventAvailability;
import hex.events.api.EventInstanceView;
import hex.events.api.EventState;

import java.time.Instant;
import java.util.*;

public final class EventInstance {
    private final UUID id;
    private final EventDefinition definition;
    private final Instant occurrenceAt;
    private final Instant registrationOpenAt;
    private final Instant prepareAt;
    private final Instant lobbyAt;
    private final Instant startAt;
    private final Instant lateJoinCloseAt;
    private final Instant endAt;
    private final Set<UUID> registeredPlayers = new LinkedHashSet<>();
    private final Set<UUID> participants = new LinkedHashSet<>();
    private final Map<UUID, Long> registrationTimes = new HashMap<>();
    private final Map<UUID, String> registrationNames = new HashMap<>();
    private EventState state;
    private boolean prepared;
    private String lastError = "";

    public EventInstance(UUID id, EventDefinition definition, Instant occurrenceAt,
                         Instant registrationOpenAt, Instant prepareAt, Instant lobbyAt,
                         Instant startAt, Instant lateJoinCloseAt, Instant endAt, EventState state) {
        this.id = id;
        this.definition = definition;
        this.occurrenceAt = occurrenceAt;
        this.registrationOpenAt = registrationOpenAt;
        this.prepareAt = prepareAt;
        this.lobbyAt = lobbyAt;
        this.startAt = startAt;
        this.lateJoinCloseAt = lateJoinCloseAt;
        this.endAt = endAt;
        this.state = state == null ? EventState.SCHEDULED : state;
    }

    public UUID id() { return id; }
    public EventDefinition definition() { return definition; }
    public Instant occurrenceAt() { return occurrenceAt; }
    public Instant registrationOpenAt() { return registrationOpenAt; }
    public Instant prepareAt() { return prepareAt; }
    public Instant lobbyAt() { return lobbyAt; }
    public Instant startAt() { return startAt; }
    public Instant lateJoinCloseAt() { return lateJoinCloseAt; }
    public Instant endAt() { return endAt; }
    public EventState state() { return state; }
    public void state(EventState state) { this.state = state; }
    public boolean prepared() { return prepared; }
    public void prepared(boolean prepared) { this.prepared = prepared; }
    public String lastError() { return lastError; }
    public void lastError(String lastError) { this.lastError = lastError == null ? "" : lastError; }
    public Set<UUID> registeredPlayers() { return registeredPlayers; }
    public Set<UUID> participants() { return participants; }

    public void rememberRegistration(UUID playerId, String playerName, long registeredAt) {
        registeredPlayers.add(playerId);
        registrationTimes.put(playerId, registeredAt);
        registrationNames.put(playerId, playerName == null ? "" : playerName);
    }

    public void forgetRegistration(UUID playerId) {
        registeredPlayers.remove(playerId);
        registrationTimes.remove(playerId);
        registrationNames.remove(playerId);
    }

    public long registrationTime(UUID playerId) { return registrationTimes.getOrDefault(playerId, Long.MAX_VALUE); }
    public String registrationName(UUID playerId) { return registrationNames.getOrDefault(playerId, ""); }

    public EventInstanceView view(EventAvailability availability) {
        return new EventInstanceView(id, definition.id(), definition.displayName(), definition.moduleId(), state,
                availability, registrationOpenAt, lobbyAt, startAt, endAt,
                registeredPlayers.size(), participants.size(), definition.capacity().minPlayers(), definition.capacity().maxPlayers());
    }
}
