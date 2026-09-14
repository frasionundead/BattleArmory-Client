var ASMAPI = Java.type('net.minecraftforge.coremod.api.ASMAPI');
var Opcodes = Java.type('org.objectweb.asm.Opcodes');
var FieldNode = Java.type('org.objectweb.asm.tree.FieldNode');
var MethodNode = Java.type('org.objectweb.asm.tree.MethodNode');
var InsnList = Java.type('org.objectweb.asm.tree.InsnList');
var InsnNode = Java.type('org.objectweb.asm.tree.InsnNode');
var VarInsnNode = Java.type('org.objectweb.asm.tree.VarInsnNode');
var TypeInsnNode = Java.type('org.objectweb.asm.tree.TypeInsnNode');
var FieldInsnNode = Java.type('org.objectweb.asm.tree.FieldInsnNode');
var MethodInsnNode = Java.type('org.objectweb.asm.tree.MethodInsnNode');

var AR = 'com/razz/decocraft/client/render/AnimatedRenderer';
var AMR = 'com/razz/decocraft/client/render/AnimatedModelRenderer';
var BASE = 'com/razz/decocraft/models/bbmodel/BBModelParts$ElementBase';
var MP = 'net/minecraft/client/model/geom/ModelPart';
var PARSE_DESC = '(Lcom/razz/decocraft/common/JsonContainer$Entry;Lcom/razz/decocraft/models/bbmodel/BBModel;Lcom/razz/decocraft/common/tileentities/AnimatedTileEntity;)Lnet/minecraft/client/model/geom/ModelPart;';
var HELPER = 'com/battlearmory/decocraftcache/DecocraftCacheRuntime';

function initializeCoreMod() {
    return {
        'battlearmory_decocraft_animated_model': {
            'target': {
                'type': 'CLASS',
                'name': 'com.razz.decocraft.client.render.AnimatedModelRenderer'
            },
            'transformer': function(classNode) {
                var fields = classNode.fields.iterator();
                while (fields.hasNext()) {
                    if (fields.next().name === 'ba$uuid') return classNode;
                }
                classNode.fields.add(new FieldNode(Opcodes.ACC_PUBLIC, 'ba$uuid', 'Ljava/lang/String;', null, null));
                return classNode;
            }
        },
        'battlearmory_decocraft_animated_renderer': {
            'target': {
                'type': 'CLASS',
                'name': 'com.razz.decocraft.client.render.AnimatedRenderer'
            },
            'transformer': function(classNode) {
                var methods = classNode.methods.iterator();
                var original = null;
                var parseOutliners = null;
                while (methods.hasNext()) {
                    var method = methods.next();
                    if (method.name === 'parseModel' && method.desc === PARSE_DESC) original = method;
                    if (method.name === 'parseOutliners') parseOutliners = method;
                }
                if (original === null || parseOutliners === null) {
                    throw 'Battle Armory Decocraft cache: required renderer methods were not found';
                }

                original.name = 'ba$buildModel';

                var injectedUuid = false;
                var insn = parseOutliners.instructions.getFirst();
                while (insn !== null) {
                    if (insn.getOpcode() === Opcodes.INVOKESPECIAL && insn.owner === AMR && insn.name === '<init>') {
                        var next = insn.getNext();
                        while (next !== null && next.getOpcode() < 0) next = next.getNext();
                        if (next !== null && next.getOpcode() === Opcodes.ASTORE && next.var === 13) {
                            var uuidCode = new InsnList();
                            uuidCode.add(new VarInsnNode(Opcodes.ALOAD, 13));
                            uuidCode.add(new VarInsnNode(Opcodes.ALOAD, 11));
                            uuidCode.add(new FieldInsnNode(Opcodes.GETFIELD, BASE, 'uuid', 'Ljava/lang/String;'));
                            uuidCode.add(new FieldInsnNode(Opcodes.PUTFIELD, AMR, 'ba$uuid', 'Ljava/lang/String;'));
                            parseOutliners.instructions.insert(next, uuidCode);
                            injectedUuid = true;
                            break;
                        }
                    }
                    insn = insn.getNext();
                }
                if (!injectedUuid) throw 'Battle Armory Decocraft cache: UUID injection point was not found';

                var wrapper = new MethodNode(Opcodes.ACC_PRIVATE, 'parseModel', PARSE_DESC, null, null);
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
                return classNode;
            }
        }
    };
}
