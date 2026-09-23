package xpncvr.fov360;

import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.blaze3d.buffers.Std140Builder;
import com.mojang.renderpearl.api.device.GpuDevice;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.platform.Window;
import org.joml.Matrix4f;
import org.lwjgl.system.MemoryStack;

import java.nio.ByteBuffer;

public final class Fov360GuiProjection {
	private GpuBuffer buffer = null;
	private GpuBufferSlice slice = null;
	private float width = Float.NaN;
	private float height = Float.NaN;
	private GpuDevice bufferDevice = null;

	GpuBufferSlice slice(Window window) {
		float w = (float) window.getWidth() / window.getGuiScale();
		float h = (float) window.getHeight() / window.getGuiScale();

		GpuDevice device = RenderSystem.getDevice();
		if (buffer == null || bufferDevice != device) {
			buffer = device.createBuffer(
				() -> "fov360 gui projection",
				GpuBuffer.USAGE_UNIFORM | GpuBuffer.USAGE_COPY_DST,
				RenderSystem.PROJECTION_MATRIX_UBO_SIZE);
			slice = buffer.slice(0, RenderSystem.PROJECTION_MATRIX_UBO_SIZE);
			bufferDevice = device;
			width = Float.NaN;
			height = Float.NaN;
		}

		if (!Float.isFinite(w) || !Float.isFinite(h)) {
			return slice;
		}

		if (w != width || h != height) {
			Matrix4f matrix = new Matrix4f()
				.setOrtho(0.0F, w, h, 0.0F, 1000.0F, 11000.0F)
				.translate(w / 2.0F, 0.0F, 0.0F);
			try (MemoryStack stack = MemoryStack.stackPush()) {
				ByteBuffer bytes = Std140Builder.onStack(stack, RenderSystem.PROJECTION_MATRIX_UBO_SIZE)
					.putMat4f(matrix).get();
				RenderSystem.getDevice().createCommandEncoder().writeToBuffer(buffer.slice(), bytes);
			}
			width = w;
			height = h;
		}

		return slice;
	}
}
