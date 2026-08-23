package hex.restrictions.service;

public record RestrictionAudit(int removedItems, int removedEnchantments, int removedTrades) {
    public static final RestrictionAudit NONE = new RestrictionAudit(0, 0, 0);

    public RestrictionAudit plus(RestrictionAudit other) {
        if (other == null) return this;
        return new RestrictionAudit(
                removedItems + other.removedItems,
                removedEnchantments + other.removedEnchantments,
                removedTrades + other.removedTrades
        );
    }

    public int totalChanges() {
        return removedItems + removedEnchantments + removedTrades;
    }
}
