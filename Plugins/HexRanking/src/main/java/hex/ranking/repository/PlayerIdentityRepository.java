package hex.ranking.repository;

import java.util.Optional;
import java.util.UUID;

public interface PlayerIdentityRepository {

    Optional<UUID> findUuidByName(String playerName);
}
