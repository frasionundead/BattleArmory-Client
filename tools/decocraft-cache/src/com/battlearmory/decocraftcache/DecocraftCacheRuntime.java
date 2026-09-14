package com.battlearmory.decocraftcache;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

public final class DecocraftCacheRuntime {
    private static final int MAX_MODELS = 512;
    private static final String ALPHA_RESOURCE = "battlearmory_translucent_materials.txt";

    private static final Map<String, FamilyState> FAMILIES = new HashMap<>();
    private static final boolean ALPHA_LIST_LOADED;

    private static volatile MethodHandle cutoutHandle;
    private static volatile MethodHandle translucentHandle;
    private static volatile boolean renderTypesInitialized;

    private static volatile Constructor<?> resourceLocationCtor;
    private static volatile Constructor<?> materialCtor;
    private static volatile Object blockAtlas;
    private static volatile boolean materialsInitialized;

    private static volatile Constructor<?> quaternionCtor;
    private static volatile MethodHandle facingYawHandle;
    private static volatile Object x180Quaternion;
    private static volatile Object z180Quaternion;
    private static volatile boolean quaternionInitialized;
    private static final IdentityHashMap<Object, Object> FACING_QUATERNIONS = new IdentityHashMap<>();

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

    /** Drop resource-derived caches before Minecraft starts a resource-pack reload. */
    public static void clearForResourceReload() {
        int models = 0;
        int materials = 0;
        for (FamilyState state : FAMILIES.values()) {
            models += state.modelCache.size();
            materials += state.materialCache.size();
            state.modelCache.clear();
            state.nodesByModel.clear();
            state.materialCache.clear();
        }
        System.out.println("[BattleArmory] Resource reload: cleared Decocraft caches (models=" + models + ", materials=" + materials + ").");
    }

    public static Object getModel(Object renderer, Object meta, Object model, Object tileEntity) {
        FamilyState state = stateForRenderer(renderer);
        if (state.disabled) {
            return buildFallback(state, renderer, meta, model, tileEntity);
        }
        try {
            ensureInitialized(state, renderer, tileEntity);
            Object raw = state.getKeyframesHandle.invoke(tileEntity);
            Map<?, ?> keyframes = raw instanceof Map ? (Map<?, ?>) raw : java.util.Collections.emptyMap();

            Object cached = state.modelCache.get(model);
            NodeState[] nodes = state.nodesByModel.get(model);
            if (cached == null || nodes == null) {
                if (state.modelCache.size() >= MAX_MODELS) {
                    state.modelCache.clear();
                    state.nodesByModel.clear();
                }
                cached = invokeBuild(state, renderer, meta, model, tileEntity);
                nodes = flattenModel(state, cached, keyframes);
                state.modelCache.put(model, cached);
                state.nodesByModel.put(model, nodes);
            }
            applyKeyframes(state, nodes, keyframes);
            return cached;
        } catch (Throwable t) {
            disable(state, t);
            return buildFallback(state, renderer, meta, model, tileEntity);
        }
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    public static Function renderTypeFunction(String namespace, String material) {
        if (!ALPHA_LIST_LOADED || namespace == null || material == null) {
            return TRANSLUCENT_FN;
        }
        FamilyState state = FAMILIES.get(namespace);
        if (state == null) return TRANSLUCENT_FN;
        return state.translucentMaterials.contains(material) ? TRANSLUCENT_FN : CUTOUT_FN;
    }

    /** Returns a cached Material instance for the Decocraft texture name. */
    public static Object material(String namespace, String material) {
        if (namespace == null || material == null) throw new IllegalArgumentException("namespace/material");
        FamilyState state = FAMILIES.get(namespace);
        if (state == null) throw new IllegalArgumentException("Unknown Decocraft family: " + namespace);
        Object cached = state.materialCache.get(material);
        if (cached != null) return cached;
        try {
            ensureMaterials();
            Object texture = resourceLocationCtor.newInstance(namespace, "block/" + material);
            cached = materialCtor.newInstance(blockAtlas, texture);
            state.materialCache.put(material, cached);
            return cached;
        } catch (Throwable t) {
            throw new RuntimeException("Battle Armory material cache failed for " + namespace + ":" + material, t);
        }
    }

    /** Cached equivalent of Decocraft's per-frame facing quaternion allocation. */
    public static Object facingQuaternion(Object facing) {
        if (facing == null) throw new IllegalArgumentException("facing");
        Object cached = FACING_QUATERNIONS.get(facing);
        if (cached != null) return cached;
        try {
            ensureQuaternionSupport(facing);
            float degrees = ((Number) facingYawHandle.invoke(facing)).floatValue();
            cached = newAxisQuaternion(0.0f, -1.0f, 0.0f, degrees);
            FACING_QUATERNIONS.put(facing, cached);
            return cached;
        } catch (Throwable t) {
            throw new RuntimeException("Battle Armory facing quaternion cache failed", t);
        }
    }

    public static Object x180Quaternion() {
        try {
            ensureQuaternionSupport(null);
            return x180Quaternion;
        } catch (Throwable t) {
            throw new RuntimeException("Battle Armory X quaternion cache failed", t);
        }
    }

    public static Object z180Quaternion() {
        try {
            ensureQuaternionSupport(null);
            return z180Quaternion;
        } catch (Throwable t) {
            throw new RuntimeException("Battle Armory Z quaternion cache failed", t);
        }
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

            Method getKeyframes = tileEntity.getClass().getMethod("getKeyframes");
            getKeyframes.setAccessible(true);
            state.getKeyframesHandle = MethodHandles.lookup().unreflect(getKeyframes);

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

    private static NodeState[] flattenModel(FamilyState state, Object root, Map<?, ?> keyframes) throws Exception {
        List<NodeState> nodes = new ArrayList<>();
        collectNodes(state, root, keyframes, nodes);
        return nodes.toArray(new NodeState[0]);
    }

    private static void collectNodes(FamilyState state, Object node, Map<?, ?> keyframes, List<NodeState> out) throws Exception {
        if (!state.nodeClass.isInstance(node)) return;
        Object uuid = state.uuidField.get(node);
        float px = 0.0f, py = 0.0f, pz = 0.0f, rx = 0.0f, ry = 0.0f, rz = 0.0f;
        if (uuid != null) {
            Object rawChannels = keyframes.get(uuid);
            Map<?, ?> channels = rawChannels instanceof Map ? (Map<?, ?>) rawChannels : null;
            float[] position = channels == null ? null : asFloatArray(channels.get(state.positionChannel));
            float[] rotation = channels == null ? null : asFloatArray(channels.get(state.rotationChannel));
            px = value(position, 0);
            py = value(position, 1);
            pz = value(position, 2);
            rx = value(rotation, 0);
            ry = value(rotation, 1);
            rz = value(rotation, 2);
        }
        out.add(new NodeState(
                node,
                uuid,
                state.xField.getFloat(node) + px,
                state.yField.getFloat(node) - py,
                state.zField.getFloat(node) - pz,
                state.rxField.getFloat(node) + rx,
                state.ryField.getFloat(node) + ry,
                state.rzField.getFloat(node) - rz));

        Object rawChildren = state.childrenField.get(node);
        if (rawChildren instanceof Map) {
            for (Object child : ((Map<?, ?>) rawChildren).values()) {
                if (state.nodeClass.isInstance(child)) collectNodes(state, child, keyframes, out);
            }
        }
    }

    private static void applyKeyframes(FamilyState state, NodeState[] nodes, Map<?, ?> keyframes) throws Exception {
        for (int i = 0; i < nodes.length; i++) {
            NodeState n = nodes[i];
            float px = 0.0f, py = 0.0f, pz = 0.0f, rx = 0.0f, ry = 0.0f, rz = 0.0f;
            if (n.uuid != null) {
                Object rawChannels = keyframes.get(n.uuid);
                Map<?, ?> channels = rawChannels instanceof Map ? (Map<?, ?>) rawChannels : null;
                float[] position = channels == null ? null : asFloatArray(channels.get(state.positionChannel));
                float[] rotation = channels == null ? null : asFloatArray(channels.get(state.rotationChannel));
                px = value(position, 0);
                py = value(position, 1);
                pz = value(position, 2);
                rx = value(rotation, 0);
                ry = value(rotation, 1);
                rz = value(rotation, 2);
            }
            state.xField.setFloat(n.node, n.x - px);
            state.yField.setFloat(n.node, n.y + py);
            state.zField.setFloat(n.node, n.z + pz);
            state.rxField.setFloat(n.node, n.rx - rx);
            state.ryField.setFloat(n.node, n.ry - ry);
            state.rzField.setFloat(n.node, n.rz + rz);
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
        state.nodesByModel.clear();
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
            int count = 0;
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    line = line.trim();
                    if (line.isEmpty() || line.startsWith("#")) continue;
                    int colon = line.indexOf(':');
                    if (colon <= 0) continue;
                    String namespace = line.substring(0, colon);
                    String prefix = "block/";
                    String path = line.substring(colon + 1);
                    if (!path.startsWith(prefix)) continue;
                    FamilyState state = FAMILIES.get(namespace);
                    if (state == null) continue;
                    if (state.translucentMaterials.add(path.substring(prefix.length()))) count++;
                }
            }
            System.out.println("[BattleArmory] Loaded " + count + " partially-transparent Decocraft materials; other materials use cutout rendering.");
            return true;
        } catch (Throwable t) {
            System.err.println("[BattleArmory] Failed to load translucent material list; keeping stock translucent rendering: " + t);
            for (FamilyState state : FAMILIES.values()) state.translucentMaterials.clear();
            return false;
        }
    }

    private static Object invokeRenderType(boolean translucent, Object atlas) {
        try {
            ensureRenderTypes(atlas.getClass().getClassLoader());
            MethodHandle handle = translucent ? translucentHandle : cutoutHandle;
            if (handle == null && !translucent) handle = translucentHandle;
            if (handle == null) throw new NoSuchMethodException("RenderType method unavailable");
            return handle.invoke(atlas);
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
                    cutoutHandle = MethodHandles.lookup().unreflect(method);
                } else if (name.equals("m_110473_") || name.equals("entityTranslucent")) {
                    method.setAccessible(true);
                    translucentHandle = MethodHandles.lookup().unreflect(method);
                }
            }
            if (translucentHandle == null) throw new NoSuchMethodException("RenderType.entityTranslucent/m_110473_");
            if (cutoutHandle == null) throw new NoSuchMethodException("RenderType.entityCutoutNoCull/m_110458_");
            renderTypesInitialized = true;
        }
    }

    private static void ensureMaterials() throws Exception {
        if (materialsInitialized) return;
        synchronized (DecocraftCacheRuntime.class) {
            if (materialsInitialized) return;
            ClassLoader loader = Thread.currentThread().getContextClassLoader();
            Class<?> rl = Class.forName("net.minecraft.resources.ResourceLocation", false, loader);
            Class<?> material = Class.forName("net.minecraft.client.resources.model.Material", false, loader);
            Class<?> atlas = Class.forName("net.minecraft.client.renderer.texture.TextureAtlas", false, loader);
            resourceLocationCtor = rl.getConstructor(String.class, String.class);
            materialCtor = material.getConstructor(rl, rl);
            Field atlasField;
            try {
                atlasField = atlas.getDeclaredField("f_118259_");
            } catch (NoSuchFieldException e) {
                atlasField = atlas.getDeclaredField("LOCATION_BLOCKS");
            }
            atlasField.setAccessible(true);
            blockAtlas = atlasField.get(null);
            materialsInitialized = true;
        }
    }

    private static void ensureQuaternionSupport(Object facing) throws Exception {
        if (!quaternionInitialized) {
            synchronized (DecocraftCacheRuntime.class) {
                if (!quaternionInitialized) {
                    ClassLoader loader = Thread.currentThread().getContextClassLoader();
                    Class<?> quaternion = Class.forName("org.joml.Quaternionf", false, loader);
                    quaternionCtor = quaternion.getConstructor(float.class, float.class, float.class, float.class);
                    x180Quaternion = newAxisQuaternion(-1.0f, 0.0f, 0.0f, 180.0f);
                    z180Quaternion = newAxisQuaternion(0.0f, 0.0f, -1.0f, 180.0f);
                    quaternionInitialized = true;
                }
            }
        }
        if (facing != null && facingYawHandle == null) {
            synchronized (DecocraftCacheRuntime.class) {
                if (facingYawHandle == null) {
                    Method method;
                    try {
                        method = facing.getClass().getMethod("m_122435_");
                    } catch (NoSuchMethodException e) {
                        method = facing.getClass().getMethod("toYRot");
                    }
                    method.setAccessible(true);
                    facingYawHandle = MethodHandles.lookup().unreflect(method);
                }
            }
        }
    }

    private static Object newAxisQuaternion(float ax, float ay, float az, float degrees) throws Exception {
        float half = (float) Math.toRadians(degrees) * 0.5f;
        float s = (float) Math.sin(half);
        float c = (float) Math.cos(half);
        return quaternionCtor.newInstance(ax * s, ay * s, az * s, c);
    }

    private static final class NodeState {
        final Object node;
        final Object uuid;
        final float x, y, z, rx, ry, rz;

        NodeState(Object node, Object uuid, float x, float y, float z, float rx, float ry, float rz) {
            this.node = node;
            this.uuid = uuid;
            this.x = x;
            this.y = y;
            this.z = z;
            this.rx = rx;
            this.ry = ry;
            this.rz = rz;
        }
    }

    private static final class FamilyState {
        final String id;
        final String nodeClassName;
        final String channelClassName;
        final IdentityHashMap<Object, Object> modelCache = new IdentityHashMap<>();
        final IdentityHashMap<Object, NodeState[]> nodesByModel = new IdentityHashMap<>();
        final Map<String, Object> materialCache = new HashMap<>();
        final Set<String> translucentMaterials = new HashSet<>();
        volatile boolean initialized;
        volatile boolean disabled;
        volatile boolean warned;
        Method buildModelMethod;
        MethodHandle getKeyframesHandle;
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
