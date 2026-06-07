package hex.minions.config;

public record AppearanceDefinition(
        String id,
        boolean small,
        boolean invisible,
        boolean invulnerable,
        boolean noGravity,
        boolean arms,
        boolean marker,
        boolean equipmentLocked,
        ItemSpec helmet,
        ItemSpec chestplate,
        ItemSpec leggings,
        ItemSpec boots,
        ItemSpec mainHand,
        ItemSpec offHand,
        double labelOffsetY,
        String labelText
) {
    public static AppearanceDefinition fallback(String id) {
        return new AppearanceDefinition(
                id,
                true,
                false,
                true,
                true,
                true,
                false,
                true,
                ItemSpec.empty(),
                ItemSpec.empty(),
                ItemSpec.empty(),
                ItemSpec.empty(),
                ItemSpec.empty(),
                ItemSpec.empty(),
                1.65D,
                "<yellow><name></yellow> <gray>Tier <tier></gray>\n<gray>Storage: <white><storage_percent>%</white></gray>"
        );
    }
}

