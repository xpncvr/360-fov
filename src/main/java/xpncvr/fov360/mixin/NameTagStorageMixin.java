package xpncvr.fov360.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.SubmitNodeCollection;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Quaternionfc;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;
import xpncvr.fov360.Fov360Renderer;

@Mixin(SubmitNodeCollection.class)
public abstract class NameTagStorageMixin {

	@Unique
	private static final Quaternionf panini$look = new Quaternionf();

	@Redirect(
		method = "submitNameTag",
		at = @At(
			value = "INVOKE",
			target = "Lcom/mojang/blaze3d/vertex/PoseStack;rotate(Lorg/joml/Quaternionfc;)V"))
	private void panini$labelTowardEye(PoseStack instance, Quaternionfc orientation, PoseStack poseStack, Vec3 nameTagAttachment) {
		if (Fov360Renderer.capturing && nameTagAttachment != null) {
			Matrix4f m = instance.last().pose();
			Fov360Renderer.billboardRotation(panini$look, m.m30(), m.m31(), m.m32(), false);
			instance.rotate(panini$look);
		} else {
			instance.rotate(orientation);
		}
	}
}
