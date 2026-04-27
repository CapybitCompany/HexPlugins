package hex.core.service.flags;

import hex.core.api.config.ValidationResult;
import hex.core.api.config.Validator;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public final class FlagsValidator implements Validator<FlagsConfig> {

    @Override
    public ValidationResult validate(FlagsConfig config) {
        List<String> errors = new ArrayList<>();
        if (config == null) {
            errors.add("FlagsConfig is null");
            return ValidationResult.errors(errors);
        }

        // minimalne sanity: null mapy
        if (config.getGlobal() == null) errors.add("global is null");
        if (config.getGames() == null) errors.add("games is null");
        if (config.getArenas() == null) errors.add("arenas is null");

        // opcjonalnie: sprawdzaj, czy klucze flag mają kropki itp.
        validateKeys(config.getGlobal(), "global", errors);

        return errors.isEmpty() ? ValidationResult.ok() : ValidationResult.errors(errors);
    }

    private void validateKeys(Map<String, Boolean> m, String scope, List<String> errors) {
        if (m == null) return;
        for (String k : m.keySet()) {
            if (k == null || k.isBlank()) errors.add(scope + ": flag key is blank");
        }
    }
}
