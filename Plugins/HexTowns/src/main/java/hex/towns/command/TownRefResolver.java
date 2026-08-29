package hex.towns.command;

import hex.towns.model.Town;
import hex.towns.service.TownsService;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Ambiguity-safe resolver for administrative town commands. */
public final class TownRefResolver {
    private final TownsService service;

    public TownRefResolver(TownsService service) {
        this.service = service;
    }

    public Resolution resolve(String token) {
        if (token == null || token.isBlank()) return Resolution.notFound(token);
        String raw = token.trim();
        if (raw.length() >= 2 && ((raw.startsWith("\"") && raw.endsWith("\"")) || (raw.startsWith("'") && raw.endsWith("'")))) {
            raw = raw.substring(1, raw.length() - 1).trim();
        }

        if (raw.startsWith("#")) {
            Long id = parseLong(raw.substring(1));
            if (id == null) return Resolution.notFound(raw);
            return service.findTownByInternalId(id)
                    .map(Resolution::found)
                    .orElse(Resolution.notFound(raw));
        }

        // Plain numeric IDs are accepted for operator convenience. A numeric town name is only
        // considered if no active town exists under that internal ID.
        Long numeric = parseLong(raw);
        if (numeric != null) {
            Optional<Town> byInternal = service.findTownByInternalId(numeric);
            if (byInternal.isPresent()) return Resolution.found(byInternal.get());
        }

        try {
            UUID uuid = UUID.fromString(raw);
            Optional<Town> byUuid = service.findTown(uuid);
            if (byUuid.isPresent()) return Resolution.found(byUuid.get());
        } catch (IllegalArgumentException ignored) {
            // Not a UUID; continue with name lookup.
        }

        List<Town> exact = service.findActiveTownsByExactName(raw);
        if (exact.isEmpty()) return Resolution.notFound(raw);
        if (exact.size() == 1) return Resolution.found(exact.get(0));
        return Resolution.ambiguous(raw, exact);
    }

    public List<Town> search(String query, int limit) {
        return service.searchActiveTowns(query, limit);
    }

    private Long parseLong(String raw) {
        try {
            long value = Long.parseLong(raw);
            return value > 0L ? value : null;
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    public enum Status { FOUND, NOT_FOUND, AMBIGUOUS }

    public record Resolution(Status status, String token, Town town, List<Town> candidates) {
        public static Resolution found(Town town) {
            return new Resolution(Status.FOUND, town == null ? "" : town.name(), town, town == null ? List.of() : List.of(town));
        }

        public static Resolution notFound(String token) {
            return new Resolution(Status.NOT_FOUND, token == null ? "" : token, null, List.of());
        }

        public static Resolution ambiguous(String token, List<Town> candidates) {
            return new Resolution(Status.AMBIGUOUS, token == null ? "" : token, null, List.copyOf(candidates));
        }

        public boolean found() { return status == Status.FOUND && town != null; }
    }
}
