package xpncvr.fov360.mixin;

import net.minecraft.client.renderer.MappableRingBuffer;
import net.minecraft.client.renderer.PostPass;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import xpncvr.fov360.Fov360Renderer;

@Mixin(PostPass.class)
public abstract class PostPassMixin {

	@Shadow
	@Final
	private String name;

	@Shadow
	@Final
	private MappableRingBuffer infoUbo;

	@Unique
	private final MappableRingBuffer[] panini$faceInfoUbos = new MappableRingBuffer[6];

	@Redirect(
		method = "lambda$addToFrame$1",
		at = @At(
			value = "FIELD",
			opcode = Opcodes.GETFIELD,
			target = "Lnet/minecraft/client/renderer/PostPass;infoUbo:Lnet/minecraft/client/renderer/MappableRingBuffer;"))
	private MappableRingBuffer panini$perFaceInfoUbo(PostPass self) {
		if (!Fov360Renderer.capturing) {
			return this.infoUbo;
		}
		int face = Fov360Renderer.captureFace;
		MappableRingBuffer ubo = this.panini$faceInfoUbos[face];
		if (ubo == null) {
			String label = this.name + " SamplerInfo face " + face;
			ubo = new MappableRingBuffer(() -> label, 130, this.infoUbo.size());
			this.panini$faceInfoUbos[face] = ubo;
		}
		return ubo;
	}

	@Inject(method = "close", at = @At("TAIL"))
	private void panini$closeFaceInfoUbos(CallbackInfo ci) {
		for (int i = 0; i < this.panini$faceInfoUbos.length; i++) {
			if (this.panini$faceInfoUbos[i] != null) {
				this.panini$faceInfoUbos[i].close();
				this.panini$faceInfoUbos[i] = null;
			}
		}
	}
}
