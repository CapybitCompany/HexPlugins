package hexnpc.workflow.action;

public record OpenShopAction(String shop) implements WorkflowAction {
    public OpenShopAction {
        shop = shop == null ? "" : shop.trim();
        if (shop.isEmpty()) throw new IllegalArgumentException("open_shop requires shop");
    }
    @Override public String type() { return "open_shop"; }
}
