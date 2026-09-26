package hexpvpsmp.protection;

import org.bukkit.Tag;
import org.bukkit.block.Block;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginManager;

import java.lang.reflect.Method;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

final class HexChestsCompatibility {

    private static final String PLUGIN_NAME = "HexChests";
    private static final Set<String> SILENT_CHEST_IDS = Set.of("afk", "epic", "premium");

    private HexChestsCompatibility() {
    }

    static boolean isHandledRewardShulker(PluginManager pluginManager, Block block) {
        if (!isShulker(block) || pluginManager == null) {
            return false;
        }
        Plugin hexChests = pluginManager.getPlugin(PLUGIN_NAME);
        if (hexChests == null || !hexChests.isEnabled()) {
            return false;
        }
        try {
            Object chestService = invokeNoArg(hexChests, "chestService");
            return isHandledRewardShulker(chestService, block);
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            return false;
        }
    }

    static boolean isHandledRewardShulker(Object chestService, Block block) {
        if (!isShulker(block) || chestService == null) {
            return false;
        }
        try {
            Object result = invoke(chestService, "chestAt", new Class<?>[]{Block.class}, block);
            if (!(result instanceof Optional<?> optional) || optional.isEmpty()) {
                return false;
            }
            return isSilentChestDefinition(optional.get());
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            return false;
        }
    }

    static boolean isSilentChestDefinition(Object chestDefinition) {
        if (chestDefinition == null) {
            return false;
        }
        try {
            Object id = invokeNoArg(chestDefinition, "id");
            return id instanceof String value && isSilentChestId(value);
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            return false;
        }
    }

    static boolean isSilentChestId(String id) {
        if (id == null) {
            return false;
        }
        return SILENT_CHEST_IDS.contains(id.trim().toLowerCase(Locale.ROOT));
    }

    static boolean isShulker(Block block) {
        return block != null && Tag.SHULKER_BOXES.isTagged(block.getType());
    }

    private static Object invokeNoArg(Object target, String methodName) throws ReflectiveOperationException {
        return invoke(target, methodName, new Class<?>[0]);
    }

    private static Object invoke(Object target, String methodName, Class<?>[] parameterTypes, Object... args)
            throws ReflectiveOperationException {
        Method method = findMethod(target.getClass(), methodName, parameterTypes);
        method.setAccessible(true);
        return method.invoke(target, args);
    }

    private static Method findMethod(Class<?> type, String name, Class<?>[] parameterTypes)
            throws NoSuchMethodException {
        Class<?> current = type;
        while (current != null) {
            try {
                return current.getDeclaredMethod(name, parameterTypes);
            } catch (NoSuchMethodException ignored) {
                current = current.getSuperclass();
            }
        }
        return type.getMethod(name, parameterTypes);
    }
}
