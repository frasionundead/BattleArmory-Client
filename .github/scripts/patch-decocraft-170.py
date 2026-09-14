from pathlib import Path

runtime_path = Path('tools/decocraft-cache/src/com/battlearmory/decocraftcache/DecocraftCacheRuntime.java')
coremod_path = Path('tools/decocraft-cache/coremods/battlearmory_decocraft_cache.js')
mods_path = Path('tools/decocraft-cache/META-INF/mods.toml')

runtime = runtime_path.read_text(encoding='utf-8')
coremod = coremod_path.read_text(encoding='utf-8')
mods = mods_path.read_text(encoding='utf-8')

def repl(text, old, new, label):
    if old not in text:
        raise SystemExit(f'patch point not found: {label}')
    return text.replace(old, new, 1)

# ---- Runtime: counters / diagnostics -------------------------------------------------
runtime = repl(runtime,
'''    private static final AtomicLong WHOLE_MODEL_QUADS_REPLAYED = new AtomicLong();
    private static final AtomicBoolean WHOLE_MODEL_STATS_THREAD_STARTED = new AtomicBoolean();
''',
'''    private static final AtomicLong WHOLE_MODEL_QUADS_REPLAYED = new AtomicLong();
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
''', 'rotation counters')

# Clear new resource-derived references/counters on F3+T.
runtime = repl(runtime,
'''            state.resourceParsedModels.clear();
            state.wholeBakedModels.clear();
            state.wholeCacheHits = 0L;
''',
'''            state.resourceParsedModels.clear();
            state.wholeBakedModels.clear();
            state.rotationBaseVertices.clear();
            state.wholeCacheHits = 0L;
''', 'clear rotation bases')

runtime = repl(runtime,
'''        DECOCRAFT_BAKE_CALLS.set(0L);
        NATURE_BAKE_CALLS.set(0L);
        BAKE_STATS_THREAD_STARTED.set(false);
''',
'''        DECOCRAFT_BAKE_CALLS.set(0L);
        NATURE_BAKE_CALLS.set(0L);
        BAKE_STATS_THREAD_STARTED.set(false);
        ROTATION_BASE_MODELS.set(0L);
        ROTATION_QUADS_COMPRESSED.set(0L);
        ROTATION_QUADS_UNMATCHED.set(0L);
        ROTATION_LAZY_MATERIALIZATIONS.set(0L);
        ROTATION_BYTES_SHARED.set(0L);
        ROTATION_BYTES_MATERIALIZED.set(0L);
        ROTATION_STATS_THREAD_STARTED.set(false);
''', 'reset rotation stats')

# Build quads normally once so correctness stays stock, then replace only their retained
# vertex payload when a lossless quarter-turn/permutation relationship is proven.
runtime = repl(runtime,
'''                    Object quad = state.wholeBakeQuadHandle.invoke(
                            bakery, element, settings, root, uv, resolution, sprite, direction, modelState);
                    state.addUnculledFaceHandle.invoke(builder, quad);
                    built.add(quad);
''',
'''                    Object quad = state.wholeBakeQuadHandle.invoke(
                            bakery, element, settings, root, uv, resolution, sprite, direction, modelState);
                    built.add(quad);
''', 'defer builder add')

runtime = repl(runtime,
'''            Object[] baked = built.toArray(new Object[0]);
            Object[] prior = state.wholeBakedModels.putIfAbsent(key, baked);
''',
'''            Object[] baked = built.toArray(new Object[0]);
            RotationBaseKey rotationBaseKey = new RotationBaseKey(modelPath, scaleBits, flipV, material, sprite);
            compressRotationVariant(state, rotationBaseKey, baked);
            for (Object quad : baked) state.addUnculledFaceHandle.invoke(builder, quad);

            Object[] prior = state.wholeBakedModels.putIfAbsent(key, baked);
''', 'compress before builder add')

# Insert compression implementation before whole-bake reflection bootstrap.
marker = '''    private static void ensureWholeBakeInitialized(FamilyState state, Object model, Object context,
'''
methods = r'''    /**
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

'''
runtime = repl(runtime, marker, methods + marker, 'insert rotation compression methods')

# Add rotation-base key beside the existing whole-model key.
marker = '''    private static final class WholeModelKey {
'''
key_class = r'''    private static final class RotationBaseKey {
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

'''
runtime = repl(runtime, marker, key_class + marker, 'rotation base key')

runtime = repl(runtime,
'''        final ConcurrentHashMap<WholeModelKey, Object[]> wholeBakedModels = new ConcurrentHashMap<>();
        volatile boolean wholeBakeInitialized;
''',
'''        final ConcurrentHashMap<WholeModelKey, Object[]> wholeBakedModels = new ConcurrentHashMap<>();
        final ConcurrentHashMap<RotationBaseKey, int[][]> rotationBaseVertices = new ConcurrentHashMap<>();
        volatile boolean wholeBakeInitialized;
''', 'family rotation base map')

# ---- Coremod: augment BakedQuad with compact rotation metadata + lazy payload --------
insert_before = '''function initializeCoreMod() {
'''
core_fn = r'''function addBakedQuadRotationStorage(result) {
    result['battlearmory_baked_quad_rotation_storage'] = {
        target: { type: 'CLASS', name: dotted(BAKED_QUAD) },
        transformer: function(classNode) {
            var methods = classNode.methods.iterator();
            var getter = null;
            var verticesFieldName = null;
            while (methods.hasNext()) {
                var candidate = methods.next();
                if (candidate.desc !== '()[I' || (candidate.access & Opcodes.ACC_STATIC) !== 0) continue;
                var scan = candidate.instructions.getFirst();
                while (scan !== null) {
                    if (scan.getOpcode() === Opcodes.GETFIELD && scan.owner === BAKED_QUAD && scan.desc === '[I') {
                        getter = candidate;
                        verticesFieldName = scan.name;
                        break;
                    }
                    scan = scan.getNext();
                }
                if (getter !== null) break;
            }
            if (getter === null || verticesFieldName === null) {
                throw 'Battle Armory rotated-quad storage: BakedQuad int[] getter/field not found';
            }

            var fields = classNode.fields.iterator();
            var verticesField = null;
            var havePlan = false;
            var haveMaterialized = false;
            while (fields.hasNext()) {
                var field = fields.next();
                if (field.name === verticesFieldName && field.desc === '[I') verticesField = field;
                if (field.name === 'ba$rotationPlan') havePlan = true;
                if (field.name === 'ba$materialized') haveMaterialized = true;
            }
            if (verticesField === null) throw 'Battle Armory rotated-quad storage: vertices field node not found';
            verticesField.access = verticesField.access & ~Opcodes.ACC_FINAL;
            if (!havePlan) classNode.fields.add(new FieldNode(Opcodes.ACC_PRIVATE, 'ba$rotationPlan', 'B', null, null));
            if (!haveMaterialized) classNode.fields.add(new FieldNode(Opcodes.ACC_PRIVATE | Opcodes.ACC_TRANSIENT, 'ba$materialized', '[I', null, null));

            var haveCompress = false;
            methods = classNode.methods.iterator();
            while (methods.hasNext()) {
                var existing = methods.next();
                if (existing.name === 'ba$compress' && existing.desc === '([IB)V') { haveCompress = true; break; }
            }
            if (!haveCompress) {
                var compress = new MethodNode(Opcodes.ACC_PUBLIC, 'ba$compress', '([IB)V', null, null);
                compress.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
                compress.instructions.add(new VarInsnNode(Opcodes.ALOAD, 1));
                compress.instructions.add(new FieldInsnNode(Opcodes.PUTFIELD, BAKED_QUAD, verticesFieldName, '[I'));
                compress.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
                compress.instructions.add(new VarInsnNode(Opcodes.ILOAD, 2));
                compress.instructions.add(new FieldInsnNode(Opcodes.PUTFIELD, BAKED_QUAD, 'ba$rotationPlan', 'B'));
                compress.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
                compress.instructions.add(new InsnNode(Opcodes.ACONST_NULL));
                compress.instructions.add(new FieldInsnNode(Opcodes.PUTFIELD, BAKED_QUAD, 'ba$materialized', '[I'));
                compress.instructions.add(new InsnNode(Opcodes.RETURN));
                compress.maxStack = 2;
                compress.maxLocals = 3;
                classNode.methods.add(compress);
            }

            getter.instructions.clear();
            getter.tryCatchBlocks.clear();
            if (getter.localVariables !== null) getter.localVariables.clear();
            var normal = new LabelNode();
            var cached = new LabelNode();
            var code = new InsnList();
            code.add(new VarInsnNode(Opcodes.ALOAD, 0));
            code.add(new FieldInsnNode(Opcodes.GETFIELD, BAKED_QUAD, 'ba$rotationPlan', 'B'));
            code.add(new JumpInsnNode(Opcodes.IFEQ, normal));
            code.add(new VarInsnNode(Opcodes.ALOAD, 0));
            code.add(new FieldInsnNode(Opcodes.GETFIELD, BAKED_QUAD, 'ba$materialized', '[I'));
            code.add(new InsnNode(Opcodes.DUP));
            code.add(new JumpInsnNode(Opcodes.IFNONNULL, cached));
            code.add(new InsnNode(Opcodes.POP));
            code.add(new VarInsnNode(Opcodes.ALOAD, 0));
            code.add(new VarInsnNode(Opcodes.ALOAD, 0));
            code.add(new FieldInsnNode(Opcodes.GETFIELD, BAKED_QUAD, verticesFieldName, '[I'));
            code.add(new VarInsnNode(Opcodes.ALOAD, 0));
            code.add(new FieldInsnNode(Opcodes.GETFIELD, BAKED_QUAD, 'ba$rotationPlan', 'B'));
            code.add(new MethodInsnNode(Opcodes.INVOKESTATIC, HELPER, 'materializeRotatedVertices', '([IB)[I', false));
            code.add(new InsnNode(Opcodes.DUP_X1));
            code.add(new FieldInsnNode(Opcodes.PUTFIELD, BAKED_QUAD, 'ba$materialized', '[I'));
            code.add(new InsnNode(Opcodes.ARETURN));
            code.add(cached);
            code.add(new InsnNode(Opcodes.ARETURN));
            code.add(normal);
            code.add(new VarInsnNode(Opcodes.ALOAD, 0));
            code.add(new FieldInsnNode(Opcodes.GETFIELD, BAKED_QUAD, verticesFieldName, '[I'));
            code.add(new InsnNode(Opcodes.ARETURN));
            getter.instructions.add(code);
            getter.maxStack = 3;
            getter.maxLocals = 1;
            return classNode;
        }
    };
}

'''
coremod = repl(coremod, insert_before, core_fn + insert_before, 'insert BakedQuad storage transformer')

coremod = repl(coremod,
'''    for (var i = 0; i < FAMILIES.length; i++) {
        addFamily(result, FAMILIES[i]);
        addMemoryDedupe(result, FAMILIES[i]);
        addWholeModelCache(result, FAMILIES[i]);
    }

    // F3+T / resource-pack reload can temporarily coexist with the old model graph.
''',
'''    for (var i = 0; i < FAMILIES.length; i++) {
        addFamily(result, FAMILIES[i]);
        addMemoryDedupe(result, FAMILIES[i]);
        addWholeModelCache(result, FAMILIES[i]);
    }
    addBakedQuadRotationStorage(result);

    // F3+T / resource-pack reload can temporarily coexist with the old model graph.
''', 'enable BakedQuad storage transformer')

# Version bump.
if 'version="1.5.0"' not in mods:
    raise SystemExit('expected known-good mods.toml version 1.5.0')
mods = mods.replace('version="1.5.0"', 'version="1.7.0"', 1)

runtime_path.write_text(runtime, encoding='utf-8')
coremod_path.write_text(coremod, encoding='utf-8')
mods_path.write_text(mods, encoding='utf-8')
print('patched Decocraft optimizer v1.7.0')
