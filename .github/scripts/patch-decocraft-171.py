from pathlib import Path

runtime_path = Path('tools/decocraft-cache/src/com/battlearmory/decocraftcache/DecocraftCacheRuntime.java')
coremod_path = Path('tools/decocraft-cache/coremods/battlearmory_decocraft_cache.js')
mods_path = Path('tools/decocraft-cache/META-INF/mods.toml')

runtime = runtime_path.read_text(encoding='utf-8')
coremod = coremod_path.read_text(encoding='utf-8')
mods = mods_path.read_text(encoding='utf-8')

marker = '''    /** Called by the coremod-patched BakedQuad getter only for compressed variants. */
    public static int[] materializeRotatedVertices(int[] base, byte encodedPlan) {
'''
helper = '''    /**
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

'''
if marker not in runtime:
    raise SystemExit('runtime marker missing')
runtime = runtime.replace(marker, helper + marker, 1)

init = 'function initializeCoreMod() {\n'
bridge = r'''function addEmbeddiumBakedQuadViewBridge(result) {
    var mixinName = 'me.jellysquid.mods.sodium.mixin.core.model.quad.BakedQuadMixin';
    result['battlearmory_embeddium_baked_quad_view_bridge'] = {
        target: { type: 'CLASS', name: mixinName },
        transformer: function(classNode) {
            var patched = 0;
            var methods = classNode.methods.iterator();
            while (methods.hasNext()) {
                var method = methods.next();
                var insn = method.instructions.getFirst();
                while (insn !== null) {
                    var next = insn.getNext();
                    if (insn.getOpcode() === Opcodes.GETFIELD && insn.desc === '[I') {
                        var call = new MethodInsnNode(
                            Opcodes.INVOKESTATIC,
                            HELPER,
                            'effectiveBakedQuadVertices',
                            '(Ljava/lang/Object;)[I',
                            false
                        );
                        method.instructions.set(insn, call);
                        patched++;
                    }
                    insn = next;
                }
            }
            if (patched === 0) throw 'Battle Armory Embeddium bridge: no int[] vertex field reads found';
            ASMAPI.log('INFO', '[BattleArmory] Patched Embeddium BakedQuadMixin vertex reads: ' + patched);
            return classNode;
        }
    };
}

'''
if init not in coremod:
    raise SystemExit('coremod init marker missing')
coremod = coremod.replace(init, bridge + init, 1)
needle = '    addBakedQuadRotationStorage(result);\n'
if needle not in coremod:
    raise SystemExit('rotation storage enable missing')
coremod = coremod.replace(needle, needle + '    addEmbeddiumBakedQuadViewBridge(result);\n', 1)

if 'version="1.7.0"' not in mods:
    raise SystemExit('version marker missing')
mods = mods.replace('version="1.7.0"', 'version="1.7.1"', 1)

runtime_path.write_text(runtime, encoding='utf-8')
coremod_path.write_text(coremod, encoding='utf-8')
mods_path.write_text(mods, encoding='utf-8')
