from pathlib import Path

runtime_path = Path('tools/decocraft-cache/src/com/battlearmory/decocraftcache/DecocraftCacheRuntime.java')
coremod_path = Path('tools/decocraft-cache/coremods/battlearmory_decocraft_cache.js')
mods_path = Path('tools/decocraft-cache/META-INF/mods.toml')

runtime = runtime_path.read_text(encoding='utf-8')
coremod = coremod_path.read_text(encoding='utf-8')
mods = mods_path.read_text(encoding='utf-8')

# The old content-addressed quad interner topped out at 1,048,576 entries while
# hitting only ~5.5%. After v1.7 rotation payload sharing it costs more table memory
# than it saves. Keep the BBModel dedupe, but construct BakedQuads directly.
needle = '''    private static Object newBakedQuad(int[] vertices, int tint, Object direction, Object sprite, boolean shade) {\n'''
if needle not in runtime:
    raise SystemExit('newBakedQuad marker missing')
helper = '''    public static Object uncachedBakedQuad(int[] vertices, int tint, Object direction, Object sprite, boolean shade) {\n        return newBakedQuad(vertices, tint, direction, sprite, shade);\n    }\n\n'''
runtime = runtime.replace(needle, helper + needle, 1)

count = coremod.count("'canonicalBakedQuad'")
if count != 2:
    raise SystemExit(f'expected 2 canonicalBakedQuad call sites, got {count}')
coremod = coremod.replace("'canonicalBakedQuad'", "'uncachedBakedQuad'")

# Embeddium stores two per-BakedQuad caches (packed normal + ModelQuadFacing).
# With ~10 million quads those two fields alone force an extra 8-byte object-layout
# bucket per quad. Recompute them during chunk compilation instead of retaining them
# forever; render output is unchanged because the calculations use the same stock
# ModelQuadUtil methods and the already-correct effective rotated vertices.
init = 'function initializeCoreMod() {\n'
if init not in coremod:
    raise SystemExit('coremod init marker missing')

compact = r'''function addEmbeddiumBakedQuadCacheCompaction(result) {
    var mixinName = 'me.jellysquid.mods.sodium.mixin.core.model.quad.BakedQuadMixin';
    result['battlearmory_embeddium_baked_quad_cache_compaction'] = {
        target: { type: 'CLASS', name: mixinName },
        transformer: function(classNode) {
            var removedNormal = false;
            var removedNormalFace = false;
            var fields = classNode.fields.iterator();
            while (fields.hasNext()) {
                var field = fields.next();
                if (field.name === 'normal' && field.desc === 'I') {
                    fields.remove();
                    removedNormal = true;
                } else if (field.name === 'normalFace') {
                    fields.remove();
                    removedNormalFace = true;
                }
            }
            if (!removedNormal || !removedNormalFace) {
                throw 'Battle Armory Embeddium compaction: normal cache fields not found';
            }

            var getNormal = null;
            var getNormalFace = null;
            var methods = classNode.methods.iterator();
            while (methods.hasNext()) {
                var method = methods.next();
                if (method.name === 'getComputedFaceNormal' && method.desc === '()I') getNormal = method;
                if (method.name === 'getNormalFace' && method.desc.indexOf('()L') === 0) getNormalFace = method;
            }
            if (getNormal === null || getNormalFace === null) {
                throw 'Battle Armory Embeddium compaction: normal methods not found';
            }

            var calculateNormal = null;
            var insn = getNormal.instructions.getFirst();
            while (insn !== null) {
                if (insn.getOpcode() === Opcodes.INVOKESTATIC && insn.name === 'calculateNormal' && insn.desc.slice(-2) === ')I') {
                    calculateNormal = insn;
                    break;
                }
                insn = insn.getNext();
            }
            if (calculateNormal === null) {
                throw 'Battle Armory Embeddium compaction: calculateNormal call not found';
            }

            var findNormalFace = null;
            insn = getNormalFace.instructions.getFirst();
            while (insn !== null) {
                if (insn.getOpcode() === Opcodes.INVOKESTATIC && insn.name === 'findNormalFace') {
                    findNormalFace = insn;
                    break;
                }
                insn = insn.getNext();
            }
            if (findNormalFace === null) {
                throw 'Battle Armory Embeddium compaction: findNormalFace call not found';
            }

            var calcOwner = calculateNormal.owner;
            var calcName = calculateNormal.name;
            var calcDesc = calculateNormal.desc;
            var faceOwner = findNormalFace.owner;
            var faceName = findNormalFace.name;
            var faceDesc = findNormalFace.desc;

            getNormal.instructions.clear();
            getNormal.tryCatchBlocks.clear();
            getNormal.localVariables = null;
            getNormal.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
            getNormal.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC, calcOwner, calcName, calcDesc, false));
            getNormal.instructions.add(new InsnNode(Opcodes.IRETURN));
            getNormal.maxStack = 1;
            getNormal.maxLocals = 1;

            getNormalFace.instructions.clear();
            getNormalFace.tryCatchBlocks.clear();
            getNormalFace.localVariables = null;
            getNormalFace.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
            getNormalFace.instructions.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, classNode.name, 'getComputedFaceNormal', '()I', false));
            getNormalFace.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC, faceOwner, faceName, faceDesc, false));
            getNormalFace.instructions.add(new InsnNode(Opcodes.ARETURN));
            getNormalFace.maxStack = 1;
            getNormalFace.maxLocals = 1;

            ASMAPI.log('INFO', '[BattleArmory] Removed Embeddium retained BakedQuad normal caches');
            return classNode;
        }
    };
}

'''
coremod = coremod.replace(init, compact + init, 1)

bridge_call = '    addEmbeddiumBakedQuadViewBridge(result);\n'
if bridge_call not in coremod:
    raise SystemExit('Embeddium bridge enable call missing')
coremod = coremod.replace(bridge_call, bridge_call + '    addEmbeddiumBakedQuadCacheCompaction(result);\n', 1)

if 'version="1.7.1"' not in mods:
    raise SystemExit('version marker missing')
mods = mods.replace('version="1.7.1"', 'version="1.7.2"', 1)

runtime_path.write_text(runtime, encoding='utf-8')
coremod_path.write_text(coremod, encoding='utf-8')
mods_path.write_text(mods, encoding='utf-8')
