var ASMAPI = Java.type('net.minecraftforge.coremod.api.ASMAPI');
var Opcodes = Java.type('org.objectweb.asm.Opcodes');
var FieldNode = Java.type('org.objectweb.asm.tree.FieldNode');
var MethodNode = Java.type('org.objectweb.asm.tree.MethodNode');
var InsnList = Java.type('org.objectweb.asm.tree.InsnList');
var InsnNode = Java.type('org.objectweb.asm.tree.InsnNode');
var LdcInsnNode = Java.type('org.objectweb.asm.tree.LdcInsnNode');
var VarInsnNode = Java.type('org.objectweb.asm.tree.VarInsnNode');
var TypeInsnNode = Java.type('org.objectweb.asm.tree.TypeInsnNode');
var FieldInsnNode = Java.type('org.objectweb.asm.tree.FieldInsnNode');
var MethodInsnNode = Java.type('org.objectweb.asm.tree.MethodInsnNode');

var HELPER = 'com/battlearmory/decocraftcache/DecocraftCacheRuntime';
var MP = 'net/minecraft/client/model/geom/ModelPart';
var MATERIAL = 'net/minecraft/client/resources/model/Material';

var FAMILIES = [
    {
        id: 'decocraft',
        ns: 'decocraft',
        ar: 'com/razz/decocraft/client/render/AnimatedRenderer',
        amr: 'com/razz/decocraft/client/render/AnimatedModelRenderer',
        base: 'com/razz/decocraft/models/bbmodel/BBModelParts$ElementBase',
        block: 'com/razz/decocraft/common/blocks/DecocraftBlock',
        entry: 'com/razz/decocraft/common/JsonContainer$Entry',
        parseDesc: '(Lcom/razz/decocraft/common/JsonContainer$Entry;Lcom/razz/decocraft/models/bbmodel/BBModel;Lcom/razz/decocraft/common/tileentities/AnimatedTileEntity;)Lnet/minecraft/client/model/geom/ModelPart;'
    },
    {
        id: 'decocraft_nature',
        ns: 'decocraft_nature',
        ar: 'com/razz/decocraft_nature/client/render/AnimatedRenderer',
        amr: 'com/razz/decocraft_nature/client/render/AnimatedModelRenderer',
        base: 'com/razz/decocraft_nature/models/bbmodel/BBModelParts$ElementBase',
        block: 'com/razz/decocraft_nature/common/blocks/DecocraftBlock',
        entry: 'com/razz/decocraft_nature/common/JsonContainer$Entry',
        parseDesc: '(Lcom/razz/decocraft_nature/common/JsonContainer$Entry;Lcom/razz/decocraft_nature/models/bbmodel/BBModel;Lcom/razz/decocraft_nature/common/tileentities/AnimatedTileEntity;)Lnet/minecraft/client/model/geom/ModelPart;'
    }
];

function nextReal(insn) {
    var n = insn === null ? null : insn.getNext();
    while (n !== null && n.getOpcode() < 0) n = n.getNext();
    return n;
}

function addFamily(result, f) {
    result['battlearmory_' + f.id + '_animated_model'] = {
        target: { type: 'CLASS', name: f.amr.replaceAll('/', '.') },
        transformer: function(classNode) {
            var fields = classNode.fields.iterator();
            while (fields.hasNext()) {
                if (fields.next().name === 'ba$uuid') return classNode;
            }
            classNode.fields.add(new FieldNode(Opcodes.ACC_PUBLIC, 'ba$uuid', 'Ljava/lang/String;', null, null));
            return classNode;
        }
    };

    result['battlearmory_' + f.id + '_animated_renderer'] = {
        target: { type: 'CLASS', name: f.ar.replaceAll('/', '.') },
        transformer: function(classNode) {
            var methods = classNode.methods.iterator();
            var original = null;
            var parseOutliners = null;
            var render = null;
            while (methods.hasNext()) {
                var method = methods.next();
                if (method.name === 'parseModel' && method.desc === f.parseDesc) original = method;
                if (method.name === 'parseOutliners') parseOutliners = method;
                if (method.name === 'render' && method.desc.indexOf('Lnet/minecraft/client/renderer/MultiBufferSource;II)V') >= 0) render = method;
            }
            if (original === null || parseOutliners === null || render === null) {
                throw 'Battle Armory ' + f.id + ' cache: required renderer methods were not found';
            }

            original.name = 'ba$buildModel';

            // Preserve each model node UUID so the cached static geometry can receive
            // the current block entity's dynamic keyframe transforms every frame.
            var injectedUuid = false;
            var insn = parseOutliners.instructions.getFirst();
            while (insn !== null) {
                if (insn.getOpcode() === Opcodes.INVOKESPECIAL && insn.owner === f.amr && insn.name === '<init>') {
                    var store = nextReal(insn);
                    if (store !== null && store.getOpcode() === Opcodes.ASTORE) {
                        var uuidCode = new InsnList();
                        uuidCode.add(new VarInsnNode(Opcodes.ALOAD, store.var));
                        // In both pinned 3.0.4 renderers local 11 is ElementBase.
                        uuidCode.add(new VarInsnNode(Opcodes.ALOAD, 11));
                        uuidCode.add(new FieldInsnNode(Opcodes.GETFIELD, f.base, 'uuid', 'Ljava/lang/String;'));
                        uuidCode.add(new FieldInsnNode(Opcodes.PUTFIELD, f.amr, 'ba$uuid', 'Ljava/lang/String;'));
                        parseOutliners.instructions.insert(store, uuidCode);
                        injectedUuid = true;
                        break;
                    }
                }
                insn = insn.getNext();
            }
            if (!injectedUuid) throw 'Battle Armory ' + f.id + ' cache: UUID injection point was not found';

            var wrapper = new MethodNode(Opcodes.ACC_PRIVATE, 'parseModel', f.parseDesc, null, null);
            wrapper.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
            wrapper.instructions.add(new VarInsnNode(Opcodes.ALOAD, 1));
            wrapper.instructions.add(new VarInsnNode(Opcodes.ALOAD, 2));
            wrapper.instructions.add(new VarInsnNode(Opcodes.ALOAD, 3));
            wrapper.instructions.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                HELPER,
                'getModel',
                '(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;',
                false
            ));
            wrapper.instructions.add(new TypeInsnNode(Opcodes.CHECKCAST, MP));
            wrapper.instructions.add(new InsnNode(Opcodes.ARETURN));
            wrapper.maxStack = 4;
            wrapper.maxLocals = 4;
            classNode.methods.add(wrapper);

            // Discover the local holding DecocraftBlock rather than hard-coding it.
            var blockVar = -1;
            insn = render.instructions.getFirst();
            while (insn !== null) {
                if (insn.getOpcode() === Opcodes.CHECKCAST && insn.desc === f.block) {
                    var blockStore = nextReal(insn);
                    if (blockStore !== null && blockStore.getOpcode() === Opcodes.ASTORE) {
                        blockVar = blockStore.var;
                        break;
                    }
                }
                insn = insn.getNext();
            }
            if (blockVar < 0) throw 'Battle Armory ' + f.id + ' cache: block local was not found';

            // Replace RenderType::entityTranslucent function with a selector. The helper
            // keeps partial-alpha materials translucent and moves binary-alpha/opaque
            // materials to entityCutoutNoCull, avoiding unnecessary translucent sorting.
            var replacedRenderType = false;
            insn = render.instructions.getFirst();
            while (insn !== null) {
                var candidate = insn;
                insn = insn.getNext();
                if (candidate.getOpcode() !== Opcodes.INVOKEDYNAMIC || candidate.desc !== '()Ljava/util/function/Function;') continue;
                var after = nextReal(candidate);
                if (after === null || after.getOpcode() !== Opcodes.INVOKEVIRTUAL || after.owner !== MATERIAL) continue;
                if (after.name !== 'm_119194_' && after.name !== 'buffer') continue;

                var selector = new InsnList();
                selector.add(new LdcInsnNode(f.ns));
                selector.add(new VarInsnNode(Opcodes.ALOAD, blockVar));
                selector.add(new FieldInsnNode(Opcodes.GETFIELD, f.block, 'meta', 'L' + f.entry + ';'));
                selector.add(new FieldInsnNode(Opcodes.GETFIELD, f.entry, 'material', 'Ljava/lang/String;'));
                selector.add(new MethodInsnNode(
                    Opcodes.INVOKESTATIC,
                    HELPER,
                    'renderTypeFunction',
                    '(Ljava/lang/String;Ljava/lang/String;)Ljava/util/function/Function;',
                    false
                ));
                render.instructions.insertBefore(candidate, selector);
                render.instructions.remove(candidate);
                replacedRenderType = true;
                break;
            }
            if (!replacedRenderType) throw 'Battle Armory ' + f.id + ' cache: render-type function injection point was not found';

            return classNode;
        }
    };
}

function initializeCoreMod() {
    var result = {};
    for (var i = 0; i < FAMILIES.length; i++) addFamily(result, FAMILIES[i]);
    return result;
}
