package hexposterunki.support;

import hexposterunki.towns.TownsAdapter;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** In-memory stand-in for HexTowns, which is not part of this build graph. */
public final class FakeTownsAdapter implements TownsAdapter {

    private final Map<UUID, UUID> playerTowns = new HashMap<>();
    private final Map<UUID, String> townNames = new HashMap<>();

    public void assign(UUID playerId, UUID townId, String townName) {
        playerTowns.put(playerId, townId);
        townNames.put(townId, townName);
    }

    public void removeTown(UUID playerId) {
        playerTowns.remove(playerId);
    }

    @Override
    public boolean available() {
        return true;
    }

    @Override
    public String status() {
        return "test";
    }

    @Override
    public Optional<UUID> townIdOf(UUID playerId) {
        return Optional.ofNullable(playerTowns.get(playerId));
    }

    @Override
    public Optional<String> townName(UUID townId) {
        return Optional.ofNullable(townNames.get(townId));
    }
}
