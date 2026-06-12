package hex.minions.config;

public record ResourceDrop(String resourceId, int amountMin, int amountMax, double chance, boolean specialDrop, double specialDropPerTierBonus, String specialDropUpgradeItem, double specialDropUpgradeBonus) {
    public ResourceDrop {
        if (amountMin < 0 || amountMax < amountMin) throw new IllegalArgumentException("Invalid amount range");
        if (chance < 0.0 || chance > 1.0) throw new IllegalArgumentException("chance must be 0..1");
    }
}

