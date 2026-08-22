package hex.minions.machine;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Locale;

/** Runtime-safe collection accounting for machine outputs. */
public final class MaceratorCollectionFix {
    private MaceratorCollectionFix() {}

    public static void record(Object service, Object runtime, Object recipe) {
        if (service == null || runtime == null || recipe == null) return;
        try {
            Object minions = field(service, "minions");
            Object collections = invoke(minions, "collections");
            if (collections == null) return;

            String machineId = string(invoke(runtime, "machineId"));
            String outputSpecial = string(invoke(recipe, "outputSpecialItem")).toLowerCase(Locale.ROOT);
            String outputMaterial = string(invoke(recipe, "outputMaterial"));
            String recipeId = string(invoke(recipe, "id")).toLowerCase(Locale.ROOT);
            int outputAmount = number(invoke(recipe, "outputAmount"));

            String collectionId;
            long amount;
            String reason;
            if ("macerator".equalsIgnoreCase(machineId) && isOreOrResourceBlock(recipe)) {
                if (outputSpecial.endsWith("_dust")) {
                    collectionId = dustCollection(outputSpecial);
                    amount = Math.max(0L, (long) outputAmount - 1L);
                    reason = "machine.macerator." + recipeId;
                } else if ("EMERALD_BLOCK".equals(string(invoke(recipe, "inputMaterial")))
                        && "EMERALD".equals(outputMaterial)) {
                    collectionId = "mining.emerald";
                    amount = Math.max(0L, (long) outputAmount - 1L);
                    reason = "machine.macerator." + recipeId;
                } else {
                    collectionId = "";
                    amount = 0L;
                    reason = "";
                }
            } else {
                collectionId = switch (outputSpecial) {
                    case "enriched_uranium" -> "industrial.enriched_uranium";
                    case "spruce_resin" -> "foraging.spruce_resin";
                    default -> "";
                };
                amount = Math.max(1, outputAmount);
                reason = "machine.output." + outputSpecial;
            }
            if (collectionId.isBlank() || amount <= 0L) return;

            String blockKey = string(invoke(runtime, "blockKey"));
            Object block = invoke(service, "blockFromKey", blockKey);
            if (block == null) return;
            Object location = invoke(block, "getLocation");
            if (location == null) return;
            Object towns = invoke(minions, "towns");
            Object optionalTown = invoke(towns, "townAt", location);
            if (optionalTown == null || !Boolean.TRUE.equals(invoke(optionalTown, "isPresent"))) return;
            Object town = invoke(optionalTown, "get");
            Object townId = invoke(town, "id");

            ClassLoader loader = service.getClass().getClassLoader();
            Class<?> ctxClass = Class.forName("hex.collections.api.CollectionProgressContext", true, loader);
            Object ctx = ctxClass.getConstructor().newInstance();
            invoke(ctx, "townId", townId);
            invoke(ctx, "collectionId", collectionId);
            invoke(ctx, "amount", Long.valueOf(amount));
            Class<?> sourceClass = Class.forName("hex.collections.api.CollectionSource", true, loader);
            @SuppressWarnings({"rawtypes", "unchecked"})
            Object source = Enum.valueOf((Class<? extends Enum>) sourceClass.asSubclass(Enum.class), "CUSTOM_PLUGIN_GRANTED");
            invoke(ctx, "source", source);
            invoke(ctx, "location", location);
            invoke(ctx, "reason", reason);
            invoke(collections, "addProgress", ctx);
        } catch (Throwable ignored) {
        }
    }

    private static String dustCollection(String id) {
        return switch (id) {
            case "iron_dust" -> "mining.iron";
            case "gold_dust" -> "mining.gold";
            case "copper_dust" -> "mining.copper";
            case "diamond_dust" -> "mining.diamond";
            case "tin_dust" -> "mining.tin";
            default -> "";
        };
    }

    private static boolean isOreOrResourceBlock(Object recipe) throws Exception {
        String material = string(invoke(recipe, "inputMaterial"));
        int cmd = number(invoke(recipe, "inputCustomModelData"));
        if ("RAW_IRON".equals(material) && cmd == 14001) return true; // Ruda cyny.
        return switch (material) {
            case "IRON_ORE", "DEEPSLATE_IRON_ORE",
                 "GOLD_ORE", "DEEPSLATE_GOLD_ORE",
                 "COPPER_ORE", "DEEPSLATE_COPPER_ORE",
                 "DIAMOND_ORE", "DEEPSLATE_DIAMOND_ORE",
                 "LAPIS_ORE", "DEEPSLATE_LAPIS_ORE", "LAPIS_BLOCK",
                 "EMERALD_BLOCK" -> true;
            default -> false;
        };
    }

    private static Object field(Object target, String name) throws Exception {
        Class<?> type = target.getClass();
        while (type != null) {
            try {
                Field f = type.getDeclaredField(name);
                f.setAccessible(true);
                return f.get(target);
            } catch (NoSuchFieldException e) {
                type = type.getSuperclass();
            }
        }
        return null;
    }

    private static Object invoke(Object target, String name, Object... args) throws Exception {
        if (target == null) return null;
        Method best = null;
        outer: for (Method m : target.getClass().getMethods()) {
            if (!m.getName().equals(name) || m.getParameterCount() != args.length) continue;
            Class<?>[] types = m.getParameterTypes();
            for (int i = 0; i < types.length; i++) {
                if (args[i] == null) continue;
                Class<?> wanted = wrap(types[i]);
                if (!wanted.isAssignableFrom(args[i].getClass())) continue outer;
            }
            best = m; break;
        }
        if (best == null) {
            Class<?> type = target.getClass();
            while (type != null && best == null) {
                outer: for (Method m : type.getDeclaredMethods()) {
                    if (!m.getName().equals(name) || m.getParameterCount() != args.length) continue;
                    Class<?>[] types = m.getParameterTypes();
                    for (int i = 0; i < types.length; i++) {
                        if (args[i] == null) continue;
                        if (!wrap(types[i]).isAssignableFrom(args[i].getClass())) continue outer;
                    }
                    best = m; break;
                }
                type = type.getSuperclass();
            }
        }
        if (best == null) return null;
        best.setAccessible(true);
        return best.invoke(target, args);
    }

    private static Class<?> wrap(Class<?> type) {
        if (!type.isPrimitive()) return type;
        if (type == long.class) return Long.class;
        if (type == int.class) return Integer.class;
        if (type == boolean.class) return Boolean.class;
        if (type == double.class) return Double.class;
        if (type == float.class) return Float.class;
        if (type == short.class) return Short.class;
        if (type == byte.class) return Byte.class;
        if (type == char.class) return Character.class;
        return type;
    }

    private static String string(Object value) { return value == null ? "" : value.toString(); }
    private static int number(Object value) { return value instanceof Number n ? n.intValue() : 0; }
}
