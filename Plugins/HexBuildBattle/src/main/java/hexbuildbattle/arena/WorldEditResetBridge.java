package hexbuildbattle.arena;

import org.bukkit.Material;
import org.bukkit.block.data.BlockData;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;

final class WorldEditResetBridge {

    private final Class<?> worldEditClass;
    private final Class<?> bukkitAdapterClass;
    private final Class<?> blockVectorClass;
    private final Class<?> cuboidRegionClass;
    private final Class<?> worldClass;

    WorldEditResetBridge() throws ClassNotFoundException {
        this.worldEditClass = Class.forName("com.sk89q.worldedit.WorldEdit");
        this.bukkitAdapterClass = Class.forName("com.sk89q.worldedit.bukkit.BukkitAdapter");
        this.blockVectorClass = Class.forName("com.sk89q.worldedit.math.BlockVector3");
        this.cuboidRegionClass = Class.forName("com.sk89q.worldedit.regions.CuboidRegion");
        this.worldClass = Class.forName("com.sk89q.worldedit.world.World");
    }

    void setRegion(CuboidRegion region, Material material) throws Exception {
        Object worldEdit = worldEditClass.getMethod("getInstance").invoke(null);
        Object editSession = createEditSession(worldEdit, region);
        try {
            Object weWorld = adaptWorld(region);
            Object cuboid = createCuboid(weWorld, region);
            Object blockState = adaptBlockData(material.createBlockData());
            invokeSetBlocks(editSession, cuboid, blockState);
        } finally {
            if (editSession instanceof AutoCloseable closeable) {
                closeable.close();
            } else {
                Method close = findNoArg(editSession.getClass(), "close");
                if (close != null) {
                    close.invoke(editSession);
                }
            }
        }
    }

    private Object createEditSession(Object worldEdit, CuboidRegion region) throws Exception {
        Method builderMethod = findNoArg(worldEditClass, "newEditSessionBuilder");
        if (builderMethod != null) {
            Object builder = builderMethod.invoke(worldEdit);
            Object weWorld = adaptWorld(region);
            invokeCompatible(builder, "world", weWorld);
            Method fastMode = findMethod(builder.getClass(), "fastMode", boolean.class);
            if (fastMode != null) {
                fastMode.invoke(builder, true);
            }
            return builder.getClass().getMethod("build").invoke(builder);
        }
        Method legacy = findMethod(worldEditClass, "newEditSession", worldClass);
        if (legacy != null) {
            return legacy.invoke(worldEdit, adaptWorld(region));
        }
        throw new NoSuchMethodException("No supported WorldEdit edit session factory found.");
    }

    private Object adaptWorld(CuboidRegion region) throws Exception {
        Method adapt = findMethod(bukkitAdapterClass, "adapt", org.bukkit.World.class);
        if (adapt == null) {
            throw new NoSuchMethodException("BukkitAdapter.adapt(World)");
        }
        return adapt.invoke(null, region.world());
    }

    private Object adaptBlockData(BlockData blockData) throws Exception {
        Method adapt = findMethod(bukkitAdapterClass, "adapt", BlockData.class);
        if (adapt == null) {
            throw new NoSuchMethodException("BukkitAdapter.adapt(BlockData)");
        }
        return adapt.invoke(null, blockData);
    }

    private Object createCuboid(Object weWorld, CuboidRegion region) throws Exception {
        Object min = vector(region.minX(), region.minY(), region.minZ());
        Object max = vector(region.maxX(), region.maxY(), region.maxZ());

        Constructor<?> withWorld = findConstructor(cuboidRegionClass, worldClass, blockVectorClass, blockVectorClass);
        if (withWorld != null) {
            return withWorld.newInstance(weWorld, min, max);
        }
        Constructor<?> withoutWorld = findConstructor(cuboidRegionClass, blockVectorClass, blockVectorClass);
        if (withoutWorld != null) {
            return withoutWorld.newInstance(min, max);
        }
        throw new NoSuchMethodException("No supported CuboidRegion constructor found.");
    }

    private Object vector(int x, int y, int z) throws Exception {
        return blockVectorClass.getMethod("at", int.class, int.class, int.class).invoke(null, x, y, z);
    }

    private void invokeSetBlocks(Object editSession, Object region, Object blockState) throws Exception {
        for (Method method : editSession.getClass().getMethods()) {
            if (!method.getName().equals("setBlocks") || method.getParameterCount() != 2) {
                continue;
            }
            Class<?>[] params = method.getParameterTypes();
            if (params[0].isInstance(region) && params[1].isInstance(blockState)) {
                method.invoke(editSession, region, blockState);
                return;
            }
        }
        throw new NoSuchMethodException("No compatible EditSession#setBlocks method found.");
    }

    private void invokeCompatible(Object target, String methodName, Object arg) throws Exception {
        for (Method method : target.getClass().getMethods()) {
            if (!method.getName().equals(methodName) || method.getParameterCount() != 1) {
                continue;
            }
            if (method.getParameterTypes()[0].isInstance(arg)) {
                method.invoke(target, arg);
                return;
            }
        }
        throw new NoSuchMethodException("No compatible method " + methodName + " found on " + target.getClass());
    }

    private Method findNoArg(Class<?> owner, String name) {
        return findMethod(owner, name);
    }

    private Method findMethod(Class<?> owner, String name, Class<?>... parameterTypes) {
        try {
            return owner.getMethod(name, parameterTypes);
        } catch (NoSuchMethodException ignored) {
            return null;
        }
    }

    private Constructor<?> findConstructor(Class<?> owner, Class<?>... parameterTypes) {
        try {
            return owner.getConstructor(parameterTypes);
        } catch (NoSuchMethodException ignored) {
            return null;
        }
    }
}
