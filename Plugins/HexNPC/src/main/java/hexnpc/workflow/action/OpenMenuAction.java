package hexnpc.workflow.action;

public record OpenMenuAction(String menu) implements WorkflowAction {
    public OpenMenuAction {
        menu = menu == null ? "" : menu.trim();
        if (menu.isEmpty()) throw new IllegalArgumentException("open_menu requires menu");
    }
    @Override public String type() { return "open_menu"; }
}
