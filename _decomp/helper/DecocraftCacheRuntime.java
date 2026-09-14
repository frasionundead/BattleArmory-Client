/*
 * Decompiled with CFR 0.152.
 */
package com.battlearmory.decocraftcache;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Map;

public final class DecocraftCacheRuntime {
    private static final int MAX_MODELS = 512;
    private static final IdentityHashMap<Object, Object> MODEL_CACHE = new IdentityHashMap();
    private static final IdentityHashMap<Object, float[]> BASE_TRANSFORMS = new IdentityHashMap();
    private static volatile boolean initialized;
    private static volatile boolean disabled;
    private static volatile boolean warned;
    private static Method buildModelMethod;
    private static Method getKeyframesMethod;
    private static Class<?> nodeClass;
    private static Field uuidField;
    private static Field xField;
    private static Field yField;
    private static Field zField;
    private static Field rxField;
    private static Field ryField;
    private static Field rzField;
    private static Field childrenField;
    private static Object positionChannel;
    private static Object rotationChannel;

    private DecocraftCacheRuntime() {
    }

    public static Object getModel(Object object, Object object2, Object object3, Object object4) {
        if (disabled) {
            return DecocraftCacheRuntime.buildFallback(object, object2, object3, object4);
        }
        try {
            Map map;
            Object object5;
            DecocraftCacheRuntime.ensureInitialized(object, object4);
            Object object6 = MODEL_CACHE.get(object3);
            if (object6 == null) {
                if (MODEL_CACHE.size() >= 512) {
                    MODEL_CACHE.clear();
                    BASE_TRANSFORMS.clear();
                }
                object6 = DecocraftCacheRuntime.invokeBuild(object, object2, object3, object4);
                MODEL_CACHE.put(object3, object6);
            }
            Map map2 = (object5 = getKeyframesMethod.invoke(object4, new Object[0])) instanceof Map ? (map = (Map)object5) : Collections.emptyMap();
            DecocraftCacheRuntime.applyKeyframes(object6, map2);
            return object6;
        }
        catch (Throwable throwable) {
            DecocraftCacheRuntime.disable(throwable);
            return DecocraftCacheRuntime.buildFallback(object, object2, object3, object4);
        }
    }

    /*
     * WARNING - Removed try catching itself - possible behaviour change.
     */
    private static void ensureInitialized(Object object, Object object2) throws Exception {
        if (initialized) {
            return;
        }
        Class<DecocraftCacheRuntime> clazz = DecocraftCacheRuntime.class;
        synchronized (DecocraftCacheRuntime.class) {
            if (initialized) {
                // ** MonitorExit[clazz] (shouldn't be in output)
                return;
            }
            for (Method method : object.getClass().getDeclaredMethods()) {
                if (!method.getName().equals("ba$buildModel") || method.getParameterCount() != 3) continue;
                method.setAccessible(true);
                buildModelMethod = method;
                break;
            }
            if (buildModelMethod == null) {
                throw new NoSuchMethodException("AnimatedRenderer.ba$buildModel");
            }
            getKeyframesMethod = object2.getClass().getMethod("getKeyframes", new Class[0]);
            getKeyframesMethod.setAccessible(true);
            ClassLoader classLoader = object.getClass().getClassLoader();
            nodeClass = Class.forName("com.razz.decocraft.client.render.AnimatedModelRenderer", false, classLoader);
            uuidField = DecocraftCacheRuntime.findField(nodeClass, "ba$uuid");
            xField = DecocraftCacheRuntime.findField(nodeClass, "f_104200_");
            yField = DecocraftCacheRuntime.findField(nodeClass, "f_104201_");
            zField = DecocraftCacheRuntime.findField(nodeClass, "f_104202_");
            rxField = DecocraftCacheRuntime.findField(nodeClass, "f_104203_");
            ryField = DecocraftCacheRuntime.findField(nodeClass, "f_104204_");
            rzField = DecocraftCacheRuntime.findField(nodeClass, "f_104205_");
            childrenField = DecocraftCacheRuntime.findField(nodeClass, "f_104213_");
            Class<?> clazz2 = Class.forName("com.razz.decocraft.models.bbmodel.BBModelParts$Channel", false, classLoader);
            ?[] objArray = clazz2.getEnumConstants();
            if (objArray == null) {
                throw new IllegalStateException("Decocraft Channel is not an enum");
            }
            for (Object obj : objArray) {
                String string = ((Enum)obj).name();
                if (string.equals("POSITION")) {
                    positionChannel = obj;
                    continue;
                }
                if (!string.equals("ROTATION")) continue;
                rotationChannel = obj;
            }
            if (positionChannel == null || rotationChannel == null) {
                throw new IllegalStateException("Decocraft animation channels not found");
            }
            initialized = true;
            // ** MonitorExit[clazz] (shouldn't be in output)
            return;
        }
    }

    private static Field findField(Class<?> clazz, String string) throws NoSuchFieldException {
        for (Class<?> clazz2 = clazz; clazz2 != null; clazz2 = clazz2.getSuperclass()) {
            try {
                Field field = clazz2.getDeclaredField(string);
                field.setAccessible(true);
                return field;
            }
            catch (NoSuchFieldException noSuchFieldException) {
                continue;
            }
        }
        throw new NoSuchFieldException(string);
    }

    private static Object invokeBuild(Object object, Object object2, Object object3, Object object4) throws Throwable {
        try {
            return buildModelMethod.invoke(object, object2, object3, object4);
        }
        catch (InvocationTargetException invocationTargetException) {
            throw invocationTargetException.getCause() != null ? invocationTargetException.getCause() : invocationTargetException;
        }
    }

    private static Object buildFallback(Object object, Object object2, Object object3, Object object4) {
        try {
            if (buildModelMethod == null) {
                for (Method method : object.getClass().getDeclaredMethods()) {
                    if (!method.getName().equals("ba$buildModel") || method.getParameterCount() != 3) continue;
                    method.setAccessible(true);
                    buildModelMethod = method;
                    break;
                }
            }
            if (buildModelMethod == null) {
                throw new NoSuchMethodException("AnimatedRenderer.ba$buildModel");
            }
            return DecocraftCacheRuntime.invokeBuild(object, object2, object3, object4);
        }
        catch (Throwable throwable) {
            throw new RuntimeException("Battle Armory Decocraft cache fallback failed", throwable);
        }
    }

    private static void applyKeyframes(Object object, Map<?, ?> map) throws Exception {
        Object object2;
        Object object3;
        if (!nodeClass.isInstance(object)) {
            return;
        }
        Object object4 = uuidField.get(object);
        if (object4 != null) {
            Object object5;
            object3 = null;
            object2 = map.get(object4);
            if (object2 instanceof Map) {
                object3 = object5 = (Map)object2;
            }
            object5 = object3 == null ? null : (float[])object3.get(positionChannel);
            Object object6 = object3 == null ? null : (float[])object3.get(rotationChannel);
            float f = DecocraftCacheRuntime.value((float[])object5, 0);
            float f2 = DecocraftCacheRuntime.value((float[])object5, 1);
            float f3 = DecocraftCacheRuntime.value((float[])object5, 2);
            float f4 = DecocraftCacheRuntime.value(object6, 0);
            float f5 = DecocraftCacheRuntime.value(object6, 1);
            float f6 = DecocraftCacheRuntime.value(object6, 2);
            float[] fArray = BASE_TRANSFORMS.get(object);
            if (fArray == null) {
                fArray = new float[]{xField.getFloat(object) + f, yField.getFloat(object) - f2, zField.getFloat(object) - f3, rxField.getFloat(object) + f4, ryField.getFloat(object) + f5, rzField.getFloat(object) - f6};
                BASE_TRANSFORMS.put(object, fArray);
            }
            xField.setFloat(object, fArray[0] - f);
            yField.setFloat(object, fArray[1] + f2);
            zField.setFloat(object, fArray[2] + f3);
            rxField.setFloat(object, fArray[3] - f4);
            ryField.setFloat(object, fArray[4] - f5);
            rzField.setFloat(object, fArray[5] + f6);
        }
        if ((object3 = childrenField.get(object)) instanceof Map) {
            object2 = (Map)object3;
            for (Object object6 : object2.values()) {
                if (!nodeClass.isInstance(object6)) continue;
                DecocraftCacheRuntime.applyKeyframes(object6, map);
            }
        }
    }

    private static float value(float[] fArray, int n) {
        return fArray != null && fArray.length > n ? fArray[n] : 0.0f;
    }

    private static void disable(Throwable throwable) {
        disabled = true;
        MODEL_CACHE.clear();
        BASE_TRANSFORMS.clear();
        if (!warned) {
            warned = true;
            System.err.println("[BattleArmory] Decocraft geometry cache disabled; falling back to stock renderer: " + String.valueOf(throwable));
            throwable.printStackTrace(System.err);
        }
    }
}

