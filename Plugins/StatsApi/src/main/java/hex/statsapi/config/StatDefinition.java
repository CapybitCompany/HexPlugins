package hex.statsapi.config;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public class StatDefinition {

    @NotBlank
    @Pattern(regexp = "^[a-z0-9_\\-]+$")
    private String id;

    @NotBlank
    private String displayName;

    @NotBlank
    @Pattern(regexp = "^[a-zA-Z0-9_]+$")
    private String table;

    @NotBlank
    @Pattern(regexp = "^[a-zA-Z0-9_]+$")
    private String uuidColumn = "uuid";

    @NotBlank
    @Pattern(regexp = "^[a-zA-Z0-9_]+$")
    private String nicknameColumn = "player";

    @NotBlank
    @Pattern(regexp = "^[a-zA-Z0-9_]+$")
    private String valueColumn;

    @NotBlank
    @Pattern(regexp = "^(ASC|DESC)$")
    private String order = "DESC";

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getDisplayName() {
        return displayName;
    }

    public void setDisplayName(String displayName) {
        this.displayName = displayName;
    }

    public String getTable() {
        return table;
    }

    public void setTable(String table) {
        this.table = table;
    }

    public String getUuidColumn() {
        return uuidColumn;
    }

    public void setUuidColumn(String uuidColumn) {
        this.uuidColumn = uuidColumn;
    }

    public String getNicknameColumn() {
        return nicknameColumn;
    }

    public void setNicknameColumn(String nicknameColumn) {
        this.nicknameColumn = nicknameColumn;
    }

    public String getValueColumn() {
        return valueColumn;
    }

    public void setValueColumn(String valueColumn) {
        this.valueColumn = valueColumn;
    }

    public String getOrder() {
        return order;
    }

    public void setOrder(String order) {
        this.order = order;
    }
}

