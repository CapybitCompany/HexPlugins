package hex.core.service.flags;

import java.util.HashMap;
import java.util.Map;

public final class FlagsConfig {

    // globalne flagi: "store.enabled" -> true/false
    private Map<String, Boolean> global = new HashMap<>();

    // per gra: gameId -> (flagKey -> bool)
    private Map<String, Map<String, Boolean>> games = new HashMap<>();

    // per arena: arenaId -> (flagKey -> bool)
    private Map<String, Map<String, Boolean>> arenas = new HashMap<>();

    public Map<String, Boolean> getGlobal() { return global; }
    public void setGlobal(Map<String, Boolean> global) { this.global = global; }

    public Map<String, Map<String, Boolean>> getGames() { return games; }
    public void setGames(Map<String, Map<String, Boolean>> games) { this.games = games; }

    public Map<String, Map<String, Boolean>> getArenas() { return arenas; }
    public void setArenas(Map<String, Map<String, Boolean>> arenas) { this.arenas = arenas; }
}
