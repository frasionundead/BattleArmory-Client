/*
 * Decompiled with CFR 0.152.
 * 
 * Could not load the following classes:
 *  com.mojang.blaze3d.vertex.PoseStack
 *  com.mojang.blaze3d.vertex.VertexConsumer
 *  com.razz.decocraft_nature.DecoException
 *  com.razz.decocraft_nature.client.render.AnimatedRenderer$1
 *  com.razz.decocraft_nature.common.JsonContainer$Entry
 *  com.razz.decocraft_nature.common.blocks.DecocraftBlock
 *  com.razz.decocraft_nature.common.properties.DecoProperty
 *  com.razz.decocraft_nature.common.tileentities.AnimatedTileEntity
 *  com.razz.decocraft_nature.models.bbmodel.BBModel
 *  com.razz.decocraft_nature.models.bbmodel.BBModelParts$Channel
 *  com.razz.decocraft_nature.models.bbmodel.BBModelParts$Element
 *  com.razz.decocraft_nature.models.bbmodel.BBModelParts$ElementBase
 *  com.razz.decocraft_nature.models.bbmodel.BBModelParts$OutlinerCube
 *  com.razz.decocraft_nature.models.bbmodel.BBModelParts$OutlinerGroup
 *  com.razz.decocraft_nature.models.bbmodel.BBModelParts$OutlinerNode
 *  com.razz.decocraft_nature.utils.DecoMath
 *  com.razz.decocraft_nature.utils.vector.Vector3f
 *  net.minecraft.client.model.geom.ModelPart
 *  net.minecraft.client.renderer.MultiBufferSource
 *  net.minecraft.client.renderer.RenderType
 *  net.minecraft.client.renderer.blockentity.BlockEntityRenderer
 *  net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider$Context
 *  net.minecraft.client.renderer.texture.TextureAtlas
 *  net.minecraft.client.resources.model.Material
 *  net.minecraft.core.Direction
 *  net.minecraft.resources.ResourceLocation
 *  net.minecraft.world.level.block.state.BlockState
 *  net.minecraft.world.level.block.state.properties.Property
 *  org.joml.AxisAngle4f
 *  org.joml.Quaternionf
 *  org.joml.Vector3fc
 */
package com.razz.decocraft_nature.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.razz.decocraft_nature.DecoException;
import com.razz.decocraft_nature.client.render.AnimatedModelRenderer;
import com.razz.decocraft_nature.client.render.AnimatedRenderer;
import com.razz.decocraft_nature.common.JsonContainer;
import com.razz.decocraft_nature.common.blocks.DecocraftBlock;
import com.razz.decocraft_nature.common.properties.DecoProperty;
import com.razz.decocraft_nature.common.tileentities.AnimatedTileEntity;
import com.razz.decocraft_nature.models.bbmodel.BBModel;
import com.razz.decocraft_nature.models.bbmodel.BBModelParts;
import com.razz.decocraft_nature.utils.DecoMath;
import com.razz.decocraft_nature.utils.vector.Vector3f;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.resources.model.Material;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import org.joml.AxisAngle4f;
import org.joml.Quaternionf;
import org.joml.Vector3fc;

public class AnimatedRenderer
implements BlockEntityRenderer<AnimatedTileEntity> {
    private static final String ROOT = "ROOT";
    private static Map<String, ModelPart> CACHE = new HashMap<String, ModelPart>();
    private final BlockEntityRendererProvider.Context context;

    public AnimatedRenderer(BlockEntityRendererProvider.Context context) {
        this.context = context;
    }

    public void render(AnimatedTileEntity tileEntity, float partialTick, PoseStack matrixStackIn, MultiBufferSource bufferIn, int combinedLightIn, int combinedOverlayIn) {
        tileEntity.addTimer((double)(partialTick / 20.0f));
        if (!tileEntity.m_58898_()) {
            return;
        }
        BlockState blockstate = tileEntity.m_58904_().m_8055_(tileEntity.m_58899_());
        if (!blockstate.m_61138_((Property)DecoProperty.FACING)) {
            return;
        }
        Direction facing = (Direction)blockstate.m_61143_((Property)DecoProperty.FACING);
        DecocraftBlock block = (DecocraftBlock)blockstate.m_60734_();
        ModelPart model = this.parseModel(block.meta, block.model, tileEntity);
        Material material = new Material(TextureAtlas.f_118259_, new ResourceLocation("decocraft_nature", "block/" + block.meta.material));
        VertexConsumer vertexUV = material.m_119194_(bufferIn, RenderType::m_110473_);
        matrixStackIn.m_85836_();
        matrixStackIn.m_252781_(new Quaternionf(new AxisAngle4f(DecoMath.toRadians((float)facing.m_122435_()), (Vector3fc)Vector3f.YN)));
        matrixStackIn.m_252781_(new Quaternionf(new AxisAngle4f(DecoMath.toRadians((float)180.0f), (Vector3fc)Vector3f.XN)));
        matrixStackIn.m_252781_(new Quaternionf(new AxisAngle4f(DecoMath.toRadians((float)180.0f), (Vector3fc)Vector3f.ZN)));
        switch (1.$SwitchMap$net$minecraft$core$Direction[facing.ordinal()]) {
            case 1: {
                matrixStackIn.m_85837_(0.5, 0.0, 0.5);
                break;
            }
            case 2: {
                matrixStackIn.m_85837_(-0.5, 0.0, -0.5);
                break;
            }
            case 3: {
                matrixStackIn.m_85837_(0.5, 0.0, -0.5);
                break;
            }
            case 4: {
                matrixStackIn.m_85837_(-0.5, 0.0, 0.5);
                break;
            }
        }
        model.m_104301_(matrixStackIn, vertexUV, combinedLightIn, combinedOverlayIn);
        matrixStackIn.m_85849_();
    }

    private ModelPart parseModel(JsonContainer.Entry meta, BBModel bbmodel, AnimatedTileEntity tileEntity) {
        AnimatedModelRenderer model = new AnimatedModelRenderer(bbmodel.resolution.width, bbmodel.resolution.height, null, ROOT);
        Map keyframes = tileEntity.getKeyframes();
        this.parseOutliners(bbmodel.resolution.width, bbmodel.resolution.height, new BBModelParts.ElementBase(), bbmodel.outliner, bbmodel.uuidMap, model, ROOT, keyframes);
        return model;
    }

    private void parseOutliners(int width, int height, BBModelParts.ElementBase topNode, List<BBModelParts.OutlinerNode> nodes, Map<String, BBModelParts.ElementBase> uuidMap, AnimatedModelRenderer topRenderer, String path, Map<String, Map<BBModelParts.Channel, float[]>> keyframes) {
        for (BBModelParts.OutlinerNode outNode : nodes) {
            BBModelParts.OutlinerGroup elementBase;
            if (outNode instanceof BBModelParts.OutlinerGroup) {
                elementBase = (BBModelParts.OutlinerGroup)outNode;
            } else if (outNode instanceof BBModelParts.OutlinerCube) {
                elementBase = uuidMap.get(((BBModelParts.OutlinerCube)outNode).uuid);
            } else {
                throw new DecoException("Error processing the outliners");
            }
            String name = path + "/" + elementBase.name;
            AnimatedModelRenderer modelRenderer = new AnimatedModelRenderer(width, height, Objects.equals(elementBase.type, "cube") ? (BBModelParts.Element)elementBase : null, name);
            float[] animRot = new float[]{0.0f, 0.0f, 0.0f};
            float[] animPos = new float[]{0.0f, 0.0f, 0.0f};
            if (keyframes.get(elementBase.uuid) != null) {
                if (keyframes.get(elementBase.uuid).get(BBModelParts.Channel.ROTATION) != null) {
                    animRot = keyframes.get(elementBase.uuid).get(BBModelParts.Channel.ROTATION);
                }
                if (keyframes.get(elementBase.uuid).get(BBModelParts.Channel.POSITION) != null) {
                    animPos = keyframes.get(elementBase.uuid).get(BBModelParts.Channel.POSITION);
                }
            }
            modelRenderer.m_104227_(elementBase.origin.x - topNode.origin.x - animPos[0], elementBase.origin.y - topNode.origin.y + animPos[1], elementBase.origin.z - topNode.origin.z + animPos[2]);
            modelRenderer.f_104203_ = elementBase.rotation.x - animRot[0];
            modelRenderer.f_104204_ = elementBase.rotation.y - animRot[1];
            modelRenderer.f_104205_ = elementBase.rotation.z + animRot[2];
            topRenderer.addChild(name, modelRenderer);
            if (Objects.equals(elementBase.type, "cube")) {
                BBModelParts.Element element = (BBModelParts.Element)elementBase;
                modelRenderer.addBox(element.from.x - element.origin.x, element.from.y - element.origin.y, element.from.z - element.origin.z, element.to.x - element.from.x, element.to.y - element.from.y, element.to.z - element.from.z);
                continue;
            }
            BBModelParts.OutlinerGroup group = elementBase;
            this.parseOutliners(width, height, (BBModelParts.ElementBase)elementBase, group.children, uuidMap, modelRenderer, name, keyframes);
        }
    }
}
