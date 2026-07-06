package hexnpc.model;

import java.util.Objects;

public record NpcDefinition(
        NpcId id,
        NpcSkin skin,
        NpcLocation location,
        InteractionSettings interaction,
        Dialogue dialogue,
        NpcActions actions,
        NpcAppearance appearance
) {
    public NpcDefinition {
        id = Objects.requireNonNull(id, "id");
        skin = skin == null ? NpcSkin.ofName(id.value()) : skin;
        location = Objects.requireNonNull(location, "location");
        interaction = interaction == null ? InteractionSettings.defaultClick() : interaction;
        dialogue = dialogue == null ? Dialogue.empty() : dialogue;
        actions = actions == null ? NpcActions.empty() : actions;
        appearance = appearance == null ? NpcAppearance.defaults() : appearance;
    }

    /**
     * Rueckwaerts-kompatibler Konstruktor ohne {@link NpcAppearance} — bestehende
     * Aufrufer und alte Tests kompilieren unveraendert und erhalten Default-Appearance.
     */
    public NpcDefinition(NpcId id,
                         NpcSkin skin,
                         NpcLocation location,
                         InteractionSettings interaction,
                         Dialogue dialogue,
                         NpcActions actions) {
        this(id, skin, location, interaction, dialogue, actions, NpcAppearance.defaults());
    }

    public NpcDefinition withLocation(NpcLocation newLocation) {
        return new NpcDefinition(id, skin, newLocation, interaction, dialogue, actions, appearance);
    }

    public NpcDefinition withSkin(NpcSkin newSkin) {
        return new NpcDefinition(id, newSkin, location, interaction, dialogue, actions, appearance);
    }

    public NpcDefinition withInteraction(InteractionSettings newInteraction) {
        return new NpcDefinition(id, skin, location, newInteraction, dialogue, actions, appearance);
    }

    public NpcDefinition withDialogue(Dialogue newDialogue) {
        return new NpcDefinition(id, skin, location, interaction, newDialogue, actions, appearance);
    }

    public NpcDefinition withActions(NpcActions newActions) {
        return new NpcDefinition(id, skin, location, interaction, dialogue, newActions, appearance);
    }

    public NpcDefinition withAppearance(NpcAppearance newAppearance) {
        return new NpcDefinition(id, skin, location, interaction, dialogue, actions, newAppearance);
    }
}
