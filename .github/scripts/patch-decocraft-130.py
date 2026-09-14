from pathlib import Path

runtime = Path('tools/decocraft-cache/src/com/battlearmory/decocraftcache/DecocraftCacheRuntime.java')
text = runtime.read_text(encoding='utf-8')
if 'import java.util.concurrent.ConcurrentHashMap;' not in text:
    text = text.replace(
        'import java.util.Set;\nimport java.util.function.Function;',
        'import java.util.Set;\nimport java.util.concurrent.ConcurrentHashMap;\nimport java.util.function.Function;', 1)

old_clear = '''    /** Drop resource-derived caches before Minecraft starts a resource-pack reload. */
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
        System.out.println("[BattleArmory] Resource reload: cleared Decocraft caches (models=" + models + ", materials=" + materials + ", resourceModels=" + resourceModels + ").");
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
'''
if 'public static Object sharedResourceModel(' not in text:
    if old_clear not in text:
        raise SystemExit('clearForResourceReload anchor not found')
    text = text.replace(old_clear, new_clear, 1)

family_anchor = '''        final IdentityHashMap<Object, Object> modelCache = new IdentityHashMap<>();
        final IdentityHashMap<Object, NodeState[]> nodesByModel = new IdentityHashMap<>();
        final Map<String, Object> materialCache = new HashMap<>();
'''
family_new = '''        final IdentityHashMap<Object, Object> modelCache = new IdentityHashMap<>();
        final IdentityHashMap<Object, NodeState[]> nodesByModel = new IdentityHashMap<>();
        final Map<String, Object> materialCache = new HashMap<>();
        final ConcurrentHashMap<String, Object> registryParsedModels = new ConcurrentHashMap<>();
        final ConcurrentHashMap<String, Object> resourceParsedModels = new ConcurrentHashMap<>();
'''
if 'registryParsedModels' not in text.split('private static final class FamilyState', 1)[-1]:
    if family_anchor not in text:
        raise SystemExit('FamilyState cache anchor not found')
    text = text.replace(family_anchor, family_new, 1)
runtime.write_text(text, encoding='utf-8')

coremod = Path('tools/decocraft-cache/coremods/battlearmory_decocraft_cache.js')
js = coremod.read_text(encoding='utf-8')
if "var JumpInsnNode" not in js:
    js = js.replace(
        "var MethodInsnNode = Java.type('org.objectweb.asm.tree.MethodInsnNode');",
        "var MethodInsnNode = Java.type('org.objectweb.asm.tree.MethodInsnNode');\nvar JumpInsnNode = Java.type('org.objectweb.asm.tree.JumpInsnNode');\nvar LabelNode = Java.type('org.objectweb.asm.tree.LabelNode');", 1)

if "jsonParser: 'com/razz/decocraft/utils/JsonParser'" not in js:
    js = js.replace(
        "        entry: 'com/razz/decocraft/common/JsonContainer$Entry',\n        parseDesc:",
        "        entry: 'com/razz/decocraft/common/JsonContainer$Entry',\n        jsonParser: 'com/razz/decocraft/utils/JsonParser',\n        bbmodel: 'com/razz/decocraft/models/bbmodel/BBModel',\n        geometryLoader: 'com/razz/decocraft/models/bbmodel/BlockbenchLoader',\n        bbModelLoader: 'com/razz/decocraft/models/bbmodel/BBModelLoader',\n        parseDesc:", 1)
if "jsonParser: 'com/razz/decocraft_nature/utils/JsonParser'" not in js:
    js = js.replace(
        "        entry: 'com/razz/decocraft_nature/common/JsonContainer$Entry',\n        parseDesc:",
        "        entry: 'com/razz/decocraft_nature/common/JsonContainer$Entry',\n        jsonParser: 'com/razz/decocraft_nature/utils/JsonParser',\n        bbmodel: 'com/razz/decocraft_nature/models/bbmodel/BBModel',\n        geometryLoader: 'com/razz/decocraft_nature/models/bbmodel/BlockbenchLoader',\n        bbModelLoader: 'com/razz/decocraft_nature/models/bbmodel/BBModelLoader',\n        parseDesc:", 1)

memory_fn = r'''
function addMemoryDedupe(result, f) {
    result['battlearmory_' + f.id + '_registry_bbmodel_dedupe'] = {
        target: { type: 'CLASS', name: dotted(f.jsonParser) },
        transformer: function(classNode) {
            var methods = classNode.methods.iterator();
            var method = null;
            var wanted = '(Ljava/lang/String;Ljava/util/zip/ZipFile;)L' + f.bbmodel + ';';
            while (methods.hasNext()) {
                var candidate = methods.next();
                if (candidate.name === 'parseModel' && candidate.desc === wanted) {
                    method = candidate;
                    break;
                }
            }
            if (method === null) throw 'Battle Armory ' + f.id + ' memory dedupe: JsonParser.parseModel not found';

            var returns = [];
            var scan = method.instructions.getFirst();
            while (scan !== null) {
                if (scan.getOpcode() === Opcodes.ARETURN) returns.push(scan);
                scan = scan.getNext();
            }

            var miss = new LabelNode();
            var entry = new InsnList();
            entry.add(new LdcInsnNode(f.id));
            entry.add(new VarInsnNode(Opcodes.ALOAD, 0));
            entry.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC, HELPER, 'parsedRegistryModelGet',
                '(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/Object;', false));
            entry.add(new InsnNode(Opcodes.DUP));
            entry.add(new JumpInsnNode(Opcodes.IFNULL, miss));
            entry.add(new TypeInsnNode(Opcodes.CHECKCAST, f.bbmodel));
            entry.add(new InsnNode(Opcodes.ARETURN));
            entry.add(miss);
            entry.add(new InsnNode(Opcodes.POP));
            method.instructions.insertBefore(method.instructions.getFirst(), entry);

            for (var i = 0; i < returns.length; i++) {
                var hook = new InsnList();
                hook.add(new LdcInsnNode(f.id));
                hook.add(new VarInsnNode(Opcodes.ALOAD, 0));
                hook.add(new MethodInsnNode(
                    Opcodes.INVOKESTATIC, HELPER, 'parsedRegistryModelPut',
                    '(Ljava/lang/Object;Ljava/lang/String;Ljava/lang/String;)Ljava/lang/Object;', false));
                hook.add(new TypeInsnNode(Opcodes.CHECKCAST, f.bbmodel));
                method.instructions.insertBefore(returns[i], hook);
            }
            method.maxStack = Math.max(method.maxStack, 3);
            return classNode;
        }
    };

    result['battlearmory_' + f.id + '_resource_bbmodel_dedupe'] = {
        target: { type: 'CLASS', name: dotted(f.geometryLoader) },
        transformer: function(classNode) {
            var methods = classNode.methods.iterator();
            var method = null;
            var modelClass = f.geometryLoader.substring(0, f.geometryLoader.lastIndexOf('/') + 1) + 'BlockbenchModel';
            var wanted = '(Lcom/google/gson/JsonObject;Lcom/google/gson/JsonDeserializationContext;)L' + modelClass + ';';
            while (methods.hasNext()) {
                var candidate = methods.next();
                if (candidate.name === 'read' && candidate.desc === wanted) {
                    method = candidate;
                    break;
                }
            }
            if (method === null) throw 'Battle Armory ' + f.id + ' memory dedupe: concrete BlockbenchLoader.read not found';

            var modelVar = -1;
            var loadCall = null;
            var readerVar = -1;
            var scan = method.instructions.getFirst();
            while (scan !== null) {
                if (scan.getOpcode() === Opcodes.INVOKEVIRTUAL &&
                    scan.owner === 'com/google/gson/JsonElement' && scan.name === 'getAsString') {
                    var store = nextReal(scan);
                    if (store !== null && store.getOpcode() === Opcodes.ASTORE && modelVar < 0) modelVar = store.var;
                }
                if (scan.getOpcode() === Opcodes.INVOKEVIRTUAL && scan.owner === f.bbModelLoader &&
                    scan.name === 'loadModel' && scan.desc === '(Ljava/io/Reader;)L' + f.bbmodel + ';') {
                    loadCall = scan;
                    var readerLoad = previousReal(scan);
                    if (readerLoad !== null && readerLoad.getOpcode() === Opcodes.ALOAD) readerVar = readerLoad.var;
                }
                scan = scan.getNext();
            }
            if (modelVar < 0 || loadCall === null || readerVar < 0) {
                throw 'Battle Armory ' + f.id + ' memory dedupe: resource parse locals/call not found';
            }

            var start = previousReal(loadCall);
            while (start !== null && !(start.getOpcode() === Opcodes.NEW && start.desc === f.bbModelLoader)) {
                start = previousReal(start);
            }
            if (start === null) throw 'Battle Armory ' + f.id + ' memory dedupe: BBModelLoader allocation not found';

            var replacement = new InsnList();
            replacement.add(new LdcInsnNode(f.id));
            replacement.add(new VarInsnNode(Opcodes.ALOAD, modelVar));
            replacement.add(new VarInsnNode(Opcodes.ALOAD, readerVar));
            replacement.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC, HELPER, 'sharedResourceModel',
                '(Ljava/lang/String;Ljava/lang/String;Ljava/lang/Object;)Ljava/lang/Object;', false));
            replacement.add(new TypeInsnNode(Opcodes.CHECKCAST, f.bbmodel));
            method.instructions.insertBefore(start, replacement);
            removeRange(method.instructions, start, loadCall);
            method.maxStack = Math.max(method.maxStack, 3);
            return classNode;
        }
    };
}

'''
if 'function addMemoryDedupe(result, f)' not in js:
    anchor = 'function initializeCoreMod() {'
    if anchor not in js:
        raise SystemExit('initializeCoreMod anchor not found')
    js = js.replace(anchor, memory_fn + anchor, 1)

old_loop = '    for (var i = 0; i < FAMILIES.length; i++) addFamily(result, FAMILIES[i]);'
new_loop = '''    for (var i = 0; i < FAMILIES.length; i++) {
        addFamily(result, FAMILIES[i]);
        addMemoryDedupe(result, FAMILIES[i]);
    }'''
if 'addMemoryDedupe(result, FAMILIES[i]);' not in js:
    if old_loop not in js:
        raise SystemExit('family initialize loop not found')
    js = js.replace(old_loop, new_loop, 1)
coremod.write_text(js, encoding='utf-8')

mods = Path('tools/decocraft-cache/META-INF/mods.toml')
mt = mods.read_text(encoding='utf-8')
mt = mt.replace('version="1.2.1"', 'version="1.3.0"', 1)
if 'shared BBModel parse graphs' not in mt:
    mt = mt.replace('render batching.', 'render batching and shared BBModel parse graphs.', 1)
mods.write_text(mt, encoding='utf-8')
