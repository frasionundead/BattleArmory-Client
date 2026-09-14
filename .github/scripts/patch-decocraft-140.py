from pathlib import Path

runtime = Path('tools/decocraft-cache/src/com/battlearmory/decocraftcache/DecocraftCacheRuntime.java')
text = runtime.read_text(encoding='utf-8')

fields_anchor = '    private static final IdentityHashMap<Object, Object> FACING_QUATERNIONS = new IdentityHashMap<>();\n\n'
fields = '''    private static final IdentityHashMap<Object, Object> FACING_QUATERNIONS = new IdentityHashMap<>();

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

'''
if 'QUAD_MAX_ENTRIES' not in text:
    if fields_anchor not in text:
        raise SystemExit('runtime quad fields anchor not found')
    text = text.replace(fields_anchor, fields, 1)

old_clear = '''    /** Drop resource-derived caches before Minecraft starts a resource-pack reload. */
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
        }
        System.out.println("[BattleArmory] Resource reload: cleared Decocraft caches (models=" + models + ", materials=" + materials + ", resourceModels=" + resourceModels + ").");
    }
'''
new_clear = '''    /** Drop resource-derived caches before Minecraft starts a resource-pack reload. */
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
        System.out.println("[BattleArmory] Resource reload: cleared Decocraft caches (models=" + models
                + ", materials=" + materials + ", resourceModels=" + resourceModels
                + ", quads=" + quadEntries + ", quadHits=" + hits + ", quadMisses=" + misses + ").");
    }
'''
if 'quadEntries = quadSize' not in text:
    if old_clear not in text:
        raise SystemExit('runtime clearForResourceReload anchor not found')
    text = text.replace(old_clear, new_clear, 1)

method_anchor = '    /** Session-stable BBModels parsed from the immutable mod jar, keyed by jar path. */\n'
quad_methods = '''    /**
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

'''
if 'public static Object canonicalBakedQuad' not in text:
    if method_anchor not in text:
        raise SystemExit('runtime method anchor not found')
    text = text.replace(method_anchor, quad_methods + method_anchor, 1)

runtime.write_text(text, encoding='utf-8')

coremod = Path('tools/decocraft-cache/coremods/battlearmory_decocraft_cache.js')
js = coremod.read_text(encoding='utf-8')

if "var BAKED_QUAD = 'net/minecraft/client/renderer/block/model/BakedQuad';" not in js:
    js = js.replace(
        "var DIRECTION = 'net/minecraft/core/Direction';\n",
        "var DIRECTION = 'net/minecraft/core/Direction';\nvar BAKED_QUAD = 'net/minecraft/client/renderer/block/model/BakedQuad';\n",
        1)

family_pairs = [
    (
        "        bbModelLoader: 'com/razz/decocraft/models/bbmodel/BBModelLoader',\n",
        "        bbModelLoader: 'com/razz/decocraft/models/bbmodel/BBModelLoader',\n        bakery: 'com/razz/decocraft/models/bbmodel/BlockbenchBakery',\n        element: 'com/razz/decocraft/models/bbmodel/BBModelParts$Element',\n"
    ),
    (
        "        bbModelLoader: 'com/razz/decocraft_nature/models/bbmodel/BBModelLoader',\n",
        "        bbModelLoader: 'com/razz/decocraft_nature/models/bbmodel/BBModelLoader',\n        bakery: 'com/razz/decocraft_nature/models/bbmodel/BlockbenchBakery',\n        element: 'com/razz/decocraft_nature/models/bbmodel/BBModelParts$Element',\n"
    )
]
for old, new in family_pairs:
    if new not in js:
        if old not in js:
            raise SystemExit('coremod family anchor not found: ' + old.strip())
        js = js.replace(old, new, 1)

quad_fn_anchor = 'function initializeCoreMod() {\n'
quad_fn = '''function addQuadDedupe(result, f) {
    result['battlearmory_' + f.id + '_baked_quad_dedupe'] = {
        target: { type: 'CLASS', name: dotted(f.bakery) },
        transformer: function(classNode) {
            var methods = classNode.methods.iterator();
            var method = null;
            while (methods.hasNext()) {
                var candidate = methods.next();
                if (candidate.name === 'bakeQuad' && candidate.desc.endsWith('L' + BAKED_QUAD + ';')) {
                    method = candidate;
                    break;
                }
            }
            if (method === null) throw 'Battle Armory ' + f.id + ' quad dedupe: bakeQuad not found';

            var start = null;
            var ctor = null;
            var scan = method.instructions.getFirst();
            while (scan !== null) {
                if (start === null && scan.getOpcode() === Opcodes.NEW && scan.desc === BAKED_QUAD) {
                    start = scan;
                } else if (start !== null && scan.getOpcode() === Opcodes.INVOKESPECIAL &&
                    scan.owner === BAKED_QUAD && scan.name === '<init>' &&
                    scan.desc === '([IILnet/minecraft/core/Direction;Lnet/minecraft/client/renderer/texture/TextureAtlasSprite;Z)V') {
                    ctor = scan;
                    break;
                }
                scan = scan.getNext();
            }
            if (start === null || ctor === null) {
                throw 'Battle Armory ' + f.id + ' quad dedupe: BakedQuad construction sequence not found';
            }

            // bakeQuad locals are stable in both Decocraft 3.0.4 and Nature 1.0.4:
            // vertices=9, calculated direction=10, sprite=6, source element=1.
            var replacement = new InsnList();
            replacement.add(new VarInsnNode(Opcodes.ALOAD, 9));
            replacement.add(new InsnNode(Opcodes.ICONST_M1));
            replacement.add(new VarInsnNode(Opcodes.ALOAD, 10));
            replacement.add(new VarInsnNode(Opcodes.ALOAD, 6));
            replacement.add(new VarInsnNode(Opcodes.ALOAD, 1));
            replacement.add(new FieldInsnNode(Opcodes.GETFIELD, f.element, 'shade', 'Z'));
            replacement.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC, HELPER, 'canonicalBakedQuad',
                '([IILjava/lang/Object;Ljava/lang/Object;Z)Ljava/lang/Object;', false));
            replacement.add(new TypeInsnNode(Opcodes.CHECKCAST, BAKED_QUAD));
            method.instructions.insertBefore(start, replacement);
            removeRange(method.instructions, start, ctor);
            method.maxStack = Math.max(method.maxStack, 5);
            return classNode;
        }
    };
}

'''
if 'function addQuadDedupe' not in js:
    if quad_fn_anchor not in js:
        raise SystemExit('coremod initialize anchor not found')
    js = js.replace(quad_fn_anchor, quad_fn + quad_fn_anchor, 1)

old_loop = '''    for (var i = 0; i < FAMILIES.length; i++) {
        addFamily(result, FAMILIES[i]);
        addMemoryDedupe(result, FAMILIES[i]);
    }
'''
new_loop = '''    for (var i = 0; i < FAMILIES.length; i++) {
        addFamily(result, FAMILIES[i]);
        addMemoryDedupe(result, FAMILIES[i]);
        addQuadDedupe(result, FAMILIES[i]);
    }
'''
if 'addQuadDedupe(result, FAMILIES[i]);' not in js:
    if old_loop not in js:
        raise SystemExit('coremod loop anchor not found')
    js = js.replace(old_loop, new_loop, 1)

coremod.write_text(js, encoding='utf-8')

mods = Path('tools/decocraft-cache/META-INF/mods.toml')
mt = mods.read_text(encoding='utf-8')
if 'version="1.4.0"' not in mt:
    if 'version="1.3.0"' not in mt:
        raise SystemExit('mods.toml 1.3.0 version anchor not found')
    mt = mt.replace('version="1.3.0"', 'version="1.4.0"', 1)
mods.write_text(mt, encoding='utf-8')
