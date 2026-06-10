package hexnpc.service;

import hexnpc.action.NpcActionHandler;
import hexnpc.config.HexNpcConfig;
import hexnpc.model.InteractionTrigger;
import hexnpc.model.NpcAction;
import hexnpc.model.NpcDefinition;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

public final class NpcInteractionService {

    private final DialogueService dialogueService;
    private final NpcActionRegistry actionRegistry;
    private final Supplier<HexNpcConfig> configSupplier;
    private final Logger logger;

    public NpcInteractionService(DialogueService dialogueService,
                                 NpcActionRegistry actionRegistry,
                                 Supplier<HexNpcConfig> configSupplier,
                                 Logger logger) {
        this.dialogueService = Objects.requireNonNull(dialogueService, "dialogueService");
        this.actionRegistry = Objects.requireNonNull(actionRegistry, "actionRegistry");
        this.configSupplier = Objects.requireNonNull(configSupplier, "configSupplier");
        this.logger = Objects.requireNonNull(logger, "logger");
    }

    public void trigger(Player player, NpcDefinition npc, InteractionTrigger source) {
        if (player == null || npc == null) {
            return;
        }
        HexNpcConfig config = configSupplier.get();
        if (config != null && !config.enabled()) {
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
        // speak() runs afterAll exactly once: immediately when there are no
        // dialogue lines, otherwise after the last line ticks through.
        dialogueService.speak(player, npc, () -> runActions(player, npc, source));
    }

    private void runActions(Player player, NpcDefinition npc, InteractionTrigger source) {
        List<NpcAction> actions = npc.actions().forTrigger(source);
        for (NpcAction action : actions) {
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
