package hexnpc.model;

import java.util.List;
import java.util.Objects;

public record NpcDefinition(
        NpcId id,
        NpcSkin skin,
        NpcLocation location,
        InteractionSettings interaction,
        Dialogue dialogue,
        List<NpcAction> actions
) {
    public NpcDefinition {
        id = Objects.requireNonNull(id, "id");
        skin = skin == null ? NpcSkin.ofName(id.value()) : skin;
        location = Objects.requireNonNull(location, "location");
        interaction = interaction == null ? InteractionSettings.defaultClick() : interaction;
        dialogue = dialogue == null ? Dialogue.empty() : dialogue;
        actions = actions == null ? List.of() : List.copyOf(actions);
    }

    public NpcDefinition withLocation(NpcLocation newLocation) {
        return new NpcDefinition(id, skin, newLocation, interaction, dialogue, actions);
    }

    public NpcDefinition withSkin(NpcSkin newSkin) {
        return new NpcDefinition(id, newSkin, location, interaction, dialogue, actions);
    }

    public NpcDefinition withInteraction(InteractionSettings newInteraction) {
        return new NpcDefinition(id, skin, location, newInteraction, dialogue, actions);
    }

    public NpcDefinition withDialogue(Dialogue newDialogue) {
        return new NpcDefinition(id, skin, location, interaction, newDialogue, actions);
    }

    public NpcDefinition withActions(List<NpcAction> newActions) {
        return new NpcDefinition(id, skin, location, interaction, dialogue, newActions);
    }
}
