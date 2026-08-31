package com.rigorberto.zstdnetworkproject;

import java.lang.reflect.Field;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class ReflectionUtil {

    /**
     * Keyed by the {@link Class} object itself, not its name: Minecraft runtimes routinely load the
     * same class name in several class loaders (mod loaders, plugin loaders, remapped and
     * unremapped views), and a name-keyed cache would hand back a {@link Field} declared by a
     * different {@code Class}, which then fails with {@code IllegalArgumentException} on access.
     */
    private record FieldKey(Class<?> owner, String name) {
    }

    private static final Map<FieldKey, Field> FIELD_CACHE = new ConcurrentHashMap<>();

    private ReflectionUtil() {
    }

    public static Field findField(Class<?> clazz, String name) throws NoSuchFieldException {
        FieldKey key = new FieldKey(clazz, name);
        Field cached = FIELD_CACHE.get(key);
        if (cached != null) {
            return cached;
        }

        Class<?> current = clazz;
        while (current != null) {
            try {
                Field field = current.getDeclaredField(name);
                field.setAccessible(true);
                FIELD_CACHE.put(key, field);
                return field;
            } catch (NoSuchFieldException ignored) {
                current = current.getSuperclass();
            }
        }
        throw new NoSuchFieldException(name + " not found in " + clazz.getName() + " or any superclass");
    }

    public static Field findField(Object target, String name) throws NoSuchFieldException {
        return findField(target.getClass(), name);
    }

    public static Object getFieldValue(Object target, String name) throws IllegalAccessException, NoSuchFieldException {
        return findField(target.getClass(), name).get(target);
    }

    /** True when the class is loadable in the current runtime, false when absent (era detection). */
    public static boolean classExists(String className) {
        try {
            Class.forName(className, false, ReflectionUtil.class.getClassLoader());
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }
}
