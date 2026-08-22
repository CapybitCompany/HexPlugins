package hexnpc.workflow;

import hexnpc.workflow.action.*;
import hexnpc.workflow.model.*;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.util.*;
import java.util.logging.Logger;
import java.util.regex.Pattern;

/** Loads global workflows and reusable interactive menus from workflows.yml. */
public final class WorkflowRegistry {
    private final File file;
    private final Logger logger;
    private volatile Map<String, WorkflowDefinition> workflows = Map.of();
    private volatile Map<String, WorkflowMenu> menus = Map.of();
    private volatile List<String> errors = List.of();
    private volatile boolean debug;

    public WorkflowRegistry(File file, Logger logger) {
        this.file = Objects.requireNonNull(file, "file");
        this.logger = Objects.requireNonNull(logger, "logger");
    }

    public synchronized int reload() {
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        Map<String, WorkflowDefinition> nextWorkflows = new LinkedHashMap<>();
        Map<String, WorkflowMenu> nextMenus = new LinkedHashMap<>();
        List<String> nextErrors = new ArrayList<>();
        debug = yaml.getBoolean("debug.workflows", false);

        ConfigurationSection workflowRoot = yaml.getConfigurationSection("workflows");
        if (workflowRoot != null) {
            for (String rawId : workflowRoot.getKeys(false)) {
                String id = normalize(rawId);
                try {
                    ConfigurationSection section = workflowRoot.getConfigurationSection(rawId);
                    if (section == null) throw new IllegalArgumentException("not a section");
                    List<WorkflowAction> actions = parseActions(section.getList("actions"));
                    nextWorkflows.put(id, new WorkflowDefinition(id, actions));
                } catch (Exception ex) {
                    nextErrors.add("workflow '" + rawId + "': " + ex.getMessage());
                }
            }
        }

        ConfigurationSection menuRoot = yaml.getConfigurationSection("menus");
        if (menuRoot != null) {
            for (String rawId : menuRoot.getKeys(false)) {
                String id = normalize(rawId);
                try {
                    ConfigurationSection section = menuRoot.getConfigurationSection(rawId);
                    if (section == null) throw new IllegalArgumentException("not a section");
                    nextMenus.put(id, parseMenu(id, section));
                } catch (Exception ex) {
                    nextErrors.add("menu '" + rawId + "': " + ex.getMessage());
                }
            }
        }

        validateReferences(nextWorkflows, nextMenus, nextErrors);
        this.workflows = Map.copyOf(nextWorkflows);
        this.menus = Map.copyOf(nextMenus);
        this.errors = List.copyOf(nextErrors);
        for (String error : nextErrors) logger.warning("HexNPC: workflow validation: " + error);
        return nextWorkflows.size();
    }

    public Optional<WorkflowDefinition> workflow(String id) {
        return Optional.ofNullable(workflows.get(normalize(id)));
    }

    public Optional<WorkflowMenu> menu(String id) {
        return Optional.ofNullable(menus.get(normalize(id)));
    }

    public boolean hasWorkflow(String id) { return workflows.containsKey(normalize(id)); }
    public boolean hasMenu(String id) { return menus.containsKey(normalize(id)); }
    public List<String> workflowIds() { return workflows.keySet().stream().sorted().toList(); }
    public List<String> menuIds() { return menus.keySet().stream().sorted().toList(); }
    public List<String> errors() { return errors; }
    public boolean debug() { return debug; }

    private WorkflowMenu parseMenu(String id, ConfigurationSection section) {
        String title = section.getString("title", "&0Menu");
        int size = section.getInt("size", section.getInt("rows", 3) * 9);
        if (size <= 0 || size > 54 || size % 9 != 0) throw new IllegalArgumentException("invalid size " + size);

        WorkflowMenuBackground background = WorkflowMenuBackground.defaults();
        ConfigurationSection bg = section.getConfigurationSection("background");
        if (bg != null) {
            Material material = material(bg.getString("material", "BLACK_STAINED_GLASS_PANE"), "background material");
            background = new WorkflowMenuBackground(material, bg.getBoolean("hide-tooltip", true));
        }

        Map<String, WorkflowMenuItem> items = new LinkedHashMap<>();
        Set<Integer> slots = new HashSet<>();
        ConfigurationSection itemsSection = section.getConfigurationSection("items");
        if (itemsSection != null) {
            for (String itemId : itemsSection.getKeys(false)) {
                ConfigurationSection item = itemsSection.getConfigurationSection(itemId);
                if (item == null) continue;
                int slot = item.getInt("slot", -1);
                if (slot < 0 || slot >= size) throw new IllegalArgumentException("item '" + itemId + "' invalid slot " + slot);
                if (!slots.add(slot)) throw new IllegalArgumentException("duplicate slot " + slot);
                Material material = material(item.getString("material", "STONE"), "item '" + itemId + "' material");
                Integer cmd = item.contains("custom-model-data") ? Math.max(0, item.getInt("custom-model-data")) : null;
                String name = item.getString("name", item.getString("display-name", ""));
                List<String> lore = item.getStringList("lore");
                Map<String, List<WorkflowAction>> clickActions = new LinkedHashMap<>();
                ConfigurationSection actions = item.getConfigurationSection("actions");
                if (actions != null) {
                    for (String click : actions.getKeys(false)) {
                        clickActions.put(normalizeClick(click), parseActions(actions.getList(click)));
                    }
                }
                items.put(itemId, new WorkflowMenuItem(itemId, slot, material, cmd, name, lore, clickActions));
            }
        }
        return new WorkflowMenu(id, title, size, background, items);
    }

    @SuppressWarnings("unchecked")
    private List<WorkflowAction> parseActions(List<?> rawActions) {
        if (rawActions == null) return List.of();
        List<WorkflowAction> actions = new ArrayList<>();
        for (Object raw : rawActions) {
            if (!(raw instanceof Map<?, ?> map)) throw new IllegalArgumentException("action must be a map");
            actions.add(parseAction((Map<String, Object>) normalizeMap(map)));
        }
        return List.copyOf(actions);
    }

    private WorkflowAction parseAction(Map<String, Object> map) {
        String type = normalize(str(map.get("type")));
        if (type.isEmpty()) throw new IllegalArgumentException("action missing type");
        return switch (type) {
            case "message" -> new MessageAction(str(map.get("text")));
            case "open_menu" -> new OpenMenuAction(str(map.get("menu")));
            case "open_shop" -> new OpenShopAction(str(map.get("shop")));
            case "close_menu" -> new CloseMenuAction();
            case "run_workflow" -> new RunWorkflowAction(str(map.get("workflow")));
            case "set_player_data" -> new SetPlayerDataAction(str(map.get("key")), str(map.get("value")));
            case "delete_player_data" -> new DeletePlayerDataAction(str(map.get("key")));
            case "command" -> parseCommand(map);
            case "anvil_input" -> parseAnvil(map);
            case "condition" -> parseCondition(map);
            default -> throw new IllegalArgumentException("unknown action type '" + type + "'");
        };
    }

    private CommandAction parseCommand(Map<String, Object> map) {
        String rawExecutor = str(map.get("executor"));
        CommandAction.Executor executor;
        try {
            executor = rawExecutor.isBlank() ? CommandAction.Executor.CONSOLE
                    : CommandAction.Executor.valueOf(rawExecutor.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("invalid command executor '" + rawExecutor + "'");
        }
        return new CommandAction(executor, str(map.get("command")), bool(map.get("allow-input-variables"), false));
    }

    @SuppressWarnings("unchecked")
    private AnvilInputAction parseAnvil(Map<String, Object> map) {
        String id = str(map.get("id"));
        String title = strDefault(map.get("title"), "&0Wpisz etykietę");
        Map<String, Object> item = map.get("item") instanceof Map<?, ?> m ? (Map<String, Object>) normalizeMap(m) : Map.of();
        Material material = material(strDefault(item.get("material"), "NAME_TAG"), "anvil_input item material");
        String itemName = strDefault(item.get("name"), "&eWpisz tekst");
        String initial = str(map.get("initial-text"));

        Map<String, Object> validation = map.get("validation") instanceof Map<?, ?> m
                ? (Map<String, Object>) normalizeMap(m) : Map.of();
        String allowed = str(validation.get("allowed-pattern"));
        if (!allowed.isBlank()) Pattern.compile(allowed);
        List<String> deny = stringList(validation.get("deny-patterns"));
        for (String pattern : deny) Pattern.compile(pattern);
        AnvilInputAction.Validation rules = new AnvilInputAction.Validation(
                bool(validation.get("required"), true),
                integer(validation.get("min-length"), 1),
                integer(validation.get("max-length"), 24),
                bool(validation.get("trim"), true),
                allowed,
                deny,
                bool(validation.get("allow-colors"), false),
                bool(validation.get("allow-minimessage"), false)
        );

        Map<String, Object> messages = map.get("messages") instanceof Map<?, ?> m
                ? (Map<String, Object>) normalizeMap(m) : Map.of();
        AnvilInputAction.Messages msg = new AnvilInputAction.Messages(
                nullableStr(messages.get("required")),
                nullableStr(messages.get("too-short")),
                nullableStr(messages.get("too-long")),
                nullableStr(messages.get("invalid"))
        );
        List<WorkflowAction> onCancel = parseActions(asList(map.get("on-cancel")));
        return new AnvilInputAction(id, title, material, itemName, initial, rules, msg, onCancel);
    }

    @SuppressWarnings("unchecked")
    private ConditionAction parseCondition(Map<String, Object> map) {
        List<ConditionDefinition> conditions = new ArrayList<>();
        for (Object raw : asList(map.get("conditions"))) {
            if (!(raw instanceof Map<?, ?> conditionMap)) throw new IllegalArgumentException("condition entry must be a map");
            Map<String, Object> c = (Map<String, Object>) normalizeMap(conditionMap);
            conditions.add(new ConditionDefinition(
                    str(c.get("type")), str(c.get("key")), str(c.get("value")), str(c.get("expected")),
                    str(c.get("placeholder")), strDefault(c.get("operator"), "==")
            ));
        }
        List<WorkflowAction> thenActions = parseActions(asList(map.get("then")));
        List<WorkflowAction> elseActions = parseActions(asList(map.get("else")));
        return new ConditionAction(conditions, thenActions, elseActions);
    }

    private void validateReferences(Map<String, WorkflowDefinition> workflowMap,
                                    Map<String, WorkflowMenu> menuMap,
                                    List<String> out) {
        for (WorkflowDefinition workflow : workflowMap.values()) {
            validateActions("workflow '" + workflow.id() + "'", workflow.actions(), workflowMap, menuMap, out);
        }
        for (WorkflowMenu menu : menuMap.values()) {
            for (WorkflowMenuItem item : menu.items().values()) {
                for (List<WorkflowAction> actions : item.actions().values()) {
                    validateActions("menu '" + menu.id() + "' item '" + item.id() + "'", actions, workflowMap, menuMap, out);
                }
            }
        }
    }

    private void validateActions(String owner, List<WorkflowAction> actions,
                                 Map<String, WorkflowDefinition> workflowMap,
                                 Map<String, WorkflowMenu> menuMap,
                                 List<String> out) {
        for (WorkflowAction action : actions) {
            if (action instanceof RunWorkflowAction run && !workflowMap.containsKey(normalize(run.workflow()))) {
                out.add(owner + " references unknown workflow '" + run.workflow() + "'");
            } else if (action instanceof OpenMenuAction open && !menuMap.containsKey(normalize(open.menu()))) {
                out.add(owner + " references unknown menu '" + open.menu() + "'");
            } else if (action instanceof ConditionAction condition) {
                validateActions(owner, condition.thenActions(), workflowMap, menuMap, out);
                validateActions(owner, condition.elseActions(), workflowMap, menuMap, out);
            } else if (action instanceof AnvilInputAction anvil) {
                validateActions(owner, anvil.onCancel(), workflowMap, menuMap, out);
            }
        }
    }

    private Material material(String raw, String label) {
        Material material = Material.matchMaterial(raw == null ? "" : raw);
        if (material == null || material.isAir() || !material.isItem()) throw new IllegalArgumentException(label + " unknown material '" + raw + "'");
        return material;
    }

    private static Map<String, Object> normalizeMap(Map<?, ?> map) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : map.entrySet()) out.put(String.valueOf(entry.getKey()), entry.getValue());
        return out;
    }

    private static List<?> asList(Object raw) {
        return raw instanceof List<?> list ? list : List.of();
    }

    private static List<String> stringList(Object raw) {
        if (!(raw instanceof List<?> list)) return List.of();
        List<String> out = new ArrayList<>();
        for (Object item : list) out.add(String.valueOf(item));
        return out;
    }

    private static boolean bool(Object raw, boolean fallback) {
        if (raw instanceof Boolean value) return value;
        if (raw == null) return fallback;
        return Boolean.parseBoolean(String.valueOf(raw));
    }

    private static int integer(Object raw, int fallback) {
        if (raw instanceof Number n) return n.intValue();
        if (raw == null) return fallback;
        try { return Integer.parseInt(String.valueOf(raw)); } catch (NumberFormatException ignored) { return fallback; }
    }

    private static String str(Object raw) { return raw == null ? "" : String.valueOf(raw); }
    private static String nullableStr(Object raw) { return raw == null ? null : String.valueOf(raw); }
    private static String strDefault(Object raw, String fallback) { String value = str(raw); return value.isBlank() ? fallback : value; }
    private static String normalize(String raw) { return raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT).replace('-', '_'); }
    private static String normalizeClick(String raw) { return normalize(raw); }
}
