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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
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

    // Content-addressed interner for immutable Decocraft BakedQuads.  A custom
    // open-addressing table avoids millions of HashMap node/key allocations.
    private static final Object QUAD_LOCK = new Object();
    private static final int QUAD_INITIAL_CAPACITY = 1 << 14;
    private static final int QUAD_MAX_ENTRIES = 1 << 20;
    private static final int QUAD_MAX_CAPACITY = 1 << 21;
    private static Object[] quadObjects = new Object[QUAD_INITIAL_CAPACITY];
    private static int[][] quadVertices = new int[QUAD_INITIAL_CAPACITY][];
    private static Object[] quadDirections = new Object[QUAD_INITIAL_CAPACITY];
    private static Object[] quadSprites = new Object[QUAD_INITIAL_CAPACITY];
    private static int[] quadTints = new int[QUAD_INITIAL_CAPACITY];
    private static boolean[] quadShades = new boolean[QUAD_INITIAL_CAPACITY];
    private static long[] quadHashes = new long[QUAD_INITIAL_CAPACITY];
    private static int quadMask = QUAD_INITIAL_CAPACITY - 1;
    private static int quadSize;
    private static boolean quadInternerSaturated;
    private static long quadHits;
    private static long quadMisses;
    private static volatile MethodHandle bakedQuadConstructor;

    // Temporary startup diagnostics for build 0013.  These counters tell us how
    // many quads Decocraft/Nature ask to bake versus how many unique quads survive
    // the content-addressed interner.  A daemon snapshot writer exits after the
    // bake count has been stable for ~10 seconds.
    private static final AtomicLong DECOCRAFT_BAKE_CALLS = new AtomicLong();
    private static final AtomicLong NATURE_BAKE_CALLS = new AtomicLong();
    private static final AtomicBoolean BAKE_STATS_THREAD_STARTED = new AtomicBoolean();
    private static final AtomicLong WHOLE_MODEL_CACHE_HITS = new AtomicLong();
    private static final AtomicLong WHOLE_MODEL_CACHE_MISSES = new AtomicLong();
    private static final AtomicLong WHOLE_MODEL_QUADS_BUILT = new AtomicLong();
    private static final AtomicLong WHOLE_MODEL_QUADS_REPLAYED = new AtomicLong();
    private static final AtomicBoolean WHOLE_MODEL_STATS_THREAD_STARTED = new AtomicBoolean();

    // v1.7: four-way blockstate rotations keep distinct BakedQuad wrappers/directions,
    // but share one retained int[] payload per source face. Rotated vertex arrays are
    // materialized lazily only for variants that are actually rendered in loaded chunks.
    private static final AtomicLong ROTATION_BASE_MODELS = new AtomicLong();
    private static final AtomicLong ROTATION_QUADS_COMPRESSED = new AtomicLong();
    private static final AtomicLong ROTATION_QUADS_UNMATCHED = new AtomicLong();
    private static final AtomicLong ROTATION_LAZY_MATERIALIZATIONS = new AtomicLong();
    private static final AtomicLong ROTATION_BYTES_SHARED = new AtomicLong();
    private static final AtomicLong ROTATION_BYTES_MATERIALIZED = new AtomicLong();
    private static final AtomicBoolean ROTATION_STATS_THREAD_STARTED = new AtomicBoolean();
    private static volatile Method bakedQuadVerticesMethod;
    private static volatile Method bakedQuadCompressMethod;

    // target-vertex -> source-vertex permutations. Identity must stay at index 0.
    private static final int[][] QUAD_PERMUTATIONS = new int[][] {
            {0,1,2,3}, {0,1,3,2}, {0,2,1,3}, {0,2,3,1}, {0,3,1,2}, {0,3,2,1},
            {1,0,2,3}, {1,0,3,2}, {1,2,0,3}, {1,2,3,0}, {1,3,0,2}, {1,3,2,0},
            {2,0,1,3}, {2,0,3,1}, {2,1,0,3}, {2,1,3,0}, {2,3,0,1}, {2,3,1,0},
            {3,0,1,2}, {3,0,2,1}, {3,1,0,2}, {3,1,2,0}, {3,2,0,1}, {3,2,1,0}
    };

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
        int resourceModels = 0;
        for (FamilyState state : FAMILIES.values()) {
            models += state.modelCache.size();
            materials += state.materialCache.size();
            resourceModels += state.resourceParsedModels.size();
            state.modelCache.clear();
            state.nodesByModel.clear();
            state.materialCache.clear();
            state.resourceParsedModels.clear();
            state.wholeBakedModels.clear();
            state.rotationBaseVertices.clear();
            state.wholeCacheHits = 0L;
            state.wholeCacheMisses = 0L;
        }

        int quadEntries;
        long hits;
        long misses;
        synchronized (QUAD_LOCK) {
            quadEntries = quadSize;
            hits = quadHits;
            misses = quadMisses;
            resetQuadInterner();
        }
        DECOCRAFT_BAKE_CALLS.set(0L);
        NATURE_BAKE_CALLS.set(0L);
        BAKE_STATS_THREAD_STARTED.set(false);
        ROTATION_BASE_MODELS.set(0L);
        ROTATION_QUADS_COMPRESSED.set(0L);
        ROTATION_QUADS_UNMATCHED.set(0L);
        ROTATION_LAZY_MATERIALIZATIONS.set(0L);
        ROTATION_BYTES_SHARED.set(0L);
        ROTATION_BYTES_MATERIALIZED.set(0L);
        ROTATION_STATS_THREAD_STARTED.set(false);
        System.out.println("[BattleArmory] Resource reload: cleared Decocraft caches (models=" + models
                + ", materials=" + materials + ", resourceModels=" + resourceModels
                + ", quads=" + quadEntries + ", quadHits=" + hits + ", quadMisses=" + misses + ").");
    }

    public static void recordBakeCall(String family) {
        if ("decocraft_nature".equals(family)) {
            NATURE_BAKE_CALLS.incrementAndGet();
        } else if ("decocraft".equals(family)) {
            DECOCRAFT_BAKE_CALLS.incrementAndGet();
        }
        startBakeStatsWriter();
    }

    private static void startBakeStatsWriter() {
        if (!BAKE_STATS_THREAD_STARTED.compareAndSet(false, true)) return;
        Thread writer = new Thread(() -> {
            long previous = -1L;
            int stableIntervals = 0;
            try {
                while (stableIntervals < 5) {
                    Thread.sleep(2000L);
                    long current = DECOCRAFT_BAKE_CALLS.get() + NATURE_BAKE_CALLS.get();
                    writeBakeStatsSnapshot();
                    if (current == previous) stableIntervals++;
                    else stableIntervals = 0;
                    previous = current;
                }
                writeBakeStatsSnapshot();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            } catch (Throwable t) {
                System.err.println("[BattleArmory][BakeStats] writer failed: " + t);
            }
        }, "BattleArmory-BakeStats");
        writer.setDaemon(true);
        writer.start();
    }

    private static void writeBakeStatsSnapshot() {
        long decocraft = DECOCRAFT_BAKE_CALLS.get();
        long nature = NATURE_BAKE_CALLS.get();
        int unique;
        long hits;
        long misses;
        boolean saturated;
        synchronized (QUAD_LOCK) {
            unique = quadSize;
            hits = quadHits;
            misses = quadMisses;
            saturated = quadInternerSaturated;
        }
        long calls = decocraft + nature;
        double hitRate = calls == 0L ? 0.0 : (100.0 * hits / calls);
        String stats = "Battle Armory bake diagnostics 0013\n"
                + "decocraft_calls=" + decocraft + "\n"
                + "decocraft_nature_calls=" + nature + "\n"
                + "family_calls_total=" + calls + "\n"
                + "interner_unique=" + unique + "\n"
                + "interner_hits=" + hits + "\n"
                + "interner_misses=" + misses + "\n"
                + "interner_hit_rate_percent=" + String.format(java.util.Locale.ROOT, "%.2f", hitRate) + "\n"
                + "interner_saturated=" + saturated + "\n";
        try {
            Path out = Path.of(System.getProperty("user.dir"), "battlearmory-bake-stats.txt");
            Files.writeString(out, stats, StandardCharsets.UTF_8);
        } catch (Throwable t) {
            System.err.println("[BattleArmory][BakeStats] snapshot write failed: " + t);
        }
        System.out.println("[BattleArmory][BakeStats] calls=" + calls + " decocraft=" + decocraft
                + " nature=" + nature + " unique=" + unique + " hits=" + hits + " misses=" + misses
                + " saturated=" + saturated);
    }

    /**
     * Return one canonical immutable BakedQuad for identical baked vertex data.
     * The constructor is invoked only on misses, so duplicate BakedQuad objects and
     * their int[] vertex payloads never enter Minecraft's retained baked-model graph.
     */
    public static Object canonicalBakedQuad(int[] vertices, int tint, Object direction, Object sprite, boolean shade) {
        if (vertices == null || direction == null || sprite == null) {
            return newBakedQuad(vertices, tint, direction, sprite, shade);
        }

        long hash = quadHash(vertices, tint, direction, sprite, shade);
        synchronized (QUAD_LOCK) {
            for (;;) {
                int slot = ((int) (hash ^ (hash >>> 32))) & quadMask;
                int probes = 0;
                while (true) {
                    Object existing = quadObjects[slot];
                    if (existing == null) break;
                    if (quadHashes[slot] == hash
                            && quadTints[slot] == tint
                            && quadDirections[slot] == direction
                            && quadSprites[slot] == sprite
                            && quadShades[slot] == shade
                            && sameVertices(quadVertices[slot], vertices)) {
                        quadHits++;
                        return existing;
                    }
                    slot = (slot + 1) & quadMask;
                    if (++probes > quadMask) {
                        quadInternerSaturated = true;
                        quadMisses++;
                        return newBakedQuad(vertices, tint, direction, sprite, shade);
                    }
                }

                if (quadSize >= QUAD_MAX_ENTRIES) {
                    quadInternerSaturated = true;
                    quadMisses++;
                    return newBakedQuad(vertices, tint, direction, sprite, shade);
                }
                if ((quadSize + 1) * 10 > quadObjects.length * 6 && quadObjects.length < QUAD_MAX_CAPACITY) {
                    growQuadInterner();
                    continue;
                }

                Object created = newBakedQuad(vertices, tint, direction, sprite, shade);
                quadObjects[slot] = created;
                quadVertices[slot] = vertices;
                quadDirections[slot] = direction;
                quadSprites[slot] = sprite;
                quadTints[slot] = tint;
                quadShades[slot] = shade;
                quadHashes[slot] = hash;
                quadSize++;
                quadMisses++;
                return created;
            }
        }
    }

    public static Object uncachedBakedQuad(int[] vertices, int tint, Object direction, Object sprite, boolean shade) {
        return newBakedQuad(vertices, tint, direction, sprite, shade);
    }

    private static Object newBakedQuad(int[] vertices, int tint, Object direction, Object sprite, boolean shade) {
        try {
            ensureBakedQuadConstructor(direction, sprite);
            return bakedQuadConstructor.invoke(vertices, tint, direction, sprite, shade);
        } catch (Throwable t) {
            throw new RuntimeException("Battle Armory BakedQuad construction failed", t);
        }
    }

    private static void ensureBakedQuadConstructor(Object direction, Object sprite) throws Exception {
        if (bakedQuadConstructor != null) return;
        synchronized (DecocraftCacheRuntime.class) {
            if (bakedQuadConstructor != null) return;
            ClassLoader loader = direction != null ? direction.getClass().getClassLoader() : null;
            if (loader == null && sprite != null) loader = sprite.getClass().getClassLoader();
            if (loader == null) loader = Thread.currentThread().getContextClassLoader();
            if (loader == null) loader = DecocraftCacheRuntime.class.getClassLoader();
            Class<?> bakedQuad = Class.forName("net.minecraft.client.renderer.block.model.BakedQuad", false, loader);
            Constructor<?> match = null;
            for (Constructor<?> ctor : bakedQuad.getDeclaredConstructors()) {
                Class<?>[] p = ctor.getParameterTypes();
                if (p.length == 5
                        && p[0] == int[].class
                        && p[1] == int.class
                        && p[2].getName().equals("net.minecraft.core.Direction")
                        && p[3].getName().equals("net.minecraft.client.renderer.texture.TextureAtlasSprite")
                        && p[4] == boolean.class) {
                    match = ctor;
                    break;
                }
            }
            if (match == null) throw new NoSuchMethodException("BakedQuad(int[],int,Direction,TextureAtlasSprite,boolean)");
            match.setAccessible(true);
            bakedQuadConstructor = MethodHandles.lookup().unreflectConstructor(match);
        }
    }

    private static long quadHash(int[] vertices, int tint, Object direction, Object sprite, boolean shade) {
        long h = 0xcbf29ce484222325L;
        for (int value : vertices) {
            h ^= (value & 0xffffffffL);
            h *= 0x100000001b3L;
        }
        h ^= (tint & 0xffffffffL);
        h *= 0x100000001b3L;
        h ^= (System.identityHashCode(direction) & 0xffffffffL);
        h *= 0x100000001b3L;
        h ^= (System.identityHashCode(sprite) & 0xffffffffL);
        h *= 0x100000001b3L;
        h ^= shade ? 0x9e3779b97f4a7c15L : 0x243f6a8885a308d3L;
        h ^= h >>> 33;
        h *= 0xff51afd7ed558ccdL;
        h ^= h >>> 33;
        h *= 0xc4ceb9fe1a85ec53L;
        h ^= h >>> 33;
        return h;
    }

    private static boolean sameVertices(int[] a, int[] b) {
        if (a == b) return true;
        if (a == null || b == null || a.length != b.length) return false;
        for (int i = 0; i < a.length; i++) {
            if (a[i] != b[i]) return false;
        }
        return true;
    }

    private static void growQuadInterner() {
        int oldCapacity = quadObjects.length;
        int newCapacity = Math.min(oldCapacity << 1, QUAD_MAX_CAPACITY);
        if (newCapacity <= oldCapacity) return;

        Object[] oldObjects = quadObjects;
        int[][] oldVertices = quadVertices;
        Object[] oldDirections = quadDirections;
        Object[] oldSprites = quadSprites;
        int[] oldTints = quadTints;
        boolean[] oldShades = quadShades;
        long[] oldHashes = quadHashes;

        quadObjects = new Object[newCapacity];
        quadVertices = new int[newCapacity][];
        quadDirections = new Object[newCapacity];
        quadSprites = new Object[newCapacity];
        quadTints = new int[newCapacity];
        quadShades = new boolean[newCapacity];
        quadHashes = new long[newCapacity];
        quadMask = newCapacity - 1;

        for (int i = 0; i < oldCapacity; i++) {
            Object quad = oldObjects[i];
            if (quad == null) continue;
            long hash = oldHashes[i];
            int slot = ((int) (hash ^ (hash >>> 32))) & quadMask;
            while (quadObjects[slot] != null) slot = (slot + 1) & quadMask;
            quadObjects[slot] = quad;
            quadVertices[slot] = oldVertices[i];
            quadDirections[slot] = oldDirections[i];
            quadSprites[slot] = oldSprites[i];
            quadTints[slot] = oldTints[i];
            quadShades[slot] = oldShades[i];
            quadHashes[slot] = hash;
        }
    }

    private static void resetQuadInterner() {
        quadObjects = new Object[QUAD_INITIAL_CAPACITY];
        quadVertices = new int[QUAD_INITIAL_CAPACITY][];
        quadDirections = new Object[QUAD_INITIAL_CAPACITY];
        quadSprites = new Object[QUAD_INITIAL_CAPACITY];
        quadTints = new int[QUAD_INITIAL_CAPACITY];
        quadShades = new boolean[QUAD_INITIAL_CAPACITY];
        quadHashes = new long[QUAD_INITIAL_CAPACITY];
        quadMask = QUAD_INITIAL_CAPACITY - 1;
        quadSize = 0;
        quadInternerSaturated = false;
        quadHits = 0L;
        quadMisses = 0L;
    }

    /**
     * Cache the complete immutable baked-quad list for one Decocraft geometry variant.
     * The stock addQuads implementation rebakes every face for every outer model JSON,
     * even when the BBModel, scale/material and ModelState transformation are identical.
     * This method performs that work once and replays the same BakedQuad references into
     * subsequent builders.  The cache is cleared on resource reload.
     */
    @SuppressWarnings({"rawtypes", "unchecked"})
    public static void addQuadsCached(String family, Object model, Object context, Object builder,
                                      Object modelBaker, Object spriteGetter, Object modelState,
                                      Object outerLocation) {
        FamilyState state = FAMILIES.get(family);
        if (state == null) throw new IllegalArgumentException("Unknown Decocraft family: " + family);
        try {
            ensureWholeBakeInitialized(state, model, context, builder, modelState);

            Object settings = state.wholeSettingsField.get(model);
            String material = (String) state.wholeSettingMaterialField.get(settings);
            Object resolvedMaterial = state.resolveDirtyMaterialHandle.invoke(material, context);
            Object sprite = ((Function) spriteGetter).apply(resolvedMaterial);
            Object rotation = state.modelStateRotationHandle.invoke(modelState);
            String modelPath = String.valueOf(state.wholeSettingLocationField.get(settings));
            int scaleBits = Float.floatToIntBits(state.wholeSettingScaleField.getFloat(settings));
            boolean flipV = state.wholeSettingFlipVField.getBoolean(settings);

            WholeModelKey key = new WholeModelKey(modelPath, scaleBits, flipV, material, rotation, sprite);
            Object[] cached = state.wholeBakedModels.get(key);
            if (cached != null) {
                for (Object quad : cached) state.addUnculledFaceHandle.invoke(builder, quad);
                state.wholeCacheHits++;
                WHOLE_MODEL_CACHE_HITS.incrementAndGet();
                WHOLE_MODEL_QUADS_REPLAYED.addAndGet(cached.length);
                startWholeModelStatsWriter();
                return;
            }

            Object bakery = state.wholeBakeryField.get(model);
            Object resolution = state.wholeResolutionField.get(model);
            List<?> elements = (List<?>) state.wholeElementsField.get(model);
            Object root = null;
            for (Object element : elements) {
                if (element == null) continue;
                Object rawName = state.wholeElementNameField.get(element);
                Object rawType = state.wholeElementTypeField.get(element);
                String name = rawName == null ? null : rawName.toString().toLowerCase();
                if ("root_node".equals(name) && "locator".equals(rawType)) root = element;
            }

            ArrayList<Object> built = new ArrayList<>();
            for (Object element : elements) {
                if (element == null) continue;
                Object rawFaces = state.wholeElementFacesField.get(element);
                if (!(rawFaces instanceof Map)) continue;
                Map<?, ?> faces = (Map<?, ?>) rawFaces;
                for (Object direction : faces.keySet()) {
                    Object face = faces.get(direction);
                    if (face == null) continue;
                    if (state.wholeFaceUvField == null) {
                        synchronized (state) {
                            if (state.wholeFaceUvField == null) state.wholeFaceUvField = findField(face.getClass(), "uv");
                        }
                    }
                    Object uv = state.wholeFaceUvField.get(face);
                    Object quad = state.wholeBakeQuadHandle.invoke(
                            bakery, element, settings, root, uv, resolution, sprite, direction, modelState);
                    built.add(quad);
                }
            }

            Object[] baked = built.toArray(new Object[0]);
            RotationBaseKey rotationBaseKey = new RotationBaseKey(modelPath, scaleBits, flipV, material, sprite);
            compressRotationVariant(state, rotationBaseKey, baked);
            for (Object quad : baked) state.addUnculledFaceHandle.invoke(builder, quad);

            Object[] prior = state.wholeBakedModels.putIfAbsent(key, baked);
            if (prior == null) {
                state.wholeCacheMisses++;
                WHOLE_MODEL_CACHE_MISSES.incrementAndGet();
                WHOLE_MODEL_QUADS_BUILT.addAndGet(baked.length);
            } else {
                // Model baking is normally single-threaded, but keep concurrent reloads safe.
                state.wholeCacheHits++;
                WHOLE_MODEL_CACHE_HITS.incrementAndGet();
            }
            startWholeModelStatsWriter();
        } catch (Throwable t) {
            throw new RuntimeException("Battle Armory whole-model bake cache failed for " + family, t);
        }
    }

    /**
     * Share the large int[] payload across the four blockstate rotations without changing
     * the BakedQuad object, sprite, face direction, tint or shade of any variant. We first
     * let Decocraft bake the exact stock result, then prove that the target payload is a
     * pure Y-quarter-turn plus vertex permutation of the first retained variant. Unknown
     * layouts simply fall back to stock storage.
     */
    private static void compressRotationVariant(FamilyState state, RotationBaseKey key, Object[] baked) throws Exception {
        if (baked == null || baked.length == 0) return;
        int[][] candidate = new int[baked.length][];
        for (int i = 0; i < baked.length; i++) {
            candidate[i] = bakedQuadVertices(baked[i]);
            if (candidate[i] == null || candidate[i].length != 32) return;
        }

        int[][] base = state.rotationBaseVertices.putIfAbsent(key, candidate);
        if (base == null) {
            ROTATION_BASE_MODELS.incrementAndGet();
            startRotationStatsWriter();
            return;
        }
        if (base.length != candidate.length) return;

        for (int i = 0; i < baked.length; i++) {
            int[] baseVertices = base[i];
            int[] targetVertices = candidate[i];
            if (baseVertices == null || targetVertices == null || baseVertices.length != targetVertices.length) {
                ROTATION_QUADS_UNMATCHED.incrementAndGet();
                continue;
            }
            int plan = findRotationPlan(baseVertices, targetVertices);
            if (plan < 0) {
                ROTATION_QUADS_UNMATCHED.incrementAndGet();
                continue;
            }

            // Plan zero is exact identity: share the array directly and keep the normal
            // BakedQuad getter path. Other plans are encoded +1; zero remains "uncompressed".
            byte encoded = plan == 0 ? (byte) 0 : (byte) (plan + 1);
            compressBakedQuad(baked[i], baseVertices, encoded);
            ROTATION_QUADS_COMPRESSED.incrementAndGet();
            ROTATION_BYTES_SHARED.addAndGet(intArrayFootprint(targetVertices.length));
        }
        startRotationStatsWriter();
    }

    private static int[] bakedQuadVertices(Object quad) throws Exception {
        ensureBakedQuadCompressionMethods(quad);
        try {
            return (int[]) bakedQuadVerticesMethod.invoke(quad);
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            throw new RuntimeException("BakedQuad.getVertices failed", cause);
        }
    }

    private static void compressBakedQuad(Object quad, int[] base, byte encodedPlan) throws Exception {
        ensureBakedQuadCompressionMethods(quad);
        try {
            bakedQuadCompressMethod.invoke(quad, base, encodedPlan);
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            throw new RuntimeException("BakedQuad.ba$compress failed", cause);
        }
    }

    private static void ensureBakedQuadCompressionMethods(Object quad) throws Exception {
        if (bakedQuadVerticesMethod != null && bakedQuadCompressMethod != null) return;
        synchronized (DecocraftCacheRuntime.class) {
            if (bakedQuadVerticesMethod != null && bakedQuadCompressMethod != null) return;
            Class<?> type = quad.getClass();
            Method vertices = null;
            for (Method method : type.getMethods()) {
                if (method.getParameterCount() == 0 && method.getReturnType() == int[].class) {
                    vertices = method;
                    break;
                }
            }
            if (vertices == null) throw new NoSuchMethodException(type.getName() + " int[] getter");
            vertices.setAccessible(true);
            Method compress = type.getMethod("ba$compress", int[].class, byte.class);
            compress.setAccessible(true);
            bakedQuadVerticesMethod = vertices;
            bakedQuadCompressMethod = compress;
        }
    }

    /**
     * Embeddium 0.3.31 reads BakedQuad.vertices directly from its BakedQuadMixin
     * instead of calling BakedQuad.getVertices(). Route those reads through the
     * patched getter so compressed quarter-turn variants materialize correctly.
     */
    public static int[] effectiveBakedQuadVertices(Object quad) {
        if (quad == null) return null;
        try {
            ensureBakedQuadCompressionMethods(quad);
            return (int[]) bakedQuadVerticesMethod.invoke(quad);
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            throw new RuntimeException("BakedQuad effective vertices failed", cause);
        } catch (Throwable t) {
            throw new RuntimeException("BakedQuad effective vertices failed", t);
        }
    }

    /** Called by the coremod-patched BakedQuad getter only for compressed variants. */
    public static int[] materializeRotatedVertices(int[] base, byte encodedPlan) {
        if (base == null) return null;
        int encoded = encodedPlan & 0xFF;
        if (encoded == 0) return base;
        int plan = encoded - 1;
        int rotation = plan / 24;
        int permutationIndex = plan % 24;
        if (rotation < 0 || rotation > 3 || permutationIndex < 0 || permutationIndex >= QUAD_PERMUTATIONS.length
                || base.length != 32) {
            return base.clone();
        }

        int[] out = new int[base.length];
        int stride = 8;
        int[] permutation = QUAD_PERMUTATIONS[permutationIndex];
        for (int targetVertex = 0; targetVertex < 4; targetVertex++) {
            int sourceVertex = permutation[targetVertex];
            int sourceOffset = sourceVertex * stride;
            int targetOffset = targetVertex * stride;
            System.arraycopy(base, sourceOffset, out, targetOffset, stride);

            float x = Float.intBitsToFloat(base[sourceOffset]);
            float y = Float.intBitsToFloat(base[sourceOffset + 1]);
            float z = Float.intBitsToFloat(base[sourceOffset + 2]);
            out[targetOffset] = Float.floatToRawIntBits(rotatedX(x, z, rotation));
            out[targetOffset + 1] = Float.floatToRawIntBits(y);
            out[targetOffset + 2] = Float.floatToRawIntBits(rotatedZ(x, z, rotation));
            out[targetOffset + 7] = rotatePackedNormal(base[sourceOffset + 7], rotation);
        }
        ROTATION_LAZY_MATERIALIZATIONS.incrementAndGet();
        ROTATION_BYTES_MATERIALIZED.addAndGet(intArrayFootprint(out.length));
        startRotationStatsWriter();
        return out;
    }

    private static int findRotationPlan(int[] base, int[] target) {
        if (base == null || target == null || base.length != 32 || target.length != 32) return -1;
        for (int rotation = 0; rotation < 4; rotation++) {
            for (int permutationIndex = 0; permutationIndex < QUAD_PERMUTATIONS.length; permutationIndex++) {
                if (matchesRotationPlan(base, target, rotation, QUAD_PERMUTATIONS[permutationIndex])) {
                    return rotation * 24 + permutationIndex;
                }
            }
        }
        return -1;
    }

    private static boolean matchesRotationPlan(int[] base, int[] target, int rotation, int[] permutation) {
        final int stride = 8;
        for (int targetVertex = 0; targetVertex < 4; targetVertex++) {
            int sourceVertex = permutation[targetVertex];
            int sourceOffset = sourceVertex * stride;
            int targetOffset = targetVertex * stride;
            float x = Float.intBitsToFloat(base[sourceOffset]);
            float y = Float.intBitsToFloat(base[sourceOffset + 1]);
            float z = Float.intBitsToFloat(base[sourceOffset + 2]);
            if (!closeFloat(rotatedX(x, z, rotation), Float.intBitsToFloat(target[targetOffset]))) return false;
            if (!closeFloat(y, Float.intBitsToFloat(target[targetOffset + 1]))) return false;
            if (!closeFloat(rotatedZ(x, z, rotation), Float.intBitsToFloat(target[targetOffset + 2]))) return false;

            // Color, UV0 and lightmap must be bit-identical after vertex permutation.
            for (int component = 3; component <= 6; component++) {
                if (base[sourceOffset + component] != target[targetOffset + component]) return false;
            }
            if (!closePackedNormal(rotatePackedNormal(base[sourceOffset + 7], rotation), target[targetOffset + 7])) {
                return false;
            }
        }
        return true;
    }

    private static float rotatedX(float x, float z, int rotation) {
        switch (rotation & 3) {
            case 1: return 1.0f - z;
            case 2: return 1.0f - x;
            case 3: return z;
            default: return x;
        }
    }

    private static float rotatedZ(float x, float z, int rotation) {
        switch (rotation & 3) {
            case 1: return x;
            case 2: return 1.0f - z;
            case 3: return 1.0f - x;
            default: return z;
        }
    }

    private static int rotatePackedNormal(int packed, int rotation) {
        int x = (byte) (packed & 0xFF);
        int y = (byte) ((packed >>> 8) & 0xFF);
        int z = (byte) ((packed >>> 16) & 0xFF);
        int rx;
        int rz;
        switch (rotation & 3) {
            case 1: rx = clampSignedByte(-z); rz = clampSignedByte(x); break;
            case 2: rx = clampSignedByte(-x); rz = clampSignedByte(-z); break;
            case 3: rx = clampSignedByte(z); rz = clampSignedByte(-x); break;
            default: rx = x; rz = z; break;
        }
        return (packed & 0xFF000000)
                | (rx & 0xFF)
                | ((y & 0xFF) << 8)
                | ((rz & 0xFF) << 16);
    }

    private static int clampSignedByte(int value) {
        return value < -128 ? -128 : Math.min(value, 127);
    }

    private static boolean closeFloat(float expected, float actual) {
        if (Float.floatToRawIntBits(expected) == Float.floatToRawIntBits(actual)) return true;
        return Math.abs(expected - actual) <= 0.00002f;
    }

    private static boolean closePackedNormal(int expected, int actual) {
        if ((expected & 0xFF000000) != (actual & 0xFF000000)) return false;
        for (int shift = 0; shift <= 16; shift += 8) {
            int a = (byte) ((expected >>> shift) & 0xFF);
            int b = (byte) ((actual >>> shift) & 0xFF);
            if (Math.abs(a - b) > 1) return false;
        }
        return true;
    }

    private static long intArrayFootprint(int length) {
        long bytes = 16L + 4L * length;
        return (bytes + 7L) & ~7L;
    }

    private static void startRotationStatsWriter() {
        if (!ROTATION_STATS_THREAD_STARTED.compareAndSet(false, true)) return;
        Thread writer = new Thread(() -> {
            long previous = Long.MIN_VALUE;
            int stable = 0;
            try {
                while (stable < 5) {
                    Thread.sleep(2000L);
                    long marker = ROTATION_QUADS_COMPRESSED.get() * 31L + ROTATION_LAZY_MATERIALIZATIONS.get();
                    writeRotationStats();
                    if (marker == previous) stable++; else stable = 0;
                    previous = marker;
                }
                writeRotationStats();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            } catch (Throwable t) {
                System.err.println("[BattleArmory][RotationCompression] stats writer failed: " + t);
            } finally {
                ROTATION_STATS_THREAD_STARTED.set(false);
            }
        }, "BattleArmory-RotationCompressionStats");
        writer.setDaemon(true);
        writer.start();
    }

    private static void writeRotationStats() {
        long shared = ROTATION_BYTES_SHARED.get();
        long materialized = ROTATION_BYTES_MATERIALIZED.get();
        long effective = Math.max(0L, shared - materialized);
        String stats = "Battle Armory rotated-quad storage diagnostics 0017\n"
                + "base_models=" + ROTATION_BASE_MODELS.get() + "\n"
                + "quads_compressed=" + ROTATION_QUADS_COMPRESSED.get() + "\n"
                + "quads_unmatched=" + ROTATION_QUADS_UNMATCHED.get() + "\n"
                + "lazy_materializations=" + ROTATION_LAZY_MATERIALIZATIONS.get() + "\n"
                + "bytes_shared=" + shared + "\n"
                + "bytes_materialized=" + materialized + "\n"
                + "effective_bytes_saved=" + effective + "\n";
        try {
            Files.writeString(Path.of(System.getProperty("user.dir"), "battlearmory-rotation-compression-stats.txt"),
                    stats, StandardCharsets.UTF_8);
        } catch (Throwable t) {
            System.err.println("[BattleArmory][RotationCompression] stats write failed: " + t);
        }
    }

    private static void ensureWholeBakeInitialized(FamilyState state, Object model, Object context,
                                                   Object builder, Object modelState) throws Exception {
        if (state.wholeBakeInitialized) return;
        synchronized (state) {
            if (state.wholeBakeInitialized) return;
            Class<?> modelClass = model.getClass();
            state.wholeBakeryField = findField(modelClass, "BAKERY");
            state.wholeSettingsField = findField(modelClass, "settings");
            state.wholeElementsField = findField(modelClass, "elements");
            state.wholeResolutionField = findField(modelClass, "resolution");

            Object settings = state.wholeSettingsField.get(model);
            Class<?> settingsClass = settings.getClass();
            state.wholeSettingLocationField = findField(settingsClass, "modelLocation");
            state.wholeSettingScaleField = findField(settingsClass, "scale");
            state.wholeSettingFlipVField = findField(settingsClass, "flipV");
            state.wholeSettingMaterialField = findField(settingsClass, "material");

            List<?> elements = (List<?>) state.wholeElementsField.get(model);
            Object elementSample = null;
            for (Object element : elements) {
                if (element != null) { elementSample = element; break; }
            }
            if (elementSample == null) throw new IllegalStateException(state.id + " BlockbenchModel has no elements");
            Class<?> elementClass = elementSample.getClass();
            state.wholeElementNameField = findField(elementClass, "name");
            state.wholeElementTypeField = findField(elementClass, "type");
            state.wholeElementFacesField = findField(elementClass, "faces");

            Object bakery = state.wholeBakeryField.get(model);
            for (Method method : bakery.getClass().getDeclaredMethods()) {
                if (method.getName().equals("bakeQuad") && method.getParameterCount() == 8) {
                    method.setAccessible(true);
                    state.wholeBakeQuadHandle = MethodHandles.lookup().unreflect(method);
                    break;
                }
            }
            if (state.wholeBakeQuadHandle == null) throw new NoSuchMethodException(state.id + " BlockbenchBakery.bakeQuad");

            ClassLoader loader = modelClass.getClassLoader();
            Class<?> geometryHelper = Class.forName(
                    "net.minecraftforge.client.model.geometry.UnbakedGeometryHelper", false, loader);
            for (Method method : geometryHelper.getDeclaredMethods()) {
                if (Modifier.isStatic(method.getModifiers()) && method.getName().equals("resolveDirtyMaterial")
                        && method.getParameterCount() == 2) {
                    method.setAccessible(true);
                    state.resolveDirtyMaterialHandle = MethodHandles.lookup().unreflect(method);
                    break;
                }
            }
            if (state.resolveDirtyMaterialHandle == null) throw new NoSuchMethodException("UnbakedGeometryHelper.resolveDirtyMaterial");

            Class<?> modelBuilder = Class.forName("net.minecraftforge.client.model.IModelBuilder", false, loader);
            for (Method method : modelBuilder.getMethods()) {
                if (method.getName().equals("addUnculledFace") && method.getParameterCount() == 1) {
                    method.setAccessible(true);
                    state.addUnculledFaceHandle = MethodHandles.lookup().unreflect(method);
                    break;
                }
            }
            if (state.addUnculledFaceHandle == null) throw new NoSuchMethodException("IModelBuilder.addUnculledFace");

            for (Method method : modelState.getClass().getMethods()) {
                if ((method.getName().equals("m_6189_") || method.getName().equals("getRotation"))
                        && method.getParameterCount() == 0) {
                    method.setAccessible(true);
                    state.modelStateRotationHandle = MethodHandles.lookup().unreflect(method);
                    break;
                }
            }
            if (state.modelStateRotationHandle == null) {
                Class<?> modelStateClass = Class.forName("net.minecraft.client.resources.model.ModelState", false, loader);
                for (Method method : modelStateClass.getMethods()) {
                    if ((method.getName().equals("m_6189_") || method.getName().equals("getRotation"))
                            && method.getParameterCount() == 0) {
                        method.setAccessible(true);
                        state.modelStateRotationHandle = MethodHandles.lookup().unreflect(method);
                        break;
                    }
                }
            }
            if (state.modelStateRotationHandle == null) throw new NoSuchMethodException("ModelState.getRotation/m_6189_");
            state.wholeBakeInitialized = true;
        }
    }

    private static void startWholeModelStatsWriter() {
        if (!WHOLE_MODEL_STATS_THREAD_STARTED.compareAndSet(false, true)) return;
        Thread writer = new Thread(() -> {
            long previous = -1L;
            int stable = 0;
            try {
                while (stable < 5) {
                    Thread.sleep(2000L);
                    long now = WHOLE_MODEL_CACHE_HITS.get() + WHOLE_MODEL_CACHE_MISSES.get();
                    writeWholeModelStats();
                    if (now == previous) stable++; else stable = 0;
                    previous = now;
                }
                writeWholeModelStats();
                releaseBakeScratchCaches();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            } catch (Throwable t) {
                System.err.println("[BattleArmory][ModelCache] writer failed: " + t);
            }
        }, "BattleArmory-ModelCacheStats");
        writer.setDaemon(true);
        writer.start();
    }

    private static void releaseBakeScratchCaches() {
        long wholeEntries = 0L;
        long rotationEntries = 0L;
        for (FamilyState state : FAMILIES.values()) {
            wholeEntries += state.wholeBakedModels.size();
            rotationEntries += state.rotationBaseVertices.size();
            state.wholeBakedModels.clear();
            state.rotationBaseVertices.clear();
        }
        System.out.println("[BattleArmory][BakeScratch] released post-bake caches: wholeModels="
                + wholeEntries + ", rotationBases=" + rotationEntries);
    }

    private static void writeWholeModelStats() {
        long hits = WHOLE_MODEL_CACHE_HITS.get();
        long misses = WHOLE_MODEL_CACHE_MISSES.get();
        long total = hits + misses;
        double rate = total == 0L ? 0.0 : 100.0 * hits / total;
        String stats = "Battle Armory whole-model cache diagnostics 0014\n"
                + "model_cache_hits=" + hits + "\n"
                + "model_cache_misses=" + misses + "\n"
                + "model_cache_hit_rate_percent=" + String.format(java.util.Locale.ROOT, "%.2f", rate) + "\n"
                + "quads_built=" + WHOLE_MODEL_QUADS_BUILT.get() + "\n"
                + "quads_replayed=" + WHOLE_MODEL_QUADS_REPLAYED.get() + "\n";
        try {
            Files.writeString(Path.of(System.getProperty("user.dir"), "battlearmory-model-cache-stats.txt"),
                    stats, StandardCharsets.UTF_8);
        } catch (Throwable t) {
            System.err.println("[BattleArmory][ModelCache] snapshot write failed: " + t);
        }
    }

    /** Session-stable BBModels parsed from the immutable mod jar, keyed by jar path. */
    public static Object parsedRegistryModelGet(String family, String path) {
        FamilyState state = FAMILIES.get(family);
        if (state == null || path == null) return null;
        return state.registryParsedModels.get(path);
    }

    /** Publish one canonical registry BBModel for every unique .bbmodel path. */
    public static Object parsedRegistryModelPut(Object model, String family, String path) {
        if (model == null || path == null) return model;
        FamilyState state = FAMILIES.get(family);
        if (state == null) return model;
        Object prior = state.registryParsedModels.putIfAbsent(path, model);
        return prior != null ? prior : model;
    }

    /**
     * Resource-pack model loading used to parse the same .bbmodel once for every
     * Decocraft block/model JSON that referenced it. Parse each resource location
     * once per reload and share the immutable BBModel graph between BlockbenchModels.
     */
    public static Object sharedResourceModel(String family, String modelKey, Object reader) {
        if (modelKey == null || reader == null) throw new IllegalArgumentException("modelKey/reader");
        FamilyState state = FAMILIES.get(family);
        if (state == null) throw new IllegalArgumentException("Unknown Decocraft family: " + family);

        Object cached = state.resourceParsedModels.get(modelKey);
        if (cached != null) {
            closeQuietly(reader);
            return cached;
        }

        try {
            ClassLoader classLoader = Thread.currentThread().getContextClassLoader();
            if (classLoader == null) classLoader = DecocraftCacheRuntime.class.getClassLoader();
            String loaderName = family.equals("decocraft_nature")
                    ? "com.razz.decocraft_nature.models.bbmodel.BBModelLoader"
                    : "com.razz.decocraft.models.bbmodel.BBModelLoader";
            Class<?> loaderClass = Class.forName(loaderName, false, classLoader);
            Object loader = loaderClass.getConstructor().newInstance();
            Method loadModel = loaderClass.getMethod("loadModel", java.io.Reader.class);
            Object parsed;
            try {
                parsed = loadModel.invoke(loader, reader);
            } catch (InvocationTargetException e) {
                Throwable cause = e.getCause() != null ? e.getCause() : e;
                throw cause;
            }
            if (parsed == null) throw new IllegalStateException("BBModelLoader returned null for " + modelKey);
            Object prior = state.resourceParsedModels.putIfAbsent(modelKey, parsed);
            return prior != null ? prior : parsed;
        } catch (Throwable t) {
            throw new RuntimeException("Battle Armory shared BBModel load failed for " + family + ":" + modelKey, t);
        } finally {
            closeQuietly(reader);
        }
    }

    private static void closeQuietly(Object object) {
        if (!(object instanceof AutoCloseable)) return;
        try {
            ((AutoCloseable) object).close();
        } catch (Exception ignored) {}
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

    private static final class RotationBaseKey {
        final String modelPath;
        final int scaleBits;
        final boolean flipV;
        final String material;
        final Object sprite;
        final int hash;

        RotationBaseKey(String modelPath, int scaleBits, boolean flipV, String material, Object sprite) {
            this.modelPath = modelPath;
            this.scaleBits = scaleBits;
            this.flipV = flipV;
            this.material = material;
            this.sprite = sprite;
            int h = modelPath == null ? 0 : modelPath.hashCode();
            h = 31 * h + scaleBits;
            h = 31 * h + (flipV ? 1 : 0);
            h = 31 * h + (material == null ? 0 : material.hashCode());
            h = 31 * h + System.identityHashCode(sprite);
            this.hash = h;
        }

        @Override public int hashCode() { return hash; }

        @Override public boolean equals(Object other) {
            if (this == other) return true;
            if (!(other instanceof RotationBaseKey)) return false;
            RotationBaseKey o = (RotationBaseKey) other;
            return scaleBits == o.scaleBits && flipV == o.flipV
                    && java.util.Objects.equals(modelPath, o.modelPath)
                    && java.util.Objects.equals(material, o.material)
                    && sprite == o.sprite;
        }
    }

    private static final class WholeModelKey {
        final String modelPath;
        final int scaleBits;
        final boolean flipV;
        final String material;
        final Object rotation;
        final Object sprite;
        final int hash;

        WholeModelKey(String modelPath, int scaleBits, boolean flipV, String material, Object rotation, Object sprite) {
            this.modelPath = modelPath;
            this.scaleBits = scaleBits;
            this.flipV = flipV;
            this.material = material;
            this.rotation = rotation;
            this.sprite = sprite;
            int h = modelPath == null ? 0 : modelPath.hashCode();
            h = 31 * h + scaleBits;
            h = 31 * h + (flipV ? 1 : 0);
            h = 31 * h + (material == null ? 0 : material.hashCode());
            h = 31 * h + System.identityHashCode(rotation);
            h = 31 * h + System.identityHashCode(sprite);
            this.hash = h;
        }

        @Override public int hashCode() { return hash; }

        @Override public boolean equals(Object other) {
            if (this == other) return true;
            if (!(other instanceof WholeModelKey)) return false;
            WholeModelKey o = (WholeModelKey) other;
            return scaleBits == o.scaleBits && flipV == o.flipV
                    && java.util.Objects.equals(modelPath, o.modelPath)
                    && java.util.Objects.equals(material, o.material)
                    && rotation == o.rotation && sprite == o.sprite;
        }
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
        final ConcurrentHashMap<String, Object> registryParsedModels = new ConcurrentHashMap<>();
        final ConcurrentHashMap<String, Object> resourceParsedModels = new ConcurrentHashMap<>();
        final ConcurrentHashMap<WholeModelKey, Object[]> wholeBakedModels = new ConcurrentHashMap<>();
        final ConcurrentHashMap<RotationBaseKey, int[][]> rotationBaseVertices = new ConcurrentHashMap<>();
        volatile boolean wholeBakeInitialized;
        long wholeCacheHits;
        long wholeCacheMisses;
        Field wholeBakeryField;
        Field wholeSettingsField;
        Field wholeElementsField;
        Field wholeResolutionField;
        Field wholeSettingLocationField;
        Field wholeSettingScaleField;
        Field wholeSettingFlipVField;
        Field wholeSettingMaterialField;
        Field wholeElementNameField;
        Field wholeElementTypeField;
        Field wholeElementFacesField;
        volatile Field wholeFaceUvField;
        MethodHandle wholeBakeQuadHandle;
        MethodHandle resolveDirtyMaterialHandle;
        MethodHandle addUnculledFaceHandle;
        MethodHandle modelStateRotationHandle;
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
