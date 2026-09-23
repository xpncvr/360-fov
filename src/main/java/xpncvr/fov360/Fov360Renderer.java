package xpncvr.fov360;

import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.blaze3d.buffers.Std140Builder;
import com.mojang.blaze3d.buffers.Std140SizeCalculator;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.pipeline.BindGroupLayout;
import com.mojang.renderpearl.api.pipeline.ColorTargetState;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.platform.Window;
import com.mojang.renderpearl.api.pipeline.UniformType;
import com.mojang.renderpearl.api.commands.CommandEncoder;
import com.mojang.renderpearl.api.device.GpuDevice;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.textures.FilterMode;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.state.GameRenderState;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;
import net.minecraft.util.profiling.Profiler;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.material.FogType;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import xpncvr.fov360.mixin.GameRendererInvoker;
import xpncvr.fov360.mixin.LevelRendererAccessor;

import java.util.Optional;
import java.util.OptionalDouble;

public final class Fov360Renderer {
	public static final Fov360Renderer INSTANCE = new Fov360Renderer();

	private static final double DEG2RAD = Math.PI / 180.0;

	private static final float CUBE_MIN_FOV = 90.0F;

	private static final int MASK_GRID = 32;

	public static volatile RenderTarget currentTarget = null;
	public static volatile boolean capturing = false;
	public static volatile boolean captureOutlines = false;
	public static volatile float captureViewYaw = 0.0F;
	public static volatile float captureFaceYaw = 0.0F;
	public static volatile float captureFacePitch = 0.0F;
	public static final Vector3f captureViewForward = new Vector3f(0.0F, 0.0F, -1.0F);
	public static volatile FogType capturedFogType = FogType.NONE;


	private Fov360Config config = null;

	private final RenderTarget[] faces = new RenderTarget[6];
	private final int[] faceSizes = new int[6];
	private final boolean[] faceEnabled = new boolean[6];

	private final RenderTarget[] outlineFaces = new RenderTarget[6];
	private final int[] outlineFaceSizes = new int[6];

	private final Fov360GuiProjection guiProjection = new Fov360GuiProjection();

	private RenderPipeline pipeline = null;
	private GpuBuffer uboBuffer = null;
	private GpuBuffer uboBufferOutline = null;
	private int uboSize = -1;
	private GpuDevice resourceDevice = null;

	private RenderTarget savedOutlineTarget = null;
	private LevelRendererAccessor outlineRedirect = null;

	private final Matrix4f[] coordFrames = new Matrix4f[6];
	private final Vector3f[] faceForward = new Vector3f[6];
	private final Quaternionf qFace = new Quaternionf();
	private final Quaternionf qPlayer = new Quaternionf();
	private final Quaternionf qTmp = new Quaternionf();
	private final Vector3f rayOut = new Vector3f();
	private final Vector3f rayA = new Vector3f();
	private final Vector3f rayB = new Vector3f();
	private final Vector3f hybA = new Vector3f();
	private final Vector3f hybB = new Vector3f();

	private float scaleStd, scalePan, scaleSte, scaleMer, scaleEqu, scaleFish;

	private Fov360Renderer() {
		for (int i = 0; i < 6; i++) {
			coordFrames[i] = new Matrix4f();
			faceForward[i] = new Vector3f();
		}
	}

	private Fov360Config config() {
		if (config == null) {
			config = Fov360Config.load();
		}
		return config;
	}

	public boolean shouldRun(Minecraft client) {
		return client.level != null && client.player != null;
	}

	public static boolean willCapture(Minecraft client) {
		if (client == null || client.player == null || !INSTANCE.shouldRun(client)) {
			return false;
		}
		return INSTANCE.config().splitScreen || INSTANCE.fovx(client) >= CUBE_MIN_FOV;
	}

	public static boolean splitGuiActive() {
		Minecraft client = Minecraft.getInstance();
		return client != null
			&& client.level != null
			&& INSTANCE.shouldRun(client)
			&& INSTANCE.config().splitScreen;
	}

	public static boolean splitGuiOnRight() {
		return splitGuiActive() && INSTANCE.config().invertSplitScreen;
	}

	public static RenderTarget skyTarget(RenderTarget cached) {
		RenderTarget target = currentTarget;
		if (target != null) {
			return target;
		}
		Minecraft client = Minecraft.getInstance();
		GameRenderer gameRenderer = client == null ? null : client.gameRenderer;
		return gameRenderer == null ? cached : gameRenderer.mainRenderTarget();
	}

	public static GpuBufferSlice rightHalfGuiProjection(Window window) {
		try {
			return INSTANCE.guiProjection.slice(window);
		} catch (Throwable t) {
			INSTANCE.fail(t);
			return null;
		}
	}

	private Quaternionf q(Quaternionf dest, float yaw, float pitch) {
		return dest.rotationYXZ(
			(float) Math.PI - yaw * (float) DEG2RAD,
			-pitch * (float) DEG2RAD,
			0.0F);
	}

	public static void billboardRotation(Quaternionf dest, double dx, double dy, double dz, boolean yawOnly) {
		double h2 = dx * dx + dz * dz;
		double dist = Math.sqrt(h2 + dy * dy);
		float lookYaw;
		float lookPitch;
		if (h2 < 1.0e-8) {
			lookYaw = captureViewYaw;
			lookPitch = yawOnly ? 0.0F : (dy > 0.0 ? -90.0F : 90.0F);
		} else {
			lookYaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
			lookPitch = (dist < 1.0e-8 || yawOnly) ? 0.0F : (float) -Math.toDegrees(Math.asin(dy / dist));
		}
		INSTANCE.q(dest, lookYaw, lookPitch);
	}

	private static final Quaternionf billboardScratch = new Quaternionf();

	public static Quaternionf billboardOrientation(PoseStack poseStack, Quaternionf faceOrientation, boolean yawOnly) {
		if (!capturing) {
			return faceOrientation;
		}
		Matrix4f m = poseStack.last().pose();
		billboardRotation(billboardScratch, m.m30(), m.m31(), m.m32(), yawOnly);
		return billboardScratch;
	}

	public boolean runFrame(GameRenderer gameRenderer, DeltaTracker deltaTracker) {
		Minecraft client = Minecraft.getInstance();
		Player player = client.player;
		if (player == null) {
			return false;
		}

		outlineRedirect = null;

		try {
			float rawFov = fovx(client);
			boolean split = config().splitScreen;
			boolean invert = config().invertSplitScreen;

			if (!split && rawFov < CUBE_MIN_FOV) {
				return false;
			}

			RenderTarget main = gameRenderer.mainRenderTarget();
			int outH = main.height;
			float projW = main.width / (split ? 2.0F : 1.0F);
			float aspect = projW / (float) outH;

			float fovx = split ? rawFov : remapBoundaryFovx(rawFov, aspect);

			computeScales(fovx);

			Camera realCamera = gameRenderer.mainCamera();
			float worldPartialTicks = deltaTracker.getGameTimeDeltaPartialTick(false);
			float cameraPartialTicks = realCamera.getCameraEntityPartialTicks(deltaTracker);

			Entity cameraEntity = client.getCameraEntity() == null ? player : client.getCameraEntity();
			float viewYaw = cameraEntity.getViewYRot(cameraPartialTicks);
			float viewPitch = cameraEntity.getViewXRot(cameraPartialTicks);

			if (client.options.getCameraType().isMirrored()) {
				viewYaw += 180.0F;
				viewPitch = -viewPitch;
			}

			q(qPlayer, viewYaw, viewPitch);
			captureViewYaw = viewYaw;
			captureViewForward.set(0.0F, 0.0F, -1.0F).rotate(qPlayer);

			for (int k = 0; k < 6; k++) {
				float faceYaw = faceYaw(viewYaw, k);
				float facePitch = facePitch(k);
				q(qFace, faceYaw, facePitch).conjugate(qTmp).mul(qPlayer);
				coordFrames[k].rotation(qTmp);
				faceForward[k].set(-coordFrames[k].m02(), -coordFrames[k].m12(), -coordFrames[k].m22());
			}

			capturedFogType = realCamera.getFluidInCamera();

			computeFaceMask(fovx, aspect, split, invert, viewPitch);

			screenToRay(0.0F, 0.0F, fovx, viewPitch, false);
			int centerFace = faceIndexOf(rayOut);

			int fullSize = requiredFaceSize(projW, fovx);
			int lowSize = config().lowResTopBottomFaces ? halvedSize(fullSize) : fullSize;
			ensureResources(fullSize, lowSize, centerFace);

			GameRendererInvoker inv = (GameRendererInvoker) gameRenderer;

			capturing = true;
			beginOutlineCapture(client);

			for (int k = 0; k < 6; k++) {
				if (!faceEnabled[k]) {
					continue;
				}
				captureFaceYaw = faceYaw(viewYaw, k);
				captureFacePitch = facePitch(k);
				currentTarget = faces[k];
				if (outlineRedirect != null) {
					outlineRedirect.panini$setEntityOutlineTarget(outlineFaces[k]);
				}

				realCamera.update(deltaTracker);
				inv.panini$extractCamera(deltaTracker, worldPartialTicks);
				client.levelExtractor.extract(deltaTracker, realCamera, worldPartialTicks);
				gameRenderer.renderLevel();
			}

			currentTarget = null;
			capturing = false;
			endOutlineCapture();

			realCamera.update(deltaTracker);
			inv.panini$extractCamera(deltaTracker, worldPartialTicks);

			reproject(client, viewPitch, outH, projW, aspect, split, invert, fovx);
			reprojectOutline(projW, outH, split, invert, fovx, viewPitch);
			if (!split) {
				renderHand(gameRenderer);
			}
			return true;
		} catch (Throwable t) {
			fail(t);
			return false;
		} finally {
			currentTarget = null;
			capturing = false;
			endOutlineCapture();
			outlineRedirect = null;
		}
	}

	private static float faceYaw(float viewYaw, int k) {
		switch (k) {
			case 1: return viewYaw + 90.0F;
			case 2: return viewYaw + 180.0F;
			case 3: return viewYaw - 90.0F;
			default: return viewYaw;
		}
	}

	private static float facePitch(int k) {
		switch (k) {
			case 4: return -90.0F;
			case 5: return 90.0F;
			default: return 0.0F;
		}
	}

	private void computeScales(float fovx) {
		double rHalf = fovx * DEG2RAD / 2.0;
		scaleStd = (float) Math.tan(rHalf);
		scalePan = (float) ((2.0 / (1.0 + Math.cos(rHalf))) * Math.sin(rHalf));
		scaleSte = (float) Math.tan(rHalf / 2.0);
		scaleMer = (float) rHalf;
		scaleEqu = (float) rHalf;
		scaleFish = (float) rHalf;
	}

	private void computeFaceMask(float fovx, float aspect, boolean split, boolean invert, float pitchDeg) {
		for (int i = 0; i < 6; i++) {
			faceEnabled[i] = false;
		}
		for (int iy = 0; iy <= MASK_GRID; iy++) {
			float ty = iy / (float) MASK_GRID;
			for (int ix = 0; ix <= MASK_GRID; ix++) {
				float tx = ix / (float) MASK_GRID;
				boolean rear = false;
				float ux = tx;
				if (split) {
					rear = (tx >= 0.5F) != invert;
					ux = (tx * 2.0F) % 1.0F;
				}
				float sx = (ux - 0.5F) * 2.0F;
				float sy = (ty - 0.5F) * (2.0F / aspect);
				if (Math.abs(sx) > 1.0F) {
					continue;
				}
				if (!screenToRay(sx, sy, fovx, pitchDeg, rear)) {
					continue;
				}
				faceEnabled[faceIndexOf(rayOut)] = true;
			}
		}
		faceEnabled[0] = true;
	}

	private int faceIndexOf(Vector3f ray) {
		int index = 0;
		float best = -2.0F;
		for (int k = 0; k < 6; k++) {
			float d = ray.dot(faceForward[k]);
			if (d > best) {
				best = d;
				index = k;
			}
		}
		return index;
	}

	private void latlonToRay(Vector3f d, double lat, double lon) {
		double cl = Math.cos(lat);
		d.set((float) (Math.sin(lon) * cl), (float) Math.sin(lat), (float) (Math.cos(lon) * cl));
	}

	private void standardRay(Vector3f d, float cx, float cy) {
		double x = cx * scaleStd, y = cy * scaleStd;
		double r = Math.sqrt(x * x + y * y);
		if (r < 1.0e-9) {
			d.set(0.0F, 0.0F, 1.0F);
			return;
		}
		double th = Math.atan(r), s = Math.sin(th);
		d.set((float) (x / r * s), (float) (y / r * s), (float) Math.cos(th));
	}

	private void paniniRay(Vector3f d, float cx, float cy) {
		double x = cx * scalePan, y = cy * scalePan;
		double k = x * x / 4.0;
		double dscr = k * k - (k + 1.0) * (k - 1.0);
		double clon = (-k + Math.sqrt(dscr)) / (k + 1.0);
		double S = 2.0 / (1.0 + clon);
		latlonToRay(d, Math.atan2(y, S), Math.atan2(x, S * clon));
	}

	private void stereoRay(Vector3f d, float cx, float cy) {
		double x = cx * scaleSte, y = cy * scaleSte;
		double r = Math.sqrt(x * x + y * y);
		if (r < 1.0e-9) {
			d.set(0.0F, 0.0F, 1.0F);
			return;
		}
		double th = Math.atan(r) / 0.5, s = Math.sin(th);
		d.set((float) (x / r * s), (float) (y / r * s), (float) Math.cos(th));
	}

	private void fisheyeRay(Vector3f d, float cx, float cy) {
		double x = cx * scaleFish, y = cy * scaleFish;
		double r = Math.sqrt(x * x + y * y);
		if (r < 1.0e-9) {
			d.set(0.0F, 0.0F, 1.0F);
			return;
		}
		double th = r, s = Math.sin(th);
		d.set((float) (x / r * s), (float) (y / r * s), (float) Math.cos(th));
	}

	private void mercatorRay(Vector3f d, float cx, float cy) {
		double x = cx * scaleMer, y = cy * scaleMer;
		latlonToRay(d, Math.atan(Math.sinh(y)), x);
	}

	private boolean equirectRay(Vector3f d, float cx, float cy) {
		double x = cx * scaleEqu, y = cy * scaleEqu;
		if (Math.abs(y) > Math.PI / 2.0) {
			d.set(0.0F, 0.0F, 0.0F);
			return false;
		}
		latlonToRay(d, y, x);
		return true;
	}

	private void hybridRay(Vector3f d, float cx, float cy, float pitchDeg) {
		paniniRay(hybA, cx, cy);
		stereoRay(hybB, cx, cy);
		d.set(hybA).lerp(hybB, Math.abs(pitchDeg) / 90.0F);
	}

	private boolean screenToRay(float cx, float cy, float fovx, float pitchDeg, boolean rear) {
		if (fovx < 90.0F) {
			standardRay(rayOut, cx, cy);
		} else if (fovx < 160.0F) {
			double lin = (fovx - 90.0) / 70.0;
			float p = (float) (1.0 - (lin - 1.0) * (lin - 1.0));
			standardRay(rayA, cx, cy);
			hybridRay(rayB, cx, cy, pitchDeg);
			rayOut.set(rayA).lerp(rayB, p);
		} else if (fovx < 220.0F) {
			double lin = (fovx - 160.0) / 60.0;
			float p = (float) (1.0 - (lin - 1.0) * (lin - 1.0));
			hybridRay(rayA, cx, cy, pitchDeg);
			fisheyeRay(rayB, cx, cy);
			rayOut.set(rayA).lerp(rayB, p);
		} else if (fovx < 300.0F) {
			double lin = (fovx - 220.0) / 80.0;
			float p = (float) (1.0 - (lin - 1.0) * (lin - 1.0));
			fisheyeRay(rayA, cx, cy);
			mercatorRay(rayB, cx, cy);
			rayOut.set(rayA).lerp(rayB, p);
		} else if (fovx < 340.0F) {
			mercatorRay(rayOut, cx, cy);
		} else if (fovx < 360.0F) {
			mercatorRay(rayA, cx, cy);
			if (!equirectRay(rayB, cx, cy)) {
				rayB.set(0.0F, 0.0F, 0.0F);
			}
			rayOut.set(rayA).lerp(rayB, (fovx - 340.0F) / 20.0F);
		} else if (!equirectRay(rayOut, cx, cy)) {
			return false;
		}
		rayOut.z = -rayOut.z;
		if (rear) {
			rayOut.x = -rayOut.x;
			rayOut.z = -rayOut.z;
		}
		return rayOut.lengthSquared() > 1.0e-12F;
	}

	private int requiredFaceSize(float projW, float fovx) {
		double rad = fovx * DEG2RAD;
		double panini = projW / (4.0 * Math.tan(Math.min(fovx, 290.0) * DEG2RAD / 4.0));
		double mercator = projW / rad;
		double density = Math.max(panini, mercator);
		if (fovx < 160.0) {
			density = Math.max(density, projW / (2.0 * Math.tan(rad / 2.0)));
		}
		int rounded = (int) (Math.ceil(2.0 * density / 128.0) * 128);
		int cap = Mth.clamp(config().faceSizeCap, 256, 4096);
		return Mth.clamp(rounded, 256, cap);
	}

	private int halvedSize(int fullSize) {
		int rounded = ((fullSize / 2 + 127) / 128) * 128;
		return Math.max(256, rounded);
	}

	private void ensureResources(int fullSize, int lowSize, int centerFace) {
		GpuDevice device = RenderSystem.getDevice();
		if (resourceDevice != device) {
			for (int i = 0; i < 6; i++) {
				faces[i] = null;
				faceSizes[i] = 0;
				outlineFaces[i] = null;
				outlineFaceSizes[i] = 0;
			}
			pipeline = null;
			uboBuffer = null;
			uboBufferOutline = null;
			uboSize = -1;
			resourceDevice = device;
		}

		for (int k = 0; k < 6; k++) {
			if (!faceEnabled[k]) {
				continue;
			}
			int target = ((k == 4 || k == 5) && k != centerFace) ? lowSize : fullSize;
			if (faces[k] == null) {
				faces[k] = new TextureTarget("fov360_face_" + k, target, target, GpuFormat.RGBA8_UNORM, GpuFormat.D32_FLOAT);
			} else if (faceSizes[k] != target) {
				faces[k].resize(target, target);
			}
			if (outlineFaces[k] == null) {
				outlineFaces[k] = new TextureTarget("fov360_outline_" + k, target, target, GpuFormat.RGBA8_UNORM, GpuFormat.D32_FLOAT);
				outlineFaceSizes[k] = target;
			} else if (outlineFaceSizes[k] != target) {
				outlineFaces[k].resize(target, target);
				outlineFaceSizes[k] = target;
			}
			if (faceSizes[k] == target) {
				continue;
			}
			faceSizes[k] = target;
		}

		if (pipeline == null) {
			RenderPipeline.Builder builder = RenderPipeline.builder(RenderPipelines.POST_PROCESSING_SNIPPET)
				.withLocation(Identifier.fromNamespaceAndPath("fov360", "pipeline/fov360"))
				.withVertexShader(Identifier.withDefaultNamespace("core/screenquad"))
				.withFragmentShader(Identifier.fromNamespaceAndPath("fov360", "post/fov360"));
			BindGroupLayout.Builder bindGroup = BindGroupLayout.builder();
			for (int i = 0; i < 6; i++) {
				bindGroup.withUniform("Face" + i + "Sampler", UniformType.COMBINED_IMAGE_SAMPLER);
			}
			bindGroup.withUniform("PaniniConfig", UniformType.UNIFORM_BUFFER);
			builder.withBindGroupLayout(bindGroup.build());
			builder.withColorTargetState(ColorTargetState.DEFAULT);
			pipeline = builder.build();
		}

		if (uboBuffer == null) {
			Std140SizeCalculator calc = new Std140SizeCalculator();
			for (int i = 0; i < 6; i++) {
				calc.putMat4f();
			}
			for (int i = 0; i < 6; i++) {
				calc.putVec4();
			}
			uboSize = calc.get();
			uboBuffer = device.createBuffer(
				() -> "PaniniConfig",
				GpuBuffer.USAGE_UNIFORM | GpuBuffer.USAGE_MAP_WRITE,
				uboSize);
		}

		if (uboBufferOutline == null) {
			uboBufferOutline = device.createBuffer(
				() -> "PaniniConfigOutline",
				GpuBuffer.USAGE_UNIFORM | GpuBuffer.USAGE_MAP_WRITE,
				uboSize);
		}
	}

	private void reproject(Minecraft client, float viewPitch, int outH, float projW,
			float aspect, boolean split, boolean invert, float fovx) {
		RenderTarget out = client.gameRenderer.mainRenderTarget();
		CommandEncoder encoder = RenderSystem.getDevice().createCommandEncoder();
		writeUbo(encoder, uboBuffer, fovx, aspect, viewPitch, projW, outH, split, invert, false);
		runReprojectPass(encoder, "fov360 reproject", uboBuffer, faces, out);
	}

	private void reprojectOutline(float projW, int outH, boolean split, boolean invert, float fovx,
			float viewPitch) {
		if (outlineRedirect == null || savedOutlineTarget == null) {
			return;
		}
		float aspect = projW / (float) outH;
		CommandEncoder encoder = RenderSystem.getDevice().createCommandEncoder();
		writeUbo(encoder, uboBufferOutline, fovx, aspect, viewPitch, projW, outH, split, invert, true);
		runReprojectPass(encoder, "fov360 outline reproject", uboBufferOutline, outlineFaces, savedOutlineTarget);
	}

	private void writeUbo(CommandEncoder encoder, GpuBuffer ubo, float fovx, float aspect, float viewPitch,
			float projW, int outH, boolean split, boolean invert, boolean outline) {
		float aa = Mth.clamp(config().antialiasSamples, 1, 4);
		try (GpuBufferSlice.MappedView view = ubo.map(false, true)) {
			Std140Builder b = Std140Builder.intoBuffer(view.data());
			for (int i = 0; i < 6; i++) {
				b.putMat4f(coordFrames[i]);
			}
			b.putVec4(fovx, aspect, viewPitch, aa);
			b.putVec4(projW, (float) outH, split ? 1.0F : 0.0F, invert ? 1.0F : 0.0F);
			b.putVec4(scaleStd, scalePan, scaleSte, scaleMer);
			b.putVec4(scaleEqu, outline ? 1.0F : 0.0F, scaleFish, 0.0F);
			b.putVec4(en(0), en(1), en(2), en(3));
			b.putVec4(en(4), en(5), 0.0F, 0.0F);
		}
	}

	private void runReprojectPass(CommandEncoder encoder, String label, GpuBuffer ubo,
			RenderTarget[] srcFaces, RenderTarget out) {
		try (RenderPass pass = encoder.createRenderPass(
				() -> label,
				out.getColorTextureView(), Optional.empty(),
				null, OptionalDouble.empty())) {
			pass.setPipeline(RenderSystem.getCompiledPipeline(pipeline));
			RenderSystem.bindDefaultUniforms(pass);
			pass.setUniform("PaniniConfig", ubo);
			for (int i = 0; i < 6; i++) {
				RenderTarget f = (faceEnabled[i] && srcFaces[i] != null) ? srcFaces[i] : srcFaces[0];
				pass.setUniform("Face" + i + "Sampler", f.getColorTextureView(), RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST));
			}
			pass.draw(3, 1, 0, 0);
		}
	}

	private void beginOutlineCapture(Minecraft client) {
		LevelRendererAccessor lr = (LevelRendererAccessor) client.levelRenderer;
		RenderTarget real = lr.panini$getEntityOutlineTarget();
		if (real == null) {
			outlineRedirect = null;
			savedOutlineTarget = null;
			captureOutlines = false;
			return;
		}
		outlineRedirect = lr;
		savedOutlineTarget = real;
		captureOutlines = true;
	}

	private void endOutlineCapture() {
		captureOutlines = false;
		if (outlineRedirect != null) {
			outlineRedirect.panini$setEntityOutlineTarget(savedOutlineTarget);
		}
	}

	private void renderHand(GameRenderer gameRenderer) {
		GameRenderState renderState = gameRenderer.gameRenderState();
		ProfilerFiller profiler = Profiler.get();
		profiler.push("fov360_hud");
		((GameRendererInvoker) gameRenderer).panini$render3dHud(
			renderState.levelRenderState.cameraRenderState,
			renderState.levelRenderState.playerRenderState,
			renderState.optionsRenderState,
			false);
	}

	private float en(int i) {
		return faceEnabled[i] ? 1.0F : 0.0F;
	}

	private float fovx(Minecraft client) {
		int fovOption = client.options.fov().get();
		return Mth.clamp(fovOption, 30.0F, 400.0F);
	}

	private float remapBoundaryFovx(float rawFov, float aspect) {
		if (rawFov >= 180.0F) {
			return rawFov;
		}
		float boundaryHorizontal = (float) Math.toDegrees(2.0 * Math.atan(aspect));
		float offset = boundaryHorizontal - 90.0F;
		float falloff = (180.0F - rawFov) / 90.0F;
		return rawFov + offset * falloff;
	}

	private void fail(Throwable t) {
		Main.LOGGER.error("360 FOV effect failed; this usually means another mod changed rendering internals the mixins depend on, check for mod incompatibilities before reporting", t);
		throw new RuntimeException("360 FOV effect failed, likely a mod incompatibility", t);
	}
}
