package hexcustomitems.model;

import java.util.Objects;

/** Spielt dem nutzenden Spieler einen Sound ab. */
public record SoundAction(
        String sound,
        float volume,
        float pitch,
        boolean offensive
) implements ItemAction {

    public SoundAction {
        sound = Objects.requireNonNull(sound, "sound");
    }
}
