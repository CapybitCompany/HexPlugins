package hexnpc.service;

/**
 * Reine Rechenlogik fuer die Look-At-Rotation: leitet Yaw/Pitch aus der NPC-Augenposition
 * zur Ziel-(Spieler-)Augenposition ab. Bewusst frei von Bukkit/PacketEvents, damit die
 * Winkelberechnung isoliert testbar ist.
 *
 * <p>Konvention wie in Minecraft: Yaw 0 blickt nach +Z, waechst im Uhrzeigersinn; positiver
 * Pitch blickt nach unten.
 */
public final class LookAtCalculator {

    /** Augenhoehe eines stehenden NPCs ueber seinen Fuessen (Vanilla-Spieler ~1.62). */
    public static final double DEFAULT_EYE_HEIGHT = 1.62D;

    private LookAtCalculator() {
    }

    /**
     * @return {@code [yaw, pitch]} in Grad, damit die NPC-Augen ({@code npcEyeX/Y/Z}) auf
     *         die Zielaugen ({@code targetX/Y/Z}) zeigen. Bei zusammenfallenden Punkten
     *         (Abstand ~0) werden {@code 0/0} zurueckgegeben.
     */
    public static float[] yawPitch(double npcEyeX, double npcEyeY, double npcEyeZ,
                                   double targetX, double targetY, double targetZ) {
        double dx = targetX - npcEyeX;
        double dy = targetY - npcEyeY;
        double dz = targetZ - npcEyeZ;

        double horizontal = Math.sqrt(dx * dx + dz * dz);
        if (horizontal < 1.0e-6 && Math.abs(dy) < 1.0e-6) {
            return new float[]{0.0f, 0.0f};
        }

        float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        float pitch = (float) Math.toDegrees(-Math.atan2(dy, horizontal));
        return new float[]{normalizeYaw(yaw), clampPitch(pitch)};
    }

    private static float normalizeYaw(float yaw) {
        float y = yaw % 360.0f;
        if (y >= 180.0f) {
            y -= 360.0f;
        } else if (y < -180.0f) {
            y += 360.0f;
        }
        return y;
    }

    private static float clampPitch(float pitch) {
        if (pitch > 90.0f) {
            return 90.0f;
        }
        return Math.max(pitch, -90.0f);
    }
}
