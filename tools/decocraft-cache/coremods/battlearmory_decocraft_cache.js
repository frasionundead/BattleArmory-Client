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
var JumpInsnNode = Java.type('org.objectweb.asm.tree.JumpInsnNode');
var LabelNode = Java.type('org.objectweb.asm.tree.LabelNode');

var HELPER = 'com/battlearmory/decocraftcache/DecocraftCacheRuntime';
var MP = 'net/minecraft/client/model/geom/ModelPart';
var MATERIAL = 'net/minecraft/client/resources/model/Material';
var POSESTACK = 'com/mojang/blaze3d/vertex/PoseStack';
var QUAT = 'org/joml/Quaternionf';
var DIRECTION = 'net/minecraft/core/Direction';

var FAMILIES = [
    {
        id: 'decocraft',
        ns: 'decocraft',
        ar: 'com/razz/decocraft/client/render/AnimatedRenderer',
        amr: 'com/razz/decocraft/client/render/AnimatedModelRenderer',
        base: 'com/razz/decocraft/models/bbmodel/BBModelParts$ElementBase',
        block: 'com/razz/decocraft/common/blocks/DecocraftBlock',
        entry: 'com/razz/decocraft/common/JsonContainer$Entry',
        jsonParser: 'com/razz/decocraft/utils/JsonParser',
        bbmodel: 'com/razz/decocraft/models/bbmodel/BBModel',
        geometryLoader: 'com/razz/decocraft/models/bbmodel/BlockbenchLoader',
        bbModelLoader: 'com/razz/decocraft/models/bbmodel/BBModelLoader',
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
        jsonParser: 'com/razz/decocraft_nature/utils/JsonParser',
        bbmodel: 'com/razz/decocraft_nature/models/bbmodel/BBModel',
        geometryLoader: 'com/razz/decocraft_nature/models/bbmodel/BlockbenchLoader',
        bbModelLoader: 'com/razz/decocraft_nature/models/bbmodel/BBModelLoader',
        parseDesc: '(Lcom/razz/decocraft_nature/common/JsonContainer$Entry;Lcom/razz/decocraft_nature/models/bbmodel/BBModel;Lcom/razz/decocraft_nature/common/tileentities/AnimatedTileEntity;)Lnet/minecraft/client/model/geom/ModelPart;'
    }
];

function dotted(name) {
    return name.replace(/\//g, '.');
}

function nextReal(insn) {
    var n = insn === null ? null : insn.getNext();
    while (n !== null && n.getOpcode() < 0) n = n.getNext();
    return n;
}

function previousReal(insn) {
    var n = insn === null ? null : insn.getPrevious();
    while (n !== null && n.getOpcode() < 0) n = n.getPrevious();
    return n;
}

function removeRange(instructions, first, last) {
    var cur = first;
    while (cur !== null) {
        var next = cur.getNext();
        instructions.remove(cur);
        if (cur === last) break;
        cur = next;
    }
}

function addFamily(result, f) {
    result['battlearmory_' + f.id + '_animated_model'] = {
        target: { type: 'CLASS', name: dotted(f.amr) },
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
        target: { type: 'CLASS', name: dotted(f.ar) },
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

            // Preserve each node UUID once. Runtime flattens this tree after the
            // first build so later animation updates no longer allocate iterators.
            var injectedUuid = false;
            var insn = parseOutliners.instructions.getFirst();
            while (insn !== null) {
                if (insn.getOpcode() === Opcodes.INVOKESPECIAL && insn.owner === f.amr && insn.name === '<init>') {
                    var store = nextReal(insn);
                    if (store !== null && store.getOpcode() === Opcodes.ASTORE) {
                        var uuidCode = new InsnList();
                        uuidCode.add(new VarInsnNode(Opcodes.ALOAD, store.var));
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

            var blockVar = -1;
            var facingVar = -1;
            insn = render.instructions.getFirst();
            while (insn !== null) {
                if (insn.getOpcode() === Opcodes.CHECKCAST && insn.desc === f.block) {
                    var blockStore = nextReal(insn);
                    if (blockStore !== null && blockStore.getOpcode() === Opcodes.ASTORE) blockVar = blockStore.var;
                }
                if (insn.getOpcode() === Opcodes.CHECKCAST && insn.desc === DIRECTION) {
                    var facingStore = nextReal(insn);
                    if (facingStore !== null && facingStore.getOpcode() === Opcodes.ASTORE) facingVar = facingStore.var;
                }
                insn = insn.getNext();
            }
            if (blockVar < 0) throw 'Battle Armory ' + f.id + ' cache: block local was not found';
            if (facingVar < 0) throw 'Battle Armory ' + f.id + ' cache: facing local was not found';

            // Replace per-block-entity new Material(new ResourceLocation(...)) with
            // a material cache keyed by the immutable Decocraft metadata string.
            var materialNew = null;
            var materialCtor = null;
            insn = render.instructions.getFirst();
            while (insn !== null) {
                if (materialNew === null && insn.getOpcode() === Opcodes.NEW && insn.desc === MATERIAL) {
                    materialNew = insn;
                } else if (materialNew !== null && insn.getOpcode() === Opcodes.INVOKESPECIAL && insn.owner === MATERIAL && insn.name === '<init>') {
                    materialCtor = insn;
                    break;
                }
                insn = insn.getNext();
            }
            if (materialNew === null || materialCtor === null) {
                throw 'Battle Armory ' + f.id + ' cache: Material allocation sequence was not found';
            }
            var materialCode = new InsnList();
            materialCode.add(new LdcInsnNode(f.ns));
            materialCode.add(new VarInsnNode(Opcodes.ALOAD, blockVar));
            materialCode.add(new FieldInsnNode(Opcodes.GETFIELD, f.block, 'meta', 'L' + f.entry + ';'));
            materialCode.add(new FieldInsnNode(Opcodes.GETFIELD, f.entry, 'material', 'Ljava/lang/String;'));
            materialCode.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                HELPER,
                'material',
                '(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/Object;',
                false
            ));
            materialCode.add(new TypeInsnNode(Opcodes.CHECKCAST, MATERIAL));
            render.instructions.insertBefore(materialNew, materialCode);
            removeRange(render.instructions, materialNew, materialCtor);

            // Preserve partial-alpha rendering, but avoid sorting for binary-alpha
            // textures. Runtime returns one of two static Function singletons.
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

            // Decocraft used to allocate three AxisAngle4f + three Quaternionf for
            // every animated block entity on every frame. Replace those constructions
            // with immutable cached quaternions while keeping the same three mulPose calls.
            var rotations = [];
            insn = render.instructions.getFirst();
            while (insn !== null) {
                if (insn.getOpcode() === Opcodes.INVOKEVIRTUAL && insn.owner === POSESTACK &&
                    insn.desc === '(L' + QUAT + ';)V') {
                    rotations.push(insn);
                }
                insn = insn.getNext();
            }
            if (rotations.length !== 3) {
                throw 'Battle Armory ' + f.id + ' cache: expected exactly three quaternion pose calls, got ' + rotations.length;
            }

            var pushCall = previousReal(rotations[0]);
            while (pushCall !== null && !(pushCall.getOpcode() === Opcodes.INVOKEVIRTUAL && pushCall.owner === POSESTACK && pushCall.desc === '()V')) {
                pushCall = previousReal(pushCall);
            }
            if (pushCall === null) throw 'Battle Armory ' + f.id + ' cache: pushPose call was not found';

            for (var i = 2; i >= 0; i--) {
                var previousCall = i === 0 ? pushCall : rotations[i - 1];
                var start = nextReal(previousCall);
                var end = rotations[i];
                if (start === null || start.getOpcode() !== Opcodes.ALOAD) {
                    throw 'Battle Armory ' + f.id + ' cache: quaternion receiver load was not found';
                }
                var poseVar = start.var;
                var qcode = new InsnList();
                qcode.add(new VarInsnNode(Opcodes.ALOAD, poseVar));
                if (i === 0) {
                    qcode.add(new VarInsnNode(Opcodes.ALOAD, facingVar));
                    qcode.add(new MethodInsnNode(Opcodes.INVOKESTATIC, HELPER, 'facingQuaternion', '(Ljava/lang/Object;)Ljava/lang/Object;', false));
                } else if (i === 1) {
                    qcode.add(new MethodInsnNode(Opcodes.INVOKESTATIC, HELPER, 'x180Quaternion', '()Ljava/lang/Object;', false));
                } else {
                    qcode.add(new MethodInsnNode(Opcodes.INVOKESTATIC, HELPER, 'z180Quaternion', '()Ljava/lang/Object;', false));
                }
                qcode.add(new TypeInsnNode(Opcodes.CHECKCAST, QUAT));
                qcode.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, POSESTACK, end.name, end.desc, false));
                render.instructions.insertBefore(start, qcode);
                removeRange(render.instructions, start, end);
            }

            return classNode;
        }
    };
}


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

function initializeCoreMod() {
    var result = {};
    for (var i = 0; i < FAMILIES.length; i++) {
        addFamily(result, FAMILIES[i]);
        addMemoryDedupe(result, FAMILIES[i]);
    }

    // F3+T / resource-pack reload can temporarily coexist with the old model graph.
    // Clear Battle Armory's strong geometry/material references before Minecraft begins
    // building the new resource set so the old cached ModelParts are immediately GC-eligible.
    result['battlearmory_resource_reload_clear'] = {
        target: { type: 'CLASS', name: 'net.minecraft.client.Minecraft' },
        transformer: function(classNode) {
            var methods = classNode.methods.iterator();
            var found = null;
            while (methods.hasNext()) {
                var method = methods.next();
                if ((method.name === 'm_91391_' || method.name === 'reloadResourcePacks') &&
                    method.desc === '()Ljava/util/concurrent/CompletableFuture;') {
                    found = method;
                    break;
                }
            }
            if (found === null) throw 'Battle Armory cache: Minecraft resource reload method was not found';
            var call = new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                HELPER,
                'clearForResourceReload',
                '()V',
                false
            );
            found.instructions.insertBefore(found.instructions.getFirst(), call);
            return classNode;
        }
    };

    return result;
}
