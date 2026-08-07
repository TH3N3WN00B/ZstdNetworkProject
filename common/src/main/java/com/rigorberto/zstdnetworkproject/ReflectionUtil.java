package com.rigorberto.zstdnetworkproject;

import java.lang.reflect.Field;

public final class ReflectionUtil {

    private ReflectionUtil() {
    }

    public static Field findField(Class<?> clazz, String name) throws NoSuchFieldException {
        Class<?> current = clazz;
        while (current != null) {
            try {
                return current.getDeclaredField(name);
            } catch (NoSuchFieldException ignored) {
                current = current.getSuperclass();
            }
        }
        throw new NoSuchFieldException(name + " not found in " + clazz.getName() + " or any superclass");
    }

    public static Field findField(Object target, String name) throws NoSuchFieldException {
        Field field = findField(target.getClass(), name);
        field.setAccessible(true);
        return field;
    }

    public static Object getFieldValue(Object target, String name) throws IllegalAccessException, NoSuchFieldException {
        return findField(target, name).get(target);
    }
}
