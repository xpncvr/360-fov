package xpncvr.fov360.mixin;

import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.render.GuiRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import xpncvr.fov360.Fov360Renderer;

@Mixin(GuiRenderer.class)
public abstract class GuiRendererMixin {

	@ModifyArg(
		method = "draw",
		at = @At(
			value = "INVOKE",
			target = "Lcom/mojang/blaze3d/systems/RenderSystem;setProjectionMatrix(Lcom/mojang/renderpearl/api/buffers/GpuBufferSlice;Lcom/mojang/blaze3d/ProjectionType;)V"),
		index = 0)
	private GpuBufferSlice panini$rightHalfProjection(GpuBufferSlice original) {
		if (Fov360Renderer.splitGuiOnRight()) {
			GpuBufferSlice slice = Fov360Renderer.rightHalfGuiProjection(Minecraft.getInstance().getWindow());
			if (slice != null) {
				return slice;
			}
		}
		return original;
	}

	@ModifyArg(
		method = "enableScissor",
		at = @At(
			value = "INVOKE",
			target = "Lcom/mojang/renderpearl/api/commands/RenderPass;enableScissor(IIII)V"),
		index = 0)
	private int panini$scissorRightShift(int x) {
		if (Fov360Renderer.splitGuiOnRight()) {
			return x + Minecraft.getInstance().getWindow().getWidth() / 2;
		}
		return x;
	}
}
