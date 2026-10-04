package top.leonx.irisveil.compat.veil.mixin;

import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.neoforged.fml.ModList;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.vertex.MeshData;
import foundry.veil.api.client.render.rendertype.VeilRenderType;

import top.leonx.irisveil.IrisVeilCompat;
import top.leonx.irisveil.compat.aeronautics.LevititeRenderContext;

/** Distinguishes direct base/ghost fixed-buffer draws outside the stage callback. */
@Mixin(MultiBufferSource.BufferSource.class)
public abstract class MixinLevititeBufferSource {
    @WrapOperation(method = "endBatch(Lnet/minecraft/client/renderer/RenderType;Lcom/mojang/blaze3d/vertex/BufferBuilder;)V",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/RenderType;draw(Lcom/mojang/blaze3d/vertex/MeshData;)V"))
    private void irisveil$scopeLevititeMesh(RenderType type, MeshData mesh, Operation<Void> original) {
        // This mixin targets a vanilla class; leave optional Veil references dormant
        // when Veil is absent, even if Iris happens to be installed and active.
        if (!IrisVeilCompat.isShaderPackInUse() || !ModList.get().isLoaded("veil")) {
            original.call(type, mesh);
            return;
        }
        String name = VeilRenderType.getName(type);
        if (!LevititeRenderContext.isLevititeLayer(name)) {
            original.call(type, mesh);
            return;
        }
        try (var ignored = LevititeRenderContext.enterLayer(name)) {
            original.call(type, mesh);
        }
    }
}
