from pathlib import Path

runtime_path = Path('tools/decocraft-cache/src/com/battlearmory/decocraftcache/DecocraftCacheRuntime.java')
coremod_path = Path('tools/decocraft-cache/coremods/battlearmory_decocraft_cache.js')
mods_path = Path('tools/decocraft-cache/META-INF/mods.toml')

runtime = runtime_path.read_text(encoding='utf-8')
if 'shouldElideBlockstateBake' not in runtime:
    needle = '    public static void addQuadsCached(String family, Object model, Object context, Object builder,\n'
    guard = '''    /**\n     * Decocraft/Nature placed furniture is rendered by its BlockEntity renderer.\n     * The startup model bakery nevertheless bakes every four-way blockstate variant,\n     * retaining millions of duplicate BakedQuad/int[] objects that are not needed for\n     * the placed-world render path. Preserve the inventory model path defensively.\n     */\n    public static boolean shouldElideBlockstateBake(Object modelLocation) {\n        if (modelLocation == null) return false;\n        String location = String.valueOf(modelLocation);\n        if (location.contains("#inventory")) return false;\n        if (location.contains(":item/") || location.startsWith("item/")) return false;\n        return true;\n    }\n\n'''
    if needle not in runtime:
        raise SystemExit('addQuadsCached anchor not found')
    runtime = runtime.replace(needle, guard + needle, 1)
runtime_path.write_text(runtime, encoding='utf-8')

js = coremod_path.read_text(encoding='utf-8')
start = js.find('function addWholeModelCache(result, f) {')
end = js.find('\nfunction initializeCoreMod() {', start)
if start < 0 or end < 0:
    if 'function addBlockstateBakeElision(result, f) {' not in js:
        raise SystemExit('whole-model cache function anchor not found')
else:
    replacement = r'''function addBlockstateBakeElision(result, f) {
    var slash = f.geometryLoader.lastIndexOf('/');
    var modelClass = f.geometryLoader.substring(0, slash + 1) + 'BlockbenchModel';
    result['battlearmory_' + f.id + '_blockstate_bake_elision'] = {
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
            if (method === null) throw 'Battle Armory ' + f.id + ' blockstate bake elision: addQuads not found';

            var first = method.instructions.getFirst();
            if (first === null) throw 'Battle Armory ' + f.id + ' blockstate bake elision: empty addQuads';
            var keep = new LabelNode();
            var guard = new InsnList();
            guard.add(new VarInsnNode(Opcodes.ALOAD, 6));
            guard.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC, HELPER, 'shouldElideBlockstateBake',
                '(Ljava/lang/Object;)Z', false));
            guard.add(new JumpInsnNode(Opcodes.IFEQ, keep));
            guard.add(new InsnNode(Opcodes.RETURN));
            guard.add(keep);
            method.instructions.insertBefore(first, guard);
            method.maxStack = Math.max(method.maxStack, 1);
            return classNode;
        }
    };
}
'''
    js = js[:start] + replacement + js[end:]

js = js.replace('addWholeModelCache(result, FAMILIES[i]);', 'addBlockstateBakeElision(result, FAMILIES[i]);')
if 'addBlockstateBakeElision(result, FAMILIES[i]);' not in js:
    raise SystemExit('initializeCoreMod call replacement failed')
coremod_path.write_text(js, encoding='utf-8')

mods = mods_path.read_text(encoding='utf-8')
import re
mods, n = re.subn(r'version="[^"]+"', 'version="1.6.0"', mods, count=1)
if n != 1:
    raise SystemExit('mods.toml version replacement failed')
mods_path.write_text(mods, encoding='utf-8')
