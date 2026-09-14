/*
 * Decompiled with CFR 0.152.
 * 
 * Could not load the following classes:
 *  com.mojang.blaze3d.vertex.PoseStack
 *  com.razz.decocraft_nature.client.render.AnimatedModelRenderer$ModelBox
 *  com.razz.decocraft_nature.common.tileentities.AnimatedTileEntity
 *  com.razz.decocraft_nature.models.bbmodel.BBModelParts$Element
 *  com.razz.decocraft_nature.utils.DecoMath
 *  com.razz.decocraft_nature.utils.vector.Vector3f
 *  net.minecraft.client.model.geom.ModelPart
 *  net.minecraft.core.Direction
 *  net.minecraftforge.api.distmarker.Dist
 *  net.minecraftforge.api.distmarker.OnlyIn
 *  org.joml.AxisAngle4f
 *  org.joml.Quaternionf
 *  org.joml.Vector3fc
 */
package com.razz.decocraft_nature.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.razz.decocraft_nature.client.render.AnimatedModelRenderer;
import com.razz.decocraft_nature.common.tileentities.AnimatedTileEntity;
import com.razz.decocraft_nature.models.bbmodel.BBModelParts;
import com.razz.decocraft_nature.utils.DecoMath;
import com.razz.decocraft_nature.utils.vector.Vector3f;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Set;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.core.Direction;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import org.joml.AxisAngle4f;
import org.joml.Quaternionf;
import org.joml.Vector3fc;

@OnlyIn(value=Dist.CLIENT)
public class AnimatedModelRenderer
extends ModelPart {
    private static final Set<Direction> FACES = Set.of(Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST, Direction.UP, Direction.DOWN);
    public final String name;
    private final BBModelParts.Element element;
    private final int xTexSize;
    private final int yTexSize;
    private long index = 0L;

    public AnimatedModelRenderer(int width, int height, BBModelParts.Element element, String name) {
        super(new ArrayList(), new HashMap());
        this.element = element;
        this.name = name;
        this.xTexSize = width;
        this.yTexSize = height;
        AnimatedTileEntity.isUnmodifiable = Collections.unmodifiableMap(this.f_104213_).getClass().isInstance(this.f_104213_);
    }

    public void addChild(String name, ModelPart part) {
        if (AnimatedTileEntity.isUnmodifiable) {
            return;
        }
        this.f_104213_.put(name + "_" + this.index++, part);
    }

    public ModelPart addBox(float x, float y, float z, float dimX, float dimY, float dimZ) {
        this.addBox(x, y, z, dimX, dimY, dimZ, 0.0f, 0.0f, 0.0f, false);
        return this;
    }

    public void addBox(float x, float y, float z, float dimX, float dimY, float dimZ, float growX, float growY, float growZ, boolean mirror) {
        if (AnimatedTileEntity.isUnmodifiable) {
            return;
        }
        this.f_104212_.add(new ModelBox(x, y, z, dimX, dimY, dimZ, growX, growY, growZ, mirror, (float)this.xTexSize, (float)this.yTexSize, this.element));
    }

    public void m_104299_(PoseStack matrixIn) {
        matrixIn.m_85837_((double)(this.f_104200_ / 16.0f), (double)(this.f_104201_ / 16.0f), (double)(this.f_104202_ / 16.0f));
        if (this.f_104205_ != 0.0f) {
            matrixIn.m_252781_(new Quaternionf(new AxisAngle4f(DecoMath.toRadians((float)this.f_104205_), (Vector3fc)Vector3f.ZP)));
        }
        if (this.f_104204_ != 0.0f) {
            matrixIn.m_252781_(new Quaternionf(new AxisAngle4f(DecoMath.toRadians((float)this.f_104204_), (Vector3fc)Vector3f.YP)));
        }
        if (this.f_104203_ != 0.0f) {
            matrixIn.m_252781_(new Quaternionf(new AxisAngle4f(DecoMath.toRadians((float)this.f_104203_), (Vector3fc)Vector3f.XP)));
        }
    }
}
