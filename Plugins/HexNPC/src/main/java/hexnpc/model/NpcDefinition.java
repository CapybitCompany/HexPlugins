package hexnpc.model;

import java.util.Objects;

public record NpcDefinition(
        NpcId id,
        NpcSkin skin,
        NpcLocation location,
        InteractionSettings interaction,
        Dialogue dialogue,
        NpcActions actions,
        NpcAppearance appearance,
        LookAtSettings lookAt
) {
    public NpcDefinition {
        id = Objects.requireNonNull(id, "id");
        skin = skin == null ? NpcSkin.ofName(id.value()) : skin;
        location = Objects.requireNonNull(location, "location");
        interaction = interaction == null ? InteractionSettings.defaultClick() : interaction;
        dialogue = dialogue == null ? Dialogue.empty() : dialogue;
        actions = actions == null ? NpcActions.empty() : actions;
        appearance = appearance == null ? NpcAppearance.defaults() : appearance;
        lookAt = lookAt == null ? LookAtSettings.defaults() : lookAt;
    }

    /**
     * Rueckwaerts-kompatibler Konstruktor ohne {@link LookAtSettings} — bestehende
     * Aufrufer/Tests mit Appearance kompilieren unveraendert und erhalten Look-At-Defaults.
     */
    public NpcDefinition(NpcId id,
                         NpcSkin skin,
                         NpcLocation location,
                         InteractionSettings interaction,
                         Dialogue dialogue,
                         NpcActions actions,
                         NpcAppearance appearance) {
        this(id, skin, location, interaction, dialogue, actions, appearance, LookAtSettings.defaults());
    }

    /**
     * Rueckwaerts-kompatibler Konstruktor ohne {@link NpcAppearance}/{@link LookAtSettings} —
     * bestehende Aufrufer und alte Tests kompilieren unveraendert und erhalten Defaults.
     */
    public NpcDefinition(NpcId id,
                         NpcSkin skin,
                         NpcLocation location,
                         InteractionSettings interaction,
                         Dialogue dialogue,
                         NpcActions actions) {
        this(id, skin, location, interaction, dialogue, actions, NpcAppearance.defaults(), LookAtSettings.defaults());
    }

    public NpcDefinition withLocation(NpcLocation newLocation) {
        return new NpcDefinition(id, skin, newLocation, interaction, dialogue, actions, appearance, lookAt);
    }

    public NpcDefinition withSkin(NpcSkin newSkin) {
        return new NpcDefinition(id, newSkin, location, interaction, dialogue, actions, appearance, lookAt);
    }

    public NpcDefinition withInteraction(InteractionSettings newInteraction) {
        return new NpcDefinition(id, skin, location, newInteraction, dialogue, actions, appearance, lookAt);
    }

    public NpcDefinition withDialogue(Dialogue newDialogue) {
        return new NpcDefinition(id, skin, location, interaction, newDialogue, actions, appearance, lookAt);
    }

    public NpcDefinition withActions(NpcActions newActions) {
        return new NpcDefinition(id, skin, location, interaction, dialogue, newActions, appearance, lookAt);
    }

    public NpcDefinition withAppearance(NpcAppearance newAppearance) {
        return new NpcDefinition(id, skin, location, interaction, dialogue, actions, newAppearance, lookAt);
    }

    public NpcDefinition withLookAt(LookAtSettings newLookAt) {
        return new NpcDefinition(id, skin, location, interaction, dialogue, actions, appearance, newLookAt);
    }
}
