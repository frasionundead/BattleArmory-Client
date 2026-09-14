/*
 * Decompiled with CFR 0.152.
 * 
 * Could not load the following classes:
 *  com.mojang.blaze3d.vertex.PoseStack
 *  com.starfish_studios.another_furniture.block.entity.ServiceBellBlockEntity
 *  net.minecraft.client.model.geom.ModelLayerLocation
 *  net.minecraft.client.model.geom.ModelPart
 *  net.minecraft.client.model.geom.PartPose
 *  net.minecraft.client.model.geom.builders.CubeListBuilder
 *  net.minecraft.client.model.geom.builders.LayerDefinition
 *  net.minecraft.client.model.geom.builders.MeshDefinition
 *  net.minecraft.client.model.geom.builders.PartDefinition
 *  net.minecraft.client.renderer.MultiBufferSource
 *  net.minecraft.client.renderer.RenderType
 *  net.minecraft.client.renderer.blockentity.BlockEntityRenderer
 *  net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider$Context
 *  net.minecraft.client.renderer.texture.TextureAtlas
 *  net.minecraft.client.resources.model.Material
 *  net.minecraft.resources.ResourceLocation
 *  net.minecraftforge.api.distmarker.Dist
 *  net.minecraftforge.api.distmarker.OnlyIn
 */
package com.starfish_studios.another_furniture.client.renderer.blockentity;

import com.mojang.blaze3d.vertex.PoseStack;
import com.starfish_studios.another_furniture.block.entity.ServiceBellBlockEntity;
import net.minecraft.client.model.geom.ModelLayerLocation;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.geom.PartPose;
import net.minecraft.client.model.geom.builders.CubeListBuilder;
import net.minecraft.client.model.geom.builders.LayerDefinition;
import net.minecraft.client.model.geom.builders.MeshDefinition;
import net.minecraft.client.model.geom.builders.PartDefinition;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.resources.model.Material;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

@OnlyIn(value=Dist.CLIENT)
public class ServiceBellButtonRenderer
implements BlockEntityRenderer<ServiceBellBlockEntity> {
    public static final Material BELL_TEXTURE = new Material(TextureAtlas.f_118259_, new ResourceLocation("another_furniture", "block/service_bell"));
    public static ModelLayerLocation SERVICE_BELL_MODEL = new ModelLayerLocation(new ResourceLocation("another_furniture", "service_bell"), "service_bell");
    private final ModelPart button;

    public ServiceBellButtonRenderer(BlockEntityRendererProvider.Context context) {
        this.button = context.m_173582_(SERVICE_BELL_MODEL);
    }

    public static LayerDefinition createBodyLayer() {
        MeshDefinition meshdefinition = new MeshDefinition();
        PartDefinition partdefinition = meshdefinition.m_171576_();
        partdefinition.m_171599_("button", CubeListBuilder.m_171558_().m_171514_(19, 11).m_171481_(-9.0f, -7.0f, 7.0f, 2.0f, 1.0f, 2.0f), PartPose.f_171404_);
        partdefinition.m_171597_("button").m_171599_("button_shaft", CubeListBuilder.m_171558_().m_171514_(0, 0).m_171481_(-8.5f, -6.0f, 7.5f, 1.0f, 1.0f, 1.0f), PartPose.f_171404_);
        return LayerDefinition.m_171565_((MeshDefinition)meshdefinition, (int)32, (int)32);
    }

    public void render(ServiceBellBlockEntity blockEntity, float partialTick, PoseStack poseStack, MultiBufferSource bufferSource, int packedLight, int packedOverlay) {
        float f3 = 0.0f;
        if (blockEntity.pressed) {
            f3 = -((float)blockEntity.ticks / 25.0f);
        }
        poseStack.m_252880_(0.0f, f3, 0.0f);
        poseStack.m_85836_();
        poseStack.m_85841_(-1.0f, -1.0f, 1.0f);
        this.button.m_104301_(poseStack, BELL_TEXTURE.m_119194_(bufferSource, RenderType::m_110446_), packedLight, packedOverlay);
        poseStack.m_85849_();
    }
}
