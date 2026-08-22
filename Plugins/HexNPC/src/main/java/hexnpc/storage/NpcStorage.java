package hexnpc.storage;

import hexnpc.model.NpcDefinition;
import hexnpc.model.NpcId;

import java.util.Collection;
import java.util.Optional;

public interface NpcStorage {

    void load() throws Exception;

    Collection<NpcDefinition> all();

    Optional<NpcDefinition> find(NpcId id);

    void save(NpcDefinition definition) throws Exception;

    boolean delete(NpcId id) throws Exception;
}
