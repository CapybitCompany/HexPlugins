package hex.core.service.ui;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class UiConfig {
    private String prefix = "<gray>[</gray><gold><bold>HEX</bold></gold><gray>]</gray> ";
    private Map<String, String> templates = new HashMap<>();
    private Map<String, List<String>> templateArgs = new HashMap<>();

    public String getPrefix() { return prefix; }
    public void setPrefix(String prefix) { this.prefix = prefix; }

    public Map<String, String> getTemplates() { return templates; }
    public void setTemplates(Map<String, String> templates) { this.templates = templates; }

    public Map<String, List<String>> getTemplateArgs() { return templateArgs; }
    public void setTemplateArgs(Map<String, List<String>> templateArgs) { this.templateArgs = templateArgs; }
}
