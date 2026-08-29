package hexcustomitems.model;

import java.util.Objects;

/** Spielt dem nutzenden Spieler einen Sound ab. */
public record SoundAction(
        String sound,
        float volume,
        float pitch,
        int delayTicks,
        boolean offensive
) implements ItemAction {

    public SoundAction(String sound, float volume, float pitch, boolean offensive) {
        this(sound, volume, pitch, 0, offensive);
    }

    public SoundAction {
        sound = Objects.requireNonNull(sound, "sound");
        delayTicks = Math.max(0, delayTicks);
    }
}
