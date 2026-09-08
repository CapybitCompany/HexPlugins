package hexbuildbattle.report;

import hexbuildbattle.arena.CuboidRegion;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.nio.file.Files;

final class WorldEditSchematicBridge {

    private final Class<?> worldEditClass;
    private final Class<?> bukkitAdapterClass;
    private final Class<?> blockVectorClass;
    private final Class<?> cuboidRegionClass;
    private final Class<?> worldClass;
    private final Class<?> clipboardClass;
    private final Class<?> blockArrayClipboardClass;
    private final Class<?> clipboardFormatClass;
    private final Class<?> clipboardWriterClass;
    private final Class<?> clipboardFormatsClass;
    private final Class<?> forwardExtentCopyClass;
    private final Class<?> operationsClass;
    private final Class<?> operationClass;

    WorldEditSchematicBridge() throws ClassNotFoundException {
        this.worldEditClass = Class.forName("com.sk89q.worldedit.WorldEdit");
        this.bukkitAdapterClass = Class.forName("com.sk89q.worldedit.bukkit.BukkitAdapter");
        this.blockVectorClass = Class.forName("com.sk89q.worldedit.math.BlockVector3");
        this.cuboidRegionClass = Class.forName("com.sk89q.worldedit.regions.CuboidRegion");
        this.worldClass = Class.forName("com.sk89q.worldedit.world.World");
        this.clipboardClass = Class.forName("com.sk89q.worldedit.extent.clipboard.Clipboard");
        this.blockArrayClipboardClass = Class.forName("com.sk89q.worldedit.extent.clipboard.BlockArrayClipboard");
        this.clipboardFormatClass = Class.forName("com.sk89q.worldedit.extent.clipboard.io.ClipboardFormat");
        this.clipboardWriterClass = Class.forName("com.sk89q.worldedit.extent.clipboard.io.ClipboardWriter");
        this.clipboardFormatsClass = Class.forName("com.sk89q.worldedit.extent.clipboard.io.ClipboardFormats");
        this.forwardExtentCopyClass = Class.forName("com.sk89q.worldedit.function.operation.ForwardExtentCopy");
        this.operationsClass = Class.forName("com.sk89q.worldedit.function.operation.Operations");
        this.operationClass = Class.forName("com.sk89q.worldedit.function.operation.Operation");
    }

    void save(CuboidRegion region, File file) throws Exception {
        Object worldEdit = worldEditClass.getMethod("getInstance").invoke(null);
        Object editSession = createEditSession(worldEdit, region);
        Object clipboard;
        try {
            Object weWorld = adaptWorld(region);
            Object min = vector(region.minX(), region.minY(), region.minZ());
            Object max = vector(region.maxX(), region.maxY(), region.maxZ());
            Object cuboid = createCuboid(weWorld, min, max);
            clipboard = createClipboard(cuboid, min);
            Object copy = createCopy(editSession, cuboid, clipboard, min);
            invokeOptional(copy, "setCopyingEntities", false);
            invokeOptional(copy, "setCopyingBiomes", false);
            complete(copy);
        } finally {
            closeEditSession(editSession);
        }
        writeClipboard(clipboard, file);
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

    private Object createCuboid(Object weWorld, Object min, Object max) throws Exception {
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

    private Object createClipboard(Object region, Object origin) throws Exception {
        Constructor<?> constructor = findCompatibleConstructor(blockArrayClipboardClass, region);
        if (constructor == null) {
            throw new NoSuchMethodException("No supported BlockArrayClipboard constructor found.");
        }
        Object clipboard = constructor.newInstance(region);
        Method setOrigin = findMethod(clipboard.getClass(), "setOrigin", blockVectorClass);
        if (setOrigin != null) {
            setOrigin.invoke(clipboard, origin);
        }
        return clipboard;
    }

    private Object createCopy(Object source, Object region, Object clipboard, Object point) throws Exception {
        Constructor<?> fourArgs = findCompatibleConstructor(forwardExtentCopyClass, source, region, clipboard, point);
        if (fourArgs != null) {
            return fourArgs.newInstance(source, region, clipboard, point);
        }

        Constructor<?> fiveArgs = findCompatibleConstructor(forwardExtentCopyClass, source, region, point, clipboard, point);
        if (fiveArgs != null) {
            return fiveArgs.newInstance(source, region, point, clipboard, point);
        }

        throw new NoSuchMethodException("No supported ForwardExtentCopy constructor found.");
    }

    private void complete(Object operation) throws Exception {
        Method complete = findMethod(operationsClass, "complete", operationClass);
        if (complete == null) {
            throw new NoSuchMethodException("Operations.complete(Operation)");
        }
        complete.invoke(null, operation);
    }

    private Object vector(int x, int y, int z) throws Exception {
        return blockVectorClass.getMethod("at", int.class, int.class, int.class).invoke(null, x, y, z);
    }

    private void writeClipboard(Object clipboard, File file) throws Exception {
        File parent = file.getParentFile();
        if (parent != null) {
            Files.createDirectories(parent.toPath());
        }
        Object format = clipboardFormat(file);
        try (OutputStream output = new BufferedOutputStream(new FileOutputStream(file))) {
            Object writer = clipboardFormatClass.getMethod("getWriter", OutputStream.class).invoke(format, output);
            try {
                clipboardWriterClass.getMethod("write", clipboardClass).invoke(writer, clipboard);
            } finally {
                if (writer instanceof AutoCloseable closeable) {
                    closeable.close();
                }
            }
        }
    }

    private Object clipboardFormat(File file) throws Exception {
        Method byFile = findMethod(clipboardFormatsClass, "findByFile", File.class);
        Object format = byFile == null ? null : byFile.invoke(null, file);
        if (format != null) {
            return format;
        }

        Method byAlias = findMethod(clipboardFormatsClass, "findByAlias", String.class);
        if (byAlias != null) {
            format = byAlias.invoke(null, "schem");
            if (format == null) {
                format = byAlias.invoke(null, "sponge");
            }
        }
        if (format == null) {
            throw new IllegalStateException("No WorldEdit clipboard format found for .schem files.");
        }
        return format;
    }

    private void closeEditSession(Object editSession) throws Exception {
        if (editSession instanceof AutoCloseable closeable) {
            closeable.close();
            return;
        }
        Method close = findNoArg(editSession.getClass(), "close");
        if (close != null) {
            close.invoke(editSession);
        }
    }

    private void invokeOptional(Object target, String methodName, boolean value) throws Exception {
        Method method = findMethod(target.getClass(), methodName, boolean.class);
        if (method != null) {
            method.invoke(target, value);
        }
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

    private Constructor<?> findCompatibleConstructor(Class<?> owner, Object... args) {
        for (Constructor<?> constructor : owner.getConstructors()) {
            Class<?>[] parameterTypes = constructor.getParameterTypes();
            if (parameterTypes.length != args.length) {
                continue;
            }
            boolean compatible = true;
            for (int i = 0; i < parameterTypes.length; i++) {
                if (!parameterTypes[i].isInstance(args[i])) {
                    compatible = false;
                    break;
                }
            }
            if (compatible) {
                return constructor;
            }
        }
        return null;
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
