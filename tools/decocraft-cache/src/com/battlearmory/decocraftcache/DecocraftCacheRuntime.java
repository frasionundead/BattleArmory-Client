package com.battlearmory.decocraftcache;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

public final class DecocraftCacheRuntime {
    private static final int MAX_MODELS = 512;
    private static final String ALPHA_RESOURCE = "battlearmory_translucent_materials.txt";

    private static final Map<String, FamilyState> FAMILIES = new HashMap<>();
    private static final Set<String> TRANSLUCENT_MATERIALS = new HashSet<>();
    private static final boolean ALPHA_LIST_LOADED;

    private static volatile Method cutoutMethod;
    private static volatile Method translucentMethod;
    private static volatile boolean renderTypesInitialized;

    private static final Function<Object, Object> CUTOUT_FN = atlas -> invokeRenderType(false, atlas);
    private static final Function<Object, Object> TRANSLUCENT_FN = atlas -> invokeRenderType(true, atlas);

    static {
        FAMILIES.put("decocraft", new FamilyState(
                "decocraft",
                "com.razz.decocraft.client.render.AnimatedModelRenderer",
                "com.razz.decocraft.models.bbmodel.BBModelParts$Channel"));
        FAMILIES.put("decocraft_nature", new FamilyState(
                "decocraft_nature",
                "com.razz.decocraft_nature.client.render.AnimatedModelRenderer",
                "com.razz.decocraft_nature.models.bbmodel.BBModelParts$Channel"));
        ALPHA_LIST_LOADED = loadAlphaList();
    }

    private DecocraftCacheRuntime() {}

    public static Object getModel(Object renderer, Object meta, Object model, Object tileEntity) {
        FamilyState state = stateForRenderer(renderer);
        if (state.disabled) {
            return buildFallback(state, renderer, meta, model, tileEntity);
        }
        try {
            ensureInitialized(state, renderer, tileEntity);
            Object cached = state.modelCache.get(model);
            if (cached == null) {
                if (state.modelCache.size() >= MAX_MODELS) {
                    state.modelCache.clear();
                    state.baseTransforms.clear();
                }
                cached = invokeBuild(state, renderer, meta, model, tileEntity);
                state.modelCache.put(model, cached);
            }
            Object raw = state.getKeyframesMethod.invoke(tileEntity);
            Map<?, ?> keyframes = raw instanceof Map ? (Map<?, ?>) raw : Collections.emptyMap();
            applyKeyframes(state, cached, keyframes);
            return cached;
        } catch (Throwable t) {
            disable(state, t);
            return buildFallback(state, renderer, meta, model, tileEntity);
        }
    }

    /**
     * Called from both Decocraft renderers instead of their hard-coded
     * RenderType::entityTranslucent method reference. Binary-alpha/opaque textures
     * use entityCutoutNoCull, while textures containing partial alpha retain
     * entityTranslucent and therefore visual blending.
     */
    @SuppressWarnings({"rawtypes", "unchecked"})
    public static Function renderTypeFunction(String namespace, String material) {
        if (!ALPHA_LIST_LOADED || namespace == null || material == null) {
            return TRANSLUCENT_FN;
        }
        String key = namespace + ":block/" + material;
        return TRANSLUCENT_MATERIALS.contains(key) ? TRANSLUCENT_FN : CUTOUT_FN;
    }

    private static FamilyState stateForRenderer(Object renderer) {
        String name = renderer.getClass().getName();
        if (name.startsWith("com.razz.decocraft_nature.")) {
            return FAMILIES.get("decocraft_nature");
        }
        return FAMILIES.get("decocraft");
    }

    private static void ensureInitialized(FamilyState state, Object renderer, Object tileEntity) throws Exception {
        if (state.initialized) return;
        synchronized (state) {
            if (state.initialized) return;

            for (Method method : renderer.getClass().getDeclaredMethods()) {
                if (method.getName().equals("ba$buildModel") && method.getParameterCount() == 3) {
                    method.setAccessible(true);
                    state.buildModelMethod = method;
                    break;
                }
            }
            if (state.buildModelMethod == null) {
                throw new NoSuchMethodException(state.id + " AnimatedRenderer.ba$buildModel");
            }

            state.getKeyframesMethod = tileEntity.getClass().getMethod("getKeyframes");
            state.getKeyframesMethod.setAccessible(true);

            ClassLoader loader = renderer.getClass().getClassLoader();
            state.nodeClass = Class.forName(state.nodeClassName, false, loader);
            state.uuidField = findField(state.nodeClass, "ba$uuid");
            state.xField = findField(state.nodeClass, "f_104200_");
            state.yField = findField(state.nodeClass, "f_104201_");
            state.zField = findField(state.nodeClass, "f_104202_");
            state.rxField = findField(state.nodeClass, "f_104203_");
            state.ryField = findField(state.nodeClass, "f_104204_");
            state.rzField = findField(state.nodeClass, "f_104205_");
            state.childrenField = findField(state.nodeClass, "f_104213_");

            Class<?> channelClass = Class.forName(state.channelClassName, false, loader);
            Object[] constants = channelClass.getEnumConstants();
            if (constants == null) throw new IllegalStateException(state.id + " Channel is not an enum");
            for (Object constant : constants) {
                String enumName = ((Enum<?>) constant).name();
                if (enumName.equals("POSITION")) state.positionChannel = constant;
                else if (enumName.equals("ROTATION")) state.rotationChannel = constant;
            }
            if (state.positionChannel == null || state.rotationChannel == null) {
                throw new IllegalStateException(state.id + " animation channels not found");
            }
            state.initialized = true;
        }
    }

    private static Field findField(Class<?> type, String name) throws NoSuchFieldException {
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            try {
                Field field = current.getDeclaredField(name);
                field.setAccessible(true);
                return field;
            } catch (NoSuchFieldException ignored) {}
        }
        throw new NoSuchFieldException(name);
    }

    private static Object invokeBuild(FamilyState state, Object renderer, Object meta, Object model, Object tileEntity) throws Throwable {
        try {
            return state.buildModelMethod.invoke(renderer, meta, model, tileEntity);
        } catch (InvocationTargetException e) {
            throw e.getCause() != null ? e.getCause() : e;
        }
    }

    private static Object buildFallback(FamilyState state, Object renderer, Object meta, Object model, Object tileEntity) {
        try {
            if (state.buildModelMethod == null) {
                for (Method method : renderer.getClass().getDeclaredMethods()) {
                    if (method.getName().equals("ba$buildModel") && method.getParameterCount() == 3) {
                        method.setAccessible(true);
                        state.buildModelMethod = method;
                        break;
                    }
                }
            }
            if (state.buildModelMethod == null) {
                throw new NoSuchMethodException(state.id + " AnimatedRenderer.ba$buildModel");
            }
            return invokeBuild(state, renderer, meta, model, tileEntity);
        } catch (Throwable t) {
            throw new RuntimeException("Battle Armory " + state.id + " cache fallback failed", t);
        }
    }

    private static void applyKeyframes(FamilyState state, Object node, Map<?, ?> keyframes) throws Exception {
        if (!state.nodeClass.isInstance(node)) return;

        Object uuid = state.uuidField.get(node);
        if (uuid != null) {
            Object rawChannels = keyframes.get(uuid);
            Map<?, ?> channels = rawChannels instanceof Map ? (Map<?, ?>) rawChannels : null;
            float[] position = channels == null ? null : asFloatArray(channels.get(state.positionChannel));
            float[] rotation = channels == null ? null : asFloatArray(channels.get(state.rotationChannel));

            float px = value(position, 0);
            float py = value(position, 1);
            float pz = value(position, 2);
            float rx = value(rotation, 0);
            float ry = value(rotation, 1);
            float rz = value(rotation, 2);

            float[] base = state.baseTransforms.get(node);
            if (base == null) {
                base = new float[] {
                        state.xField.getFloat(node) + px,
                        state.yField.getFloat(node) - py,
                        state.zField.getFloat(node) - pz,
                        state.rxField.getFloat(node) + rx,
                        state.ryField.getFloat(node) + ry,
                        state.rzField.getFloat(node) - rz
                };
                state.baseTransforms.put(node, base);
            }

            state.xField.setFloat(node, base[0] - px);
            state.yField.setFloat(node, base[1] + py);
            state.zField.setFloat(node, base[2] + pz);
            state.rxField.setFloat(node, base[3] - rx);
            state.ryField.setFloat(node, base[4] - ry);
            state.rzField.setFloat(node, base[5] + rz);
        }

        Object rawChildren = state.childrenField.get(node);
        if (rawChildren instanceof Map) {
            for (Object child : ((Map<?, ?>) rawChildren).values()) {
                if (state.nodeClass.isInstance(child)) applyKeyframes(state, child, keyframes);
            }
        }
    }

    private static float[] asFloatArray(Object value) {
        return value instanceof float[] ? (float[]) value : null;
    }

    private static float value(float[] values, int index) {
        return values != null && values.length > index ? values[index] : 0.0f;
    }

    private static void disable(FamilyState state, Throwable t) {
        state.disabled = true;
        state.modelCache.clear();
        state.baseTransforms.clear();
        if (!state.warned) {
            state.warned = true;
            System.err.println("[BattleArmory] " + state.id + " geometry cache disabled; falling back to stock renderer: " + t);
            t.printStackTrace(System.err);
        }
    }

    private static boolean loadAlphaList() {
        try (InputStream stream = DecocraftCacheRuntime.class.getClassLoader().getResourceAsStream(ALPHA_RESOURCE)) {
            if (stream == null) {
                System.err.println("[BattleArmory] Translucent material list missing; keeping stock translucent rendering.");
                return false;
            }
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    line = line.trim();
                    if (!line.isEmpty() && !line.startsWith("#")) TRANSLUCENT_MATERIALS.add(line);
                }
            }
            System.out.println("[BattleArmory] Loaded " + TRANSLUCENT_MATERIALS.size() + " partially-transparent Decocraft materials; other materials use cutout rendering.");
            return true;
        } catch (Throwable t) {
            System.err.println("[BattleArmory] Failed to load translucent material list; keeping stock translucent rendering: " + t);
            TRANSLUCENT_MATERIALS.clear();
            return false;
        }
    }

    private static Object invokeRenderType(boolean translucent, Object atlas) {
        try {
            ensureRenderTypes(atlas.getClass().getClassLoader());
            Method method = translucent ? translucentMethod : cutoutMethod;
            if (method == null && !translucent) method = translucentMethod;
            if (method == null) throw new NoSuchMethodException("RenderType method unavailable");
            return method.invoke(null, atlas);
        } catch (Throwable t) {
            throw new RuntimeException("Battle Armory render-type selection failed", t);
        }
    }

    private static void ensureRenderTypes(ClassLoader fallbackLoader) throws Exception {
        if (renderTypesInitialized) return;
        synchronized (DecocraftCacheRuntime.class) {
            if (renderTypesInitialized) return;
            ClassLoader loader = Thread.currentThread().getContextClassLoader();
            if (loader == null) loader = fallbackLoader;
            Class<?> renderType = Class.forName("net.minecraft.client.renderer.RenderType", false, loader);
            for (Method method : renderType.getDeclaredMethods()) {
                if (!Modifier.isStatic(method.getModifiers()) || method.getParameterCount() != 1) continue;
                String name = method.getName();
                if (name.equals("m_110458_") || name.equals("entityCutoutNoCull")) {
                    method.setAccessible(true);
                    cutoutMethod = method;
                } else if (name.equals("m_110473_") || name.equals("entityTranslucent")) {
                    method.setAccessible(true);
                    translucentMethod = method;
                }
            }
            if (translucentMethod == null) throw new NoSuchMethodException("RenderType.entityTranslucent/m_110473_");
            if (cutoutMethod == null) throw new NoSuchMethodException("RenderType.entityCutoutNoCull/m_110458_");
            renderTypesInitialized = true;
        }
    }

    private static final class FamilyState {
        final String id;
        final String nodeClassName;
        final String channelClassName;
        final IdentityHashMap<Object, Object> modelCache = new IdentityHashMap<>();
        final IdentityHashMap<Object, float[]> baseTransforms = new IdentityHashMap<>();
        volatile boolean initialized;
        volatile boolean disabled;
        volatile boolean warned;
        Method buildModelMethod;
        Method getKeyframesMethod;
        Class<?> nodeClass;
        Field uuidField;
        Field xField;
        Field yField;
        Field zField;
        Field rxField;
        Field ryField;
        Field rzField;
        Field childrenField;
        Object positionChannel;
        Object rotationChannel;

        FamilyState(String id, String nodeClassName, String channelClassName) {
            this.id = id;
            this.nodeClassName = nodeClassName;
            this.channelClassName = channelClassName;
        }
    }
}
