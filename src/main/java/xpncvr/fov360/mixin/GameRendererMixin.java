package xpncvr.fov360.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.state.level.PlayerRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import xpncvr.fov360.Fov360Renderer;

@Mixin(GameRenderer.class)
public abstract class GameRendererMixin {

	@Inject(method = "renderLevel", at = @At("HEAD"), cancellable = true)
	private void panini$driveLevel(CallbackInfo ci) {
		if (Fov360Renderer.capturing) {
			return;
		}
		Minecraft client = Minecraft.getInstance();
		if (!Fov360Renderer.willCapture(client)) {
			return;
		}
		if (Fov360Renderer.INSTANCE.runFrame((GameRenderer) (Object) this, client.getDeltaTracker())) {
			ci.cancel();
		}
	}

	@Inject(method = "renderItemInHand", at = @At("HEAD"), cancellable = true)
	private void panini$cancelHandDuringCapture(CameraRenderState cameraState, PlayerRenderState playerState,
			GpuTextureView depthTextureView, CallbackInfo ci) {
		if (Fov360Renderer.capturing) {
			ci.cancel();
		}
	}

	@Inject(method = "bobView", at = @At("HEAD"), cancellable = true)
	private void panini$cancelBob(CameraRenderState cameraState, PoseStack poseStack, CallbackInfo ci) {
		if (Fov360Renderer.capturing) {
			ci.cancel();
		}
	}

	@Inject(method = "bobHurt", at = @At("HEAD"), cancellable = true)
	private void panini$cancelTilt(CameraRenderState cameraState, PoseStack poseStack, CallbackInfo ci) {
		if (Fov360Renderer.capturing) {
			ci.cancel();
		}
	}
}
