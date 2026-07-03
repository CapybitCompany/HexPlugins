package hex.minions.menu;

import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.jetbrains.annotations.NotNull;

public final class MinionWikiHolder implements InventoryHolder {
    public static final String ELECTRONICS_RETURN_ID = "__electronics__";

    private final String typeId;
    private final String machineId;
    private final String machineRecipeId;
    private final int page;

    public MinionWikiHolder(String typeId) {
        this(typeId, "", "", 0);
    }

    public MinionWikiHolder(String typeId, int page) {
        this(typeId, "", "", page);
    }

    private MinionWikiHolder(String typeId, String machineId, String machineRecipeId) {
        this(typeId, machineId, machineRecipeId, 0);
    }

    private MinionWikiHolder(String typeId, String machineId, String machineRecipeId, int page) {
        this.typeId = typeId == null ? "" : typeId;
        this.machineId = machineId == null ? "" : machineId;
        this.machineRecipeId = machineRecipeId == null ? "" : machineRecipeId;
        this.page = Math.max(0, page);
    }

    public static MinionWikiHolder machineIndex(String returnTypeId) {
        return new MinionWikiHolder(returnTypeId, "__index__", "");
    }

    public static MinionWikiHolder machine(String returnTypeId, String machineId) {
        return new MinionWikiHolder(returnTypeId, machineId, "");
    }

    public static MinionWikiHolder machineRecipe(String returnTypeId, String machineId, String recipeId) {
        return new MinionWikiHolder(returnTypeId, machineId, recipeId);
    }

    public static MinionWikiHolder electronicsIndex() {
        return machineIndex(ELECTRONICS_RETURN_ID);
    }

    public static MinionWikiHolder electronicsMachine(String machineId) {
        return machine(ELECTRONICS_RETURN_ID, machineId);
    }

    public static MinionWikiHolder electronicsMachineRecipe(String machineId, String recipeId) {
        return machineRecipe(ELECTRONICS_RETURN_ID, machineId, recipeId);
    }

    public boolean index() {
        return typeId.isBlank() && machineId.isBlank() && machineRecipeId.isBlank();
    }

    public boolean minionType() {
        return !typeId.isBlank() && machineId.isBlank() && machineRecipeId.isBlank();
    }

    public boolean machineIndex() {
        return "__index__".equals(machineId) && machineRecipeId.isBlank();
    }

    public boolean machine() {
        return !machineId.isBlank() && !"__index__".equals(machineId) && machineRecipeId.isBlank();
    }

    public boolean machineRecipe() {
        return !machineId.isBlank() && !machineRecipeId.isBlank();
    }

    public boolean electronicsReturn() {
        return ELECTRONICS_RETURN_ID.equals(typeId);
    }

    public String typeId() {
        return typeId;
    }

    public String machineId() {
        return machineId;
    }

    public String machineRecipeId() {
        return machineRecipeId;
    }

    public int page() {
        return page;
    }

    @Override
    public @NotNull Inventory getInventory() {
        throw new UnsupportedOperationException("Holder only");
    }
}
