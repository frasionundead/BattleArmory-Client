from pathlib import Path

runtime = Path('tools/decocraft-cache/src/com/battlearmory/decocraftcache/DecocraftCacheRuntime.java')
text = runtime.read_text(encoding='utf-8')

# Whole-model cache counters.
counter_anchor = '    private static final AtomicBoolean BAKE_STATS_THREAD_STARTED = new AtomicBoolean();\n'
if 'WHOLE_MODEL_CACHE_HITS' not in text:
    text = text.replace(counter_anchor, counter_anchor + '''    private static final AtomicLong WHOLE_MODEL_CACHE_HITS = new AtomicLong();
    private static final AtomicLong WHOLE_MODEL_CACHE_MISSES = new AtomicLong();
    private static final AtomicLong WHOLE_MODEL_QUADS_BUILT = new AtomicLong();
    private static final AtomicLong WHOLE_MODEL_QUADS_REPLAYED = new AtomicLong();
    private static final AtomicBoolean WHOLE_MODEL_STATS_THREAD_STARTED = new AtomicBoolean();
''', 1)

# Clear the whole-model cache before a resource reload.
clear_anchor = '            state.resourceParsedModels.clear();\n'
if 'state.wholeBakedModels.clear();' not in text:
    text = text.replace(clear_anchor, clear_anchor + '''            state.wholeBakedModels.clear();
            state.wholeCacheHits = 0L;
            state.wholeCacheMisses = 0L;
''', 1)

method_anchor = '    /** Session-stable BBModels parsed from the immutable mod jar, keyed by jar path. */\n'
methods = r'''    /**
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
                    state.addUnculledFaceHandle.invoke(builder, quad);
                    built.add(quad);
                }
            }

            Object[] baked = built.toArray(new Object[0]);
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
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            } catch (Throwable t) {
                System.err.println("[BattleArmory][ModelCache] writer failed: " + t);
            }
        }, "BattleArmory-ModelCacheStats");
        writer.setDaemon(true);
        writer.start();
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

'''
if 'public static void addQuadsCached' not in text:
    if method_anchor not in text:
        raise SystemExit('runtime whole-model method anchor not found')
    text = text.replace(method_anchor, methods + method_anchor, 1)

# Add cache/reflection state to FamilyState.
family_anchor = '        final ConcurrentHashMap<String, Object> resourceParsedModels = new ConcurrentHashMap<>();\n'
family_fields = '''        final ConcurrentHashMap<String, Object> resourceParsedModels = new ConcurrentHashMap<>();
        final ConcurrentHashMap<WholeModelKey, Object[]> wholeBakedModels = new ConcurrentHashMap<>();
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
'''
if 'wholeBakedModels' not in text:
    if family_anchor not in text:
        raise SystemExit('runtime FamilyState anchor not found')
    text = text.replace(family_anchor, family_fields, 1)

node_anchor = '    private static final class NodeState {\n'
key_class = '''    private static final class WholeModelKey {
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

'''
if 'private static final class WholeModelKey' not in text:
    if node_anchor not in text:
        raise SystemExit('runtime key class anchor not found')
    text = text.replace(node_anchor, key_class + node_anchor, 1)

runtime.write_text(text, encoding='utf-8')

coremod = Path('tools/decocraft-cache/coremods/battlearmory_decocraft_cache.js')
js = coremod.read_text(encoding='utf-8')
whole_fn_anchor = 'function initializeCoreMod() {\n'
whole_fn = r'''function addWholeModelCache(result, f) {
    var slash = f.geometryLoader.lastIndexOf('/');
    var modelClass = f.geometryLoader.substring(0, slash + 1) + 'BlockbenchModel';
    result['battlearmory_' + f.id + '_whole_model_cache'] = {
        target: { type: 'CLASS', name: dotted(modelClass) },
        transformer: function(classNode) {
            var methods = classNode.methods.iterator();
            var method = null;
            while (methods.hasNext()) {
                var candidate = methods.next();
                if (candidate.name === 'addQuads' && candidate.desc.indexOf('Lnet/minecraftforge/client/model/IModelBuilder;') >= 0) {
                    method = candidate;
                    break;
                }
            }
            if (method === null) throw 'Battle Armory ' + f.id + ' whole-model cache: addQuads not found';

            method.instructions.clear();
            method.tryCatchBlocks.clear();
            if (method.localVariables !== null) method.localVariables.clear();
            var code = new InsnList();
            code.add(new LdcInsnNode(f.id));
            code.add(new VarInsnNode(Opcodes.ALOAD, 0));
            code.add(new VarInsnNode(Opcodes.ALOAD, 1));
            code.add(new VarInsnNode(Opcodes.ALOAD, 2));
            code.add(new VarInsnNode(Opcodes.ALOAD, 3));
            code.add(new VarInsnNode(Opcodes.ALOAD, 4));
            code.add(new VarInsnNode(Opcodes.ALOAD, 5));
            code.add(new VarInsnNode(Opcodes.ALOAD, 6));
            code.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC, HELPER, 'addQuadsCached',
                '(Ljava/lang/String;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)V',
                false));
            code.add(new InsnNode(Opcodes.RETURN));
            method.instructions.add(code);
            method.maxStack = 8;
            method.maxLocals = 7;
            return classNode;
        }
    };
}

'''
if 'function addWholeModelCache' not in js:
    if whole_fn_anchor not in js:
        raise SystemExit('coremod initialize anchor not found')
    js = js.replace(whole_fn_anchor, whole_fn + whole_fn_anchor, 1)

# Replace the weak per-quad interner transformer with the whole-model cache.
old = '        addQuadDedupe(result, FAMILIES[i]);\n'
new = '        addWholeModelCache(result, FAMILIES[i]);\n'
if new not in js:
    if old not in js:
        raise SystemExit('coremod addQuadDedupe initialize call not found')
    js = js.replace(old, new, 1)
coremod.write_text(js, encoding='utf-8')

mods = Path('tools/decocraft-cache/META-INF/mods.toml')
mt = mods.read_text(encoding='utf-8')
mt = mt.replace('version="1.4.1"', 'version="1.5.0"')
mods.write_text(mt, encoding='utf-8')
