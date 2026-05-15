package hexabovename.repository;

import java.util.Collection;
import java.util.Map;
import java.util.UUID;

public interface DisplayTextRepository extends AutoCloseable {

    void initialize() throws Exception;

    Map<UUID, String> loadDisplayTexts(Collection<PlayerSnapshot> players) throws Exception;

    @Override
    default void close() throws Exception {
    }
}
