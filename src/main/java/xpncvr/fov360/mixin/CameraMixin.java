package xpncvr.fov360.mixin;

import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.world.level.material.FogType;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import xpncvr.fov360.Fov360Renderer;

@Mixin(Camera.class)
public abstract class CameraMixin {

	@Unique
	private static final float PANINI_CAPTURE_CULL_FOV = 110.0F;

	@Unique
	private static final float PANINI_MAX_VANILLA_FOV = 150.0F;

	@Shadow
	private Frustum cullFrustum;

	@Shadow
	private float fov;

	@Shadow
	private boolean isPanoramicMode;

	@Shadow
	private float depthFar;

	@Shadow
	protected abstract void setRotation(float yRot, float xRot);

	@Shadow
	private void setupPerspective(float zNear, float zFar, float fov, float width, float height) {
		throw new AssertionError();
	}

	@Inject(method = "update", at = @At("TAIL"))
	private void panini$overrideCaptureView(DeltaTracker deltaTracker, CallbackInfo ci) {
		if (!Fov360Renderer.capturing) {
			return;
		}
		Camera self = (Camera) (Object) this;
		this.setRotation(Fov360Renderer.captureFaceYaw, Fov360Renderer.captureFacePitch);

		Matrix4f viewRotation = self.getViewRotationMatrix(new Matrix4f());
		Matrix4f cullProjection = new Matrix4f().perspective(
			PANINI_CAPTURE_CULL_FOV * (float) (Math.PI / 180.0),
			1.0F,
			Camera.PROJECTION_Z_NEAR,
			this.depthFar,
			RenderSystem.getDevice().getDeviceInfo().isZZeroToOne());
		Frustum frustum = new Frustum(viewRotation, cullProjection);
		Vec3 pos = self.position();
		frustum.prepare(pos.x(), pos.y(), pos.z());
		this.cullFrustum = frustum;

		this.setupPerspective(
			Camera.PROJECTION_Z_NEAR,
			this.depthFar,
			90.0F,
			1.0F,
			1.0F);
	}

	@Inject(method = "createProjectionMatrixForCulling", at = @At("HEAD"), cancellable = true)
	private void panini$clampCullingFov(CallbackInfoReturnable<Matrix4f> cir) {
		Minecraft client = Minecraft.getInstance();
		float requested = Math.max(this.fov, client.options.fov().get());
		if (requested <= PANINI_MAX_VANILLA_FOV) {
			return;
		}
		cir.setReturnValue(new Matrix4f().perspective(
			PANINI_MAX_VANILLA_FOV * (float) (Math.PI / 180.0),
			(float) client.getWindow().getWidth() / client.getWindow().getHeight(),
			Camera.PROJECTION_Z_NEAR,
			this.depthFar,
			RenderSystem.getDevice().getDeviceInfo().isZZeroToOne()));
	}

	@Inject(method = "calculateFov", at = @At("RETURN"), cancellable = true)
	private void panini$matchRectilinearFov(float partialTicks, CallbackInfoReturnable<Float> cir) {
		if (this.isPanoramicMode || Fov360Renderer.capturing) {
			return;
		}
		float scale = Fov360Renderer.vanillaFovScale(Minecraft.getInstance());
		if (scale != 1.0F) {
			cir.setReturnValue(cir.getReturnValueF() * scale);
		}
	}

	@Inject(method = "getFluidInCamera", at = @At("HEAD"), cancellable = true)
	private void panini$stableFogType(CallbackInfoReturnable<FogType> cir) {
		if (Fov360Renderer.capturing) {
			cir.setReturnValue(Fov360Renderer.capturedFogType);
		}
	}
}
