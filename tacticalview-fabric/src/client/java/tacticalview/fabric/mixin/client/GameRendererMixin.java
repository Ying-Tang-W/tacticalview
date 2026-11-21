package tacticalview.fabric.mixin.client;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.client.renderer.GameRenderer;

import tacticalview.fabric.render.TacticalRenderer;

/** 渲染器关闭时释放我们的 GPU 缓冲区。 */
@Mixin(GameRenderer.class)
public class GameRendererMixin {
    @Inject(method = "close", at = @At("RETURN"))
    private void tacticalview$onClose(CallbackInfo ci) {
        TacticalRenderer.close();
    }
}
