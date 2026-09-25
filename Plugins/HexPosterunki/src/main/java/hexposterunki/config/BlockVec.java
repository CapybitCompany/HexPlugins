package hexposterunki.config;

/** Bukkit-free integer block coordinate. */
public record BlockVec(int x, int y, int z) {

    public static BlockVec parse(String csv) {
        if (csv == null) {
            throw new IllegalArgumentException("Brak współrzędnych (oczekiwano \"x,y,z\")");
        }
        String[] parts = csv.split(",");
        if (parts.length != 3) {
            throw new IllegalArgumentException("Nieprawidłowe współrzędne: \"" + csv + "\" (oczekiwano \"x,y,z\")");
        }
        try {
            return new BlockVec(
                    (int) Math.floor(Double.parseDouble(parts[0].trim())),
                    (int) Math.floor(Double.parseDouble(parts[1].trim())),
                    (int) Math.floor(Double.parseDouble(parts[2].trim())));
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("Nieprawidłowe współrzędne: \"" + csv + "\"", exception);
        }
    }

    public String toCsv() {
        return x + "," + y + "," + z;
    }
}
