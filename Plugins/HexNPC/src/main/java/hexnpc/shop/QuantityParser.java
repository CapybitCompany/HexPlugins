package hexnpc.shop;

/**
 * Waliduje i parsuje ilość podaną przez gracza (sign/czat). Czysta,
 * deterministyczna logika — łatwa do testów jednostkowych.
 *
 * <p>Odrzuca: puste wejście, wartości nie będące liczbą, liczby ujemne,
 * zero, liczby z częścią dziesiętną, przepełnienia oraz wartości powyżej
 * skonfigurowanego maksimum.
 */
public final class QuantityParser {

    public enum Error {
        NONE,
        EMPTY,
        NOT_A_NUMBER,
        TOO_SMALL,
        TOO_LARGE
    }

    public record Result(boolean ok, int value, Error error) {
        public static Result ok(int value) {
            return new Result(true, value, Error.NONE);
        }

        public static Result fail(Error error) {
            return new Result(false, 0, error);
        }
    }

    private QuantityParser() {
    }

    public static Result parse(String raw, int min, int max) {
        if (raw == null) {
            return Result.fail(Error.EMPTY);
        }
        String trimmed = raw.trim();
        if (trimmed.isEmpty()) {
            return Result.fail(Error.EMPTY);
        }
        // Tylko cyfry: odrzuca znak minus, kropkę/przecinek, spacje, litery.
        if (!trimmed.chars().allMatch(Character::isDigit)) {
            return Result.fail(Error.NOT_A_NUMBER);
        }
        long value;
        try {
            value = Long.parseLong(trimmed);
        } catch (NumberFormatException ex) {
            // Zbyt wiele cyfr, by zmieścić się w long — traktujemy jak za dużo.
            return Result.fail(Error.TOO_LARGE);
        }
        int safeMin = Math.max(1, min);
        if (value < safeMin) {
            return Result.fail(Error.TOO_SMALL);
        }
        if (value > max) {
            return Result.fail(Error.TOO_LARGE);
        }
        return Result.ok((int) value);
    }
}
