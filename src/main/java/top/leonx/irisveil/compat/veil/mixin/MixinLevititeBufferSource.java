package top.leonx.irisveil.compat.veil.mixin;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.vertex.MeshData;
import foundry.veil.api.client.render.rendertype.VeilRenderType;

import top.leonx.irisveil.compat.aeronautics.AeronauticsLevititeCompat;

/** Detach completed fixed meshes so nested views cannot flush main-world builders. */
@Mixin(MultiBufferSource.BufferSource.class)
public abstract class MixinLevititeBufferSource {
    @WrapOperation(method = "endBatch(Lnet/minecraft/client/renderer/RenderType;Lcom/mojang/blaze3d/vertex/BufferBuilder;)V",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/RenderType;draw(Lcom/mojang/blaze3d/vertex/MeshData;)V"))
    private void irisveil$deferNativeLevititeMesh(RenderType type, MeshData mesh, Operation<Void> original) {
        // Avoid loading Veil classes in the optional-mod-absent path.
        if (!AeronauticsLevititeCompat.isVeilPresent()) {
            original.call(type, mesh);
            return;
        }
        String name = VeilRenderType.getName(type);
        if (AeronauticsLevititeCompat.shouldSkipShadow(name)) {
            mesh.close();
            return;
        }
        if ((Object) this == Minecraft.getInstance().renderBuffers().bufferSource()
                && AeronauticsLevititeCompat.deferMesh(name, () -> original.call(type, mesh), mesh::close)) {
            return;
        }
        original.call(type, mesh);
    }
}
