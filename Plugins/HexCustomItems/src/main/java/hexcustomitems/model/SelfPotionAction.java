package hexcustomitems.model;

import java.util.Objects;

/** Legt einen Trank-Effekt auf den nutzenden Spieler. */
public record SelfPotionAction(
        PotionEffectSpec effect,
        boolean offensive
) implements ItemAction {

    public SelfPotionAction {
        effect = Objects.requireNonNull(effect, "effect");
    }
}
