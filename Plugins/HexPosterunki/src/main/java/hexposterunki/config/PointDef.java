package hexposterunki.config;

/** Bukkit-free spawn point with optional facing, parsed from {@code "x,y,z"} or {@code "x,y,z,yaw,pitch"}. */
public record PointDef(double x, double y, double z, float yaw, float pitch) {

    public static PointDef parse(String csv) {
        if (csv == null) {
            throw new IllegalArgumentException("Brak punktu (oczekiwano \"x,y,z\" lub \"x,y,z,yaw,pitch\")");
        }
        String[] parts = csv.split(",");
        if (parts.length != 3 && parts.length != 5) {
            throw new IllegalArgumentException("Nieprawidłowy punkt: \"" + csv + "\" (oczekiwano \"x,y,z\" lub \"x,y,z,yaw,pitch\")");
        }
        try {
            double x = Double.parseDouble(parts[0].trim());
            double y = Double.parseDouble(parts[1].trim());
            double z = Double.parseDouble(parts[2].trim());
            float yaw = parts.length == 5 ? Float.parseFloat(parts[3].trim()) : 0.0F;
            float pitch = parts.length == 5 ? Float.parseFloat(parts[4].trim()) : 0.0F;
            return new PointDef(x, y, z, yaw, pitch);
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("Nieprawidłowy punkt: \"" + csv + "\"", exception);
        }
    }

    public int blockX() {
        return (int) Math.floor(x);
    }

    public int blockY() {
        return (int) Math.floor(y);
    }

    public int blockZ() {
        return (int) Math.floor(z);
    }
}
