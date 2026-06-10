package hexnpc.service;

import hexnpc.action.NpcActionHandler;
import hexnpc.model.InteractionTrigger;
import hexnpc.model.NpcAction;
import hexnpc.model.NpcDefinition;
import org.bukkit.entity.Player;

import java.util.Objects;
import java.util.logging.Level;
import java.util.logging.Logger;

public final class NpcInteractionService {

    private final DialogueService dialogueService;
    private final NpcActionRegistry actionRegistry;
    private final Logger logger;

    public NpcInteractionService(DialogueService dialogueService,
                                 NpcActionRegistry actionRegistry,
                                 Logger logger) {
        this.dialogueService = Objects.requireNonNull(dialogueService, "dialogueService");
        this.actionRegistry = Objects.requireNonNull(actionRegistry, "actionRegistry");
        this.logger = Objects.requireNonNull(logger, "logger");
    }

    public void trigger(Player player, NpcDefinition npc, InteractionTrigger source) {
        if (player == null || npc == null) {
            return;
        }
        if (!player.hasPermission("hexnpc.use")) {
            return;
        }
        boolean allowed = switch (source) {
            case CLICK -> npc.interaction().clickEnabled();
            case PROXIMITY -> npc.interaction().proximityEnabled();
        };
        if (!allowed) {
            return;
        }
        if (dialogueService.isOnCooldown(player, npc)) {
            return;
        }
        dialogueService.speak(player, npc, () -> runActions(player, npc));
        if (!npc.dialogue().hasLines()) {
            runActions(player, npc);
        }
    }

    private void runActions(Player player, NpcDefinition npc) {
        for (NpcAction action : npc.actions()) {
            NpcActionHandler handler = actionRegistry.resolve(action.type()).orElse(null);
            if (handler == null) {
                logger.warning("HexNPC: NPC '" + npc.id() + "' references unknown action type '" + action.type() + "'");
                continue;
            }
            try {
                handler.execute(player, npc, action);
            } catch (Exception ex) {
                logger.log(Level.WARNING,
                        "HexNPC: action '" + action.type() + "' on NPC '" + npc.id() + "' failed: " + ex.getMessage(),
                        ex);
            }
        }
    }
}
