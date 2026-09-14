from pathlib import Path

runtime_path = Path('tools/decocraft-cache/src/com/battlearmory/decocraftcache/DecocraftCacheRuntime.java')
coremod_path = Path('tools/decocraft-cache/coremods/battlearmory_decocraft_cache.js')
mods_path = Path('tools/decocraft-cache/META-INF/mods.toml')

runtime = runtime_path.read_text(encoding='utf-8')
coremod = coremod_path.read_text(encoding='utf-8')
mods = mods_path.read_text(encoding='utf-8')

# 1.7.4 keeps the proven 0020 render path intact. The only new behavior is at
# ModelManager.reload(): release references to the previous baked model graph before
# the new graph starts baking, so F3+T does not need old+new 10M-quad graphs at once.
marker = '''    /** Drop resource-derived caches before Minecraft starts a resource-pack reload. */\n'''
if marker not in runtime:
    raise SystemExit('runtime reload marker missing')

helper = r'''    /**
     * Called at the beginning of ModelManager.reload, after the reload overlay is active
     * and before the replacement model graph is baked. This intentionally touches only
     * old baked-model references; render geometry/layout from 0020 is unchanged.
     */
    public static void prepareVanillaModelReload(Object modelManager) {
        if (modelManager == null) return;
        long before = usedHeapBytes();
        long clearedRefs = 0L;
        boolean droppedBakery = false;
        try {
            ClassLoader loader = modelManager.getClass().getClassLoader();
            Class<?> bakedModelClass = Class.forName(
                    "net.minecraft.client.resources.model.BakedModel", false, loader);

            // ModelManager.bakedRegistry owns the old top-level baked graph.
            clearedRefs += clearMapsContainingValues(modelManager, bakedModelClass);

            // BlockModelShaper duplicates references by BlockState.
            Object blockShaper = fieldValueByTypeName(
                    modelManager, "net.minecraft.client.renderer.block.BlockModelShaper");
            if (blockShaper != null) {
                clearedRefs += clearMapsContainingValues(blockShaper, bakedModelClass);
            }

            // ItemModelShaper.shapesCache is another duplicate BakedModel graph. Keep
            // its registration map (ModelResourceLocation values) intact.
            Object minecraft = minecraftInstance(loader);
            if (minecraft != null) {
                Object itemRenderer = fieldValueByTypeName(
                        minecraft, "net.minecraft.client.renderer.entity.ItemRenderer");
                if (itemRenderer != null) {
                    Object itemShaper = fieldValueByTypeName(
                            itemRenderer, "net.minecraft.client.renderer.ItemModelShaper");
                    if (itemShaper != null) {
                        clearedRefs += clearMapsContainingValues(itemShaper, bakedModelClass);
                    }
                }
            }

            // The previous ModelBakery also retains its bakedTopLevelModels. ModelManager
            // reload builds the replacement bakery as a local future and assigns it only in
            // apply(), so the old field is no longer needed once a new reload starts.
            droppedBakery = clearFieldByTypeName(
                    modelManager, "net.minecraft.client.resources.model.ModelBakery");

            releaseBakeScratchCaches();

            if (clearedRefs > 0L || droppedBakery) {
                System.gc();
                long after = usedHeapBytes();
                long freed = Math.max(0L, before - after);
                System.out.println("[BattleArmory][ReloadEvict] old model graph released: refs="
                        + clearedRefs + ", bakery=" + droppedBakery + ", heapBefore=" + before
                        + ", heapAfter=" + after + ", gcFreed=" + freed);
            }
        } catch (Throwable t) {
            // Reload must remain usable even if a third-party transformer changes a field.
            System.err.println("[BattleArmory][ReloadEvict] skipped due to compatibility issue: " + t);
        }
    }

    private static long clearMapsContainingValues(Object owner, Class<?> wantedValueType) throws Exception {
        long cleared = 0L;
        Class<?> type = owner.getClass();
        while (type != null) {
            for (Field field : type.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers())) continue;
                field.setAccessible(true);
                Object value = field.get(owner);
                if (!(value instanceof Map<?, ?>)) continue;
                Map<?, ?> map = (Map<?, ?>) value;
                if (map.isEmpty()) continue;

                boolean matches = false;
                int inspected = 0;
                for (Object candidate : map.values()) {
                    if (candidate != null) {
                        matches = wantedValueType.isInstance(candidate);
                        break;
                    }
                    if (++inspected >= 16) break;
                }
                if (!matches) continue;

                int size = map.size();
                try {
                    map.clear();
                } catch (UnsupportedOperationException immutable) {
                    if (!Modifier.isFinal(field.getModifiers())
                            && field.getType().isAssignableFrom(HashMap.class)) {
                        field.set(owner, new HashMap<>());
                    } else {
                        throw immutable;
                    }
                }
                cleared += size;
            }
            type = type.getSuperclass();
        }
        return cleared;
    }

    private static Object fieldValueByTypeName(Object owner, String typeName) throws Exception {
        Class<?> type = owner.getClass();
        while (type != null) {
            for (Field field : type.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers())) continue;
                if (!field.getType().getName().equals(typeName)) continue;
                field.setAccessible(true);
                return field.get(owner);
            }
            type = type.getSuperclass();
        }
        return null;
    }

    private static boolean clearFieldByTypeName(Object owner, String typeName) throws Exception {
        Class<?> type = owner.getClass();
        while (type != null) {
            for (Field field : type.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers())) continue;
                if (!field.getType().getName().equals(typeName)) continue;
                field.setAccessible(true);
                Object old = field.get(owner);
                if (old == null) return false;
                field.set(owner, null);
                return true;
            }
            type = type.getSuperclass();
        }
        return false;
    }

    private static Object minecraftInstance(ClassLoader loader) throws Exception {
        Class<?> minecraftClass = Class.forName("net.minecraft.client.Minecraft", false, loader);
        for (Method method : minecraftClass.getDeclaredMethods()) {
            if (!Modifier.isStatic(method.getModifiers()) || method.getParameterCount() != 0) continue;
            if (method.getReturnType() != minecraftClass) continue;
            method.setAccessible(true);
            Object instance = method.invoke(null);
            if (instance != null) return instance;
        }
        return null;
    }

    private static long usedHeapBytes() {
        Runtime runtime = Runtime.getRuntime();
        return runtime.totalMemory() - runtime.freeMemory();
    }

'''
if 'public static void prepareVanillaModelReload(' in runtime:
    raise SystemExit('runtime already patched for 1.7.4')
runtime = runtime.replace(marker, helper + marker, 1)

core_marker = 'function initializeCoreMod() {\n'
if core_marker not in coremod:
    raise SystemExit('coremod initialize marker missing')

core_helper = r'''function addVanillaModelReloadEviction(result) {
    result['battlearmory_vanilla_model_reload_eviction'] = {
        target: { type: 'CLASS', name: 'net.minecraft.client.resources.model.ModelManager' },
        transformer: function(classNode) {
            var methods = classNode.methods.iterator();
            var found = null;
            while (methods.hasNext()) {
                var method = methods.next();
                if (method.desc.indexOf('Lnet/minecraft/server/packs/resources/PreparableReloadListener$PreparationBarrier;') >= 0 &&
                    method.desc.indexOf('Lnet/minecraft/server/packs/resources/ResourceManager;') >= 0 &&
                    method.desc.endsWith(')Ljava/util/concurrent/CompletableFuture;')) {
                    found = method;
                    break;
                }
            }
            if (found === null) throw 'Battle Armory reload eviction: ModelManager.reload not found';

            var code = new InsnList();
            code.add(new VarInsnNode(Opcodes.ALOAD, 0));
            code.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                HELPER,
                'prepareVanillaModelReload',
                '(Ljava/lang/Object;)V',
                false
            ));
            found.instructions.insertBefore(found.instructions.getFirst(), code);
            ASMAPI.log('INFO', '[BattleArmory] Installed pre-bake old-model eviction for resource reload');
            return classNode;
        }
    };
}

'''
if 'battlearmory_vanilla_model_reload_eviction' in coremod:
    raise SystemExit('coremod already patched for 1.7.4')
coremod = coremod.replace(core_marker, core_helper + core_marker, 1)

call_marker = '    addEmbeddiumBakedQuadViewBridge(result);\n'
if call_marker not in coremod:
    raise SystemExit('coremod bridge call marker missing')
coremod = coremod.replace(call_marker, call_marker + '    addVanillaModelReloadEviction(result);\n', 1)

if 'version="1.7.3"' not in mods:
    raise SystemExit('expected 1.7.3 mods.toml')
mods = mods.replace('version="1.7.3"', 'version="1.7.4"', 1)

runtime_path.write_text(runtime, encoding='utf-8')
coremod_path.write_text(coremod, encoding='utf-8')
mods_path.write_text(mods, encoding='utf-8')
