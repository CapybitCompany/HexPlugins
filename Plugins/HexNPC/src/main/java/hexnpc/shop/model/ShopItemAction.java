package hexnpc.shop.model;

/** Opcjonalna generyczna akcja kafla / stanu produktu. */
public record ShopItemAction(ShopItemActionType type, String command, String workflow) {
    public ShopItemAction {
        type = type == null ? ShopItemActionType.DETAILS : type;
        command = command == null ? "" : command.trim();
        workflow = workflow == null ? "" : workflow.trim();
        if (type == ShopItemActionType.PLAYER_COMMAND && command.isEmpty()) {
            throw new IllegalArgumentException("PLAYER_COMMAND action requires command");
        }
        if (type == ShopItemActionType.RUN_WORKFLOW && workflow.isEmpty()) {
            throw new IllegalArgumentException("RUN_WORKFLOW action requires workflow");
        }
    }

    /** Backward-compatible constructor used by 1.3/1.4 code/tests. */
    public ShopItemAction(ShopItemActionType type, String command) {
        this(type, command, "");
    }

    public static ShopItemAction details() { return new ShopItemAction(ShopItemActionType.DETAILS, "", ""); }
    public static ShopItemAction none() { return new ShopItemAction(ShopItemActionType.NONE, "", ""); }
}
