package hex.core.service.ui;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class UiConfig {
    private String prefix = "<gray>[</gray><gold><bold>HEX</bold></gold><gray>]</gray> ";
    private Map<String, String> templates = new HashMap<>();
    private Map<String, List<String>> templateArgs = new HashMap<>();

    // Admin overrides that win over defaults registered by plugins.
    private Map<String, String> overrides = new HashMap<>();

    // Optional per-namespace chat prefix (e.g. elimination, drawn, iceberg).
    private Map<String, String> prefixes = new HashMap<>();

    // YAML-defined UI presets; plugins can also register presets programmatically.
    private Map<String, UiPresetConfig> presets = new HashMap<>();

    public String getPrefix() { return prefix; }
    public void setPrefix(String prefix) { this.prefix = prefix == null ? "" : prefix; }

    public Map<String, String> getTemplates() { return templates; }
    public void setTemplates(Map<String, String> templates) { this.templates = templates == null ? new HashMap<>() : templates; }

    public Map<String, List<String>> getTemplateArgs() { return templateArgs; }
    public void setTemplateArgs(Map<String, List<String>> templateArgs) { this.templateArgs = templateArgs == null ? new HashMap<>() : templateArgs; }

    public Map<String, String> getOverrides() { return overrides; }
    public void setOverrides(Map<String, String> overrides) { this.overrides = overrides == null ? new HashMap<>() : overrides; }

    public Map<String, String> getPrefixes() { return prefixes; }
    public void setPrefixes(Map<String, String> prefixes) { this.prefixes = prefixes == null ? new HashMap<>() : prefixes; }

    public Map<String, UiPresetConfig> getPresets() { return presets; }
    public void setPresets(Map<String, UiPresetConfig> presets) { this.presets = presets == null ? new HashMap<>() : presets; }
}
