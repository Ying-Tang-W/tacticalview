package tacticalview.fabric.mixin.client;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.textures.GpuSampler;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.chunk.ChunkSectionLayerGroup;
import net.minecraft.client.renderer.chunk.ChunkSectionsToRender;

import tacticalview.fabric.render.TacticalRenderer;

/**
 * 26.3 的 Fabric API 不直接提供主场景的 RenderPass，所以在半透明地形绘制之后插入我们的绘制。
 * 做法来自 Fabric 官方文档 "Rendering in the World"。
 */
@Mixin(LevelRenderer.class)
public class LevelRendererMixin {
    @WrapOperation(method = "executeClassicTransparency", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/chunk/ChunkSectionsToRender;renderGroup(Lnet/minecraft/client/renderer/chunk/ChunkSectionLayerGroup;Lcom/mojang/renderpearl/api/commands/RenderPass;Lcom/mojang/renderpearl/api/textures/GpuSampler;Lcom/mojang/renderpearl/api/textures/GpuTextureView;Z)V"))
    private void tacticalview$afterTranslucentTerrain(ChunkSectionsToRender instance, ChunkSectionLayerGroup group, RenderPass renderPass, GpuSampler sampler, GpuTextureView atlas, boolean renderWireframeTerrain, Operation<Void> original) {
        original.call(instance, group, renderPass, sampler, atlas, renderWireframeTerrain);
        TacticalRenderer.getInstance().draw(renderPass);
    }
}
