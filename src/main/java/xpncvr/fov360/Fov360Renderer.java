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
import org.joml.Vector4f;
import xpncvr.fov360.mixin.GameRendererInvoker;
import xpncvr.fov360.mixin.LevelRendererAccessor;

import java.util.Arrays;
import java.util.Optional;
import java.util.OptionalDouble;

public final class Fov360Renderer {
	public static final Fov360Renderer INSTANCE = new Fov360Renderer();

	private static final double DEG2RAD = Math.PI / 180.0;

	private static final int MASK_GRID = 32;

	private static final int MIN_SLIDER_FOV = 30;

	private static final int MAX_SLIDER_FOV = 400;

	public static final int REAR_FACE = 6;

	private static final float CUBE_FACE_FOV = 90.0F;

	private static final float CUBE_CULL_FOV = 110.0F;

	private static final float REAR_CULL_MARGIN = 20.0F;

	private static final float MAX_CULL_FOV = 170.0F;

	private static final int MIN_REAR_SIZE = 16;

	private static final int REAR_MASK_GRID = 16;

	private static final int OVERLAY_UBO_FLOATS = 12;

	private static final int OVERLAY_VERTICES = 6;

	private static final float REAR_BORDER_GRAY = 0.08F;
	private static final Vector4f REAR_BLOCKED_COLOR = new Vector4f(0.0F, 0.0F, 0.0F, 1.0F);
	private static final Vector4f CLEAR_OUTLINE = new Vector4f(0.0F, 0.0F, 0.0F, 0.0F);

	public static volatile RenderTarget currentTarget = null;
	public static volatile boolean capturing = false;
	public static volatile boolean captureOutlines = false;
	public static volatile int captureFace = 0;
	public static volatile float captureViewYaw = 0.0F;
	public static volatile float captureFaceYaw = 0.0F;
	public static volatile float captureFacePitch = 0.0F;
	public static final Vector3f captureViewForward = new Vector3f(0.0F, 0.0F, -1.0F);
	public static volatile FogType capturedFogType = FogType.NONE;
	public static volatile float captureFov = CUBE_FACE_FOV;
	public static volatile float captureAspect = 1.0F;
	public static volatile float captureCullFov = CUBE_CULL_FOV;
	public static volatile boolean rearOverlayPreview = false;
	public static volatile boolean suppressHand = false;


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
	private RenderTarget rearTarget = null;
	private RenderTarget rearOutlineTarget = null;
	private int rearWidth = 0;
	private int rearHeight = 0;
	private RenderPipeline overlayPipeline = null;
	private GpuBuffer uboBufferRear = null;
	private GpuBuffer uboBufferRearOutline = null;
	private GpuBuffer overlayUbo = null;
	private boolean rearValid = false;
	private float rearCapturedFov = 0.0F;
	private final float[] overlayUboValues = new float[OVERLAY_UBO_FLOATS];
	private final float[] overlayUboWritten = new float[OVERLAY_UBO_FLOATS];
	private boolean overlayUboFresh = false;
	private long lastRearNanos = 0L;
	private Object rearLevel = null;
	private float lastViewPitch = 0.0F;
	private final Quaternionf qRear = new Quaternionf();

	private float frameViewYaw, frameViewPitch;

	private Fov360Projection blendFrom = Fov360Projection.RECTILINEAR;
	private Fov360Projection blendTo = Fov360Projection.RECTILINEAR;
	private float blendT = 0.0F;

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

	public static Fov360Config currentConfig() {
		return INSTANCE.config();
	}

	public static float effectiveFov(Minecraft client) {
		float rawFov = INSTANCE.fovx(client);
		if (INSTANCE.config().splitScreen) {
			return rawFov;
		}
		return INSTANCE.remapBoundaryFovx(rawFov, windowAspect(client));
	}

	public boolean shouldRun(Minecraft client) {
		return client.level != null && client.player != null;
	}

	public static boolean willCapture(Minecraft client) {
		if (client == null || client.player == null || !INSTANCE.shouldRun(client)) {
			return false;
		}
		return INSTANCE.needsCube(client);
	}

	private boolean needsCube(Minecraft client) {
		if (config().splitScreen) {
			return true;
		}
		Fov360Config.ProjectionBlend blend = Fov360Config.sample(config().projectionPoints, effectiveFov(client));
		boolean rectilinear = blend.from() == Fov360Projection.RECTILINEAR
			&& (blend.t() <= 0.0F || blend.to() == Fov360Projection.RECTILINEAR);
		return !rectilinear;
	}

	public static float vanillaFovScale(Minecraft client) {
		int rawFov = client.options.fov().get();
		if (rawFov < 90 || INSTANCE.needsCube(client)) {
			return 1.0F;
		}
		float fovx = Math.min(effectiveFov(client), Fov360Projection.RECTILINEAR.maxFov());
		float vertical = (float) Math.toDegrees(2.0 * Math.atan(Math.tan(fovx * DEG2RAD / 2.0) / windowAspect(client)));
		return vertical / rawFov;
	}

	public static int sliderFovFor(Minecraft client, float horizontalFov) {
		float aspect = windowAspect(client);
		boolean split = INSTANCE.config().splitScreen;
		int best = MIN_SLIDER_FOV;
		float bestDistance = Float.MAX_VALUE;
		for (int raw = MIN_SLIDER_FOV; raw <= MAX_SLIDER_FOV; raw++) {
			float fovx = split ? raw : INSTANCE.remapBoundaryFovx(raw, aspect);
			float distance = Math.abs(fovx - horizontalFov);
			if (distance < bestDistance) {
				best = raw;
				bestDistance = distance;
			}
		}
		return best;
	}

	private static float windowAspect(Minecraft client) {
		Window window = client.getWindow();
		return window.getWidth() / (float) Math.max(1, window.getHeight());
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

			if (!needsCube(client)) {
				return false;
			}

			float dynamicFov = gameRenderer.mainCamera().getFov();
			if (dynamicFov > 0.0F) {
				rawFov = Mth.clamp(rawFov * dynamicFov / client.options.fov().get(), MIN_SLIDER_FOV, MAX_SLIDER_FOV);
			}

			RenderTarget main = gameRenderer.mainRenderTarget();
			int outH = main.height;
			float projW = main.width / (split ? 2.0F : 1.0F);
			float aspect = projW / (float) outH;

			float fovx = split ? rawFov : remapBoundaryFovx(rawFov, aspect);

			float baseFov = fovx(client);
			computeScales(fovx);
			selectProjections(split ? baseFov : remapBoundaryFovx(baseFov, aspect));

			Camera realCamera = gameRenderer.mainCamera();
			float worldPartialTicks = deltaTracker.getGameTimeDeltaPartialTick(false);
			float cameraPartialTicks = realCamera.getCameraEntityPartialTicks(deltaTracker);

			updateViewAngles(client, player, cameraPartialTicks);
			float viewYaw = frameViewYaw;
			float viewPitch = frameViewPitch;
			lastViewPitch = viewPitch;

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

			int fullSize = requiredFaceSize(projW);
			int lowSize = config().lowResTopBottomFaces ? halvedSize(fullSize) : fullSize;
			ensureResources(fullSize, lowSize, centerFace);

			GameRendererInvoker inv = (GameRendererInvoker) gameRenderer;

			capturing = true;
			beginOutlineCapture(client);
			boolean anyOutline = false;

			for (int k = 0; k < 6; k++) {
				if (!faceEnabled[k]) {
					continue;
				}
				captureFace = k;
				captureFaceYaw = faceYaw(viewYaw, k);
				captureFacePitch = facePitch(k);
				currentTarget = faces[k];
				if (outlineRedirect != null) {
					RenderSystem.getDevice().createCommandEncoder().clearColorTexture(outlineFaces[k].getColorTexture(), CLEAR_OUTLINE);
					outlineRedirect.panini$setEntityOutlineTarget(outlineFaces[k]);
				}

				realCamera.update(deltaTracker);
				inv.panini$extractCamera(deltaTracker, worldPartialTicks);
				client.levelExtractor.extract(deltaTracker, realCamera, worldPartialTicks);
				gameRenderer.renderLevel();
				if (outlineRedirect != null) {
					anyOutline |= outlineRedirect.panini$getCurrentFrameRendersEntityOutline();
				}
			}

			currentTarget = null;
			capturing = false;
			endOutlineCapture();

			realCamera.update(deltaTracker);
			inv.panini$extractCamera(deltaTracker, worldPartialTicks);

			reproject(client, viewPitch, outH, projW, aspect, split, invert, fovx);
			reprojectOutline(projW, outH, split, invert, fovx, viewPitch);
			if (outlineRedirect != null) {
				outlineRedirect.panini$setCurrentFrameRendersEntityOutline(anyOutline);
			}
			suppressHand = split;
			try {
				renderHand(gameRenderer);
			} finally {
				suppressHand = false;
			}
			renderRearOverlay(gameRenderer, deltaTracker, true);
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

	private void updateViewAngles(Minecraft client, Player player, float cameraPartialTicks) {
		Entity cameraEntity = client.getCameraEntity() == null ? player : client.getCameraEntity();
		frameViewYaw = cameraEntity.getViewYRot(cameraPartialTicks);
		frameViewPitch = cameraEntity.getViewXRot(cameraPartialTicks);
		if (client.options.getCameraType().isMirrored()) {
			frameViewYaw += 180.0F;
			frameViewPitch = -frameViewPitch;
		}
	}

	private boolean rearOverlayActive(Minecraft client) {
		Fov360Config c = config();
		return shouldRun(client)
			&& !c.splitScreen
			&& (rearOverlayPreview || (c.rearOverlay && !client.gui.hud.isHidden()));
	}

	public void renderRearOverlay(GameRenderer gameRenderer, DeltaTracker deltaTracker, boolean cubeFresh) {
		Minecraft client = Minecraft.getInstance();
		if (capturing || client.player == null || !rearOverlayActive(client)) {
			return;
		}
		try {
			Fov360Config c = config();
			RenderTarget main = gameRenderer.mainRenderTarget();
			int x0 = Math.round(c.rearOverlayX * main.width);
			int y0 = Math.round(c.rearOverlayY * main.height);
			int x1 = Math.round((c.rearOverlayX + c.rearOverlayWidth) * main.width);
			int y1 = Math.round((c.rearOverlayY + c.rearOverlayHeight) * main.height);
			if (x1 - x0 < 2 || y1 - y0 < 2) {
				return;
			}
			float aspect = (x1 - x0) / (float) (y1 - y0);
			float scale = Mth.clamp(c.rearOverlayRenderScale, Fov360Config.MIN_REAR_OVERLAY_SCALE, Fov360Config.MAX_REAR_OVERLAY_SCALE) / 100.0F;
			int w = Math.max(MIN_REAR_SIZE, Math.round((x1 - x0) * scale));
			int h = Math.max(MIN_REAR_SIZE, Math.round((y1 - y0) * scale));
			ensureRearResources(w, h);

			if (viewBlocked(gameRenderer)) {
				RenderSystem.getDevice().createCommandEncoder().clearColorTexture(rearTarget.getColorTexture(), REAR_BLOCKED_COLOR);
				rearValid = false;
			} else if (rearCaptureDue(client)) {
				if (!(cubeFresh && reprojectRearFromCube(aspect))) {
					captureRear(client, gameRenderer, deltaTracker, aspect);
				}
				lastRearNanos = System.nanoTime();
				rearValid = true;
				rearLevel = client.level;
				rearCapturedFov = rearFovx();
			}
			compositeRear(client, main, x0, main.height - y1, x1, main.height - y0);
		} catch (Throwable t) {
			fail(t);
		}
	}

	private static boolean viewBlocked(GameRenderer gameRenderer) {
		return gameRenderer.gameRenderState().levelRenderState.playerRenderState.blockOverlay != null;
	}

	private float rearFovx() {
		return Mth.clamp(config().rearOverlayFov, Fov360Config.MIN_REAR_OVERLAY_FOV, Fov360Config.MAX_REAR_OVERLAY_FOV);
	}

	private boolean rearCaptureDue(Minecraft client) {
		if (!rearValid || rearLevel != client.level || rearCapturedFov != rearFovx()) {
			return true;
		}
		if (client.isPaused()) {
			return false;
		}
		int fps = config().rearOverlayFps;
		return fps <= 0 || System.nanoTime() - lastRearNanos >= 1_000_000_000L / fps;
	}

	private boolean reprojectRearFromCube(float aspect) {
		if (pipeline == null || uboSize <= 0) {
			return false;
		}
		float scale = (float) Math.tan(rearFovx() * DEG2RAD / 2.0);
		for (int iy = 0; iy <= REAR_MASK_GRID; iy++) {
			float sy = (iy / (float) REAR_MASK_GRID - 0.5F) * (2.0F / aspect);
			for (int ix = 0; ix <= REAR_MASK_GRID; ix++) {
				float sx = (ix / (float) REAR_MASK_GRID - 0.5F) * 2.0F;
				rayOut.set(-sx * scale, sy * scale, 1.0F);
				int face = faceIndexOf(rayOut);
				if (!faceEnabled[face] || faces[face] == null) {
					return false;
				}
			}
		}
		if (uboBufferRear == null) {
			uboBufferRear = RenderSystem.getDevice().createBuffer(
				() -> "PaniniConfigRear",
				GpuBuffer.USAGE_UNIFORM | GpuBuffer.USAGE_MAP_WRITE,
				uboSize);
		}
		writeRearUbo(uboBufferRear, aspect, scale, false);
		CommandEncoder encoder = RenderSystem.getDevice().createCommandEncoder();
		runReprojectPass(encoder, "fov360 rear reproject", uboBufferRear, faces, rearTarget);

		if (outlineRedirect != null && outlineRedirect.panini$getCurrentFrameRendersEntityOutline()) {
			if (uboBufferRearOutline == null) {
				uboBufferRearOutline = RenderSystem.getDevice().createBuffer(
					() -> "PaniniConfigRearOutline",
					GpuBuffer.USAGE_UNIFORM | GpuBuffer.USAGE_MAP_WRITE,
					uboSize);
			}
			ensureRearOutlineTarget();
			writeRearUbo(uboBufferRearOutline, aspect, scale, true);
			runReprojectPass(encoder, "fov360 rear outline reproject", uboBufferRearOutline, outlineFaces, rearOutlineTarget);
			rearOutlineTarget.blitAndBlendToTexture(rearTarget.getColorTextureView(), rearTarget.getDepthTextureView());
		}
		return true;
	}

	private void writeRearUbo(GpuBuffer ubo, float aspect, float scale, boolean outline) {
		float aa = Mth.clamp(config().antialiasSamples, 1, 4);
		try (GpuBufferSlice.MappedView view = ubo.map(false, true)) {
			Std140Builder b = Std140Builder.intoBuffer(view.data());
			for (int i = 0; i < 6; i++) {
				b.putMat4f(coordFrames[i]);
			}
			b.putVec4(rearFovx(), aspect, lastViewPitch, aa);
			b.putVec4((float) rearWidth, (float) rearHeight, 0.0F, 0.0F);
			b.putVec4(scale, 0.0F, 0.0F, 0.0F);
			b.putVec4(0.0F, outline ? 1.0F : 0.0F, 0.0F, 1.0F);
			b.putVec4(en(0), en(1), en(2), en(3));
			b.putVec4(en(4), en(5), 0.0F, 0.0F);
			b.putVec4(Fov360Projection.RECTILINEAR.ordinal(), Fov360Projection.RECTILINEAR.ordinal(), 0.0F, 0.0F);
		}
	}

	private void ensureRearOutlineTarget() {
		if (rearOutlineTarget == null) {
			rearOutlineTarget = new TextureTarget("fov360_rear_outline", rearWidth, rearHeight, GpuFormat.RGBA8_UNORM, GpuFormat.D32_FLOAT);
		}
	}

	private void captureRear(Minecraft client, GameRenderer gameRenderer, DeltaTracker deltaTracker, float aspect) {
		Camera realCamera = gameRenderer.mainCamera();
		float worldPartialTicks = deltaTracker.getGameTimeDeltaPartialTick(false);
		updateViewAngles(client, client.player, realCamera.getCameraEntityPartialTicks(deltaTracker));
		float rearYaw = frameViewYaw + 180.0F;
		float rearPitch = -frameViewPitch;
		float vertical = (float) Math.toDegrees(2.0 * Math.atan(Math.tan(rearFovx() * DEG2RAD / 2.0) / aspect));

		q(qRear, rearYaw, rearPitch);
		captureViewYaw = rearYaw;
		q(qPlayer, frameViewYaw, frameViewPitch);
		captureViewForward.set(0.0F, 0.0F, -1.0F).rotate(qPlayer);
		capturedFogType = realCamera.getFluidInCamera();

		GameRendererInvoker inv = (GameRendererInvoker) gameRenderer;
		LevelRendererAccessor lr = (LevelRendererAccessor) client.levelRenderer;
		RenderTarget realOutline = lr.panini$getEntityOutlineTarget();
		boolean realRendersOutline = lr.panini$getCurrentFrameRendersEntityOutline();
		if (realOutline != null) {
			ensureRearOutlineTarget();
		}

		capturing = true;
		captureOutlines = realOutline != null;
		captureFace = REAR_FACE;
		captureFaceYaw = rearYaw;
		captureFacePitch = rearPitch;
		captureFov = vertical;
		captureAspect = aspect;
		captureCullFov = Math.min(vertical + REAR_CULL_MARGIN, MAX_CULL_FOV);
		currentTarget = rearTarget;
		if (realOutline != null) {
			lr.panini$setEntityOutlineTarget(rearOutlineTarget);
		}
		try {
			realCamera.update(deltaTracker);
			inv.panini$extractCamera(deltaTracker, worldPartialTicks);
			client.levelExtractor.extract(deltaTracker, realCamera, worldPartialTicks);
			gameRenderer.renderLevel();
			if (realOutline != null && lr.panini$getCurrentFrameRendersEntityOutline()) {
				rearOutlineTarget.blitAndBlendToTexture(rearTarget.getColorTextureView(), rearTarget.getDepthTextureView());
			}
		} finally {
			currentTarget = null;
			capturing = false;
			captureOutlines = false;
			captureFov = CUBE_FACE_FOV;
			captureAspect = 1.0F;
			captureCullFov = CUBE_CULL_FOV;
			if (realOutline != null) {
				lr.panini$setEntityOutlineTarget(realOutline);
			}
			lr.panini$setCurrentFrameRendersEntityOutline(realRendersOutline);
			realCamera.update(deltaTracker);
			inv.panini$extractCamera(deltaTracker, worldPartialTicks);
		}
	}

	private void compositeRear(Minecraft client, RenderTarget out, int x0, int y0, int x1, int y1) {
		Fov360Config c = config();
		float border = c.rearOverlayBorder ? Math.max(1, (int) client.getWindow().getGuiScale()) : 0.0F;
		float[] v = overlayUboValues;
		v[0] = x0;
		v[1] = y0;
		v[2] = x1;
		v[3] = y1;
		v[4] = c.rearOverlayMirror ? 1.0F : 0.0F;
		v[5] = border;
		v[6] = out.width;
		v[7] = out.height;
		v[8] = REAR_BORDER_GRAY;
		v[9] = REAR_BORDER_GRAY;
		v[10] = REAR_BORDER_GRAY;
		v[11] = 1.0F;
		if (!overlayUboFresh || !Arrays.equals(v, overlayUboWritten)) {
			try (GpuBufferSlice.MappedView view = overlayUbo.map(false, true)) {
				Std140Builder b = Std140Builder.intoBuffer(view.data());
				for (int i = 0; i < OVERLAY_UBO_FLOATS; i += 4) {
					b.putVec4(v[i], v[i + 1], v[i + 2], v[i + 3]);
				}
			}
			System.arraycopy(v, 0, overlayUboWritten, 0, OVERLAY_UBO_FLOATS);
			overlayUboFresh = true;
		}
		CommandEncoder encoder = RenderSystem.getDevice().createCommandEncoder();
		try (RenderPass pass = encoder.createRenderPass(
				() -> "fov360 rear overlay",
				out.getColorTextureView(), Optional.empty(),
				null, OptionalDouble.empty())) {
			pass.setPipeline(RenderSystem.getCompiledPipeline(overlayPipeline));
			RenderSystem.bindDefaultUniforms(pass);
			pass.setUniform("RearOverlayConfig", overlayUbo);
			pass.setUniform("RearSampler", rearTarget.getColorTextureView(), RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR));
			pass.draw(OVERLAY_VERTICES, 1, 0, 0);
		}
	}

	private void ensureRearResources(int w, int h) {
		ensureDevice();
		if (rearTarget == null) {
			rearTarget = new TextureTarget("fov360_rear", w, h, GpuFormat.RGBA8_UNORM, GpuFormat.D32_FLOAT);
			rearValid = false;
		} else if (rearWidth != w || rearHeight != h) {
			rearTarget.resize(w, h);
			if (rearOutlineTarget != null) {
				rearOutlineTarget.resize(w, h);
			}
			rearValid = false;
		}
		rearWidth = w;
		rearHeight = h;

		if (overlayPipeline == null) {
			RenderPipeline.Builder builder = RenderPipeline.builder(RenderPipelines.POST_PROCESSING_SNIPPET)
				.withLocation(Identifier.fromNamespaceAndPath("fov360", "pipeline/rear_overlay"))
				.withVertexShader(Identifier.fromNamespaceAndPath("fov360", "post/rear_overlay"))
				.withFragmentShader(Identifier.fromNamespaceAndPath("fov360", "post/rear_overlay"));
			builder.withBindGroupLayout(BindGroupLayout.builder()
				.withUniform("RearSampler", UniformType.COMBINED_IMAGE_SAMPLER)
				.withUniform("RearOverlayConfig", UniformType.UNIFORM_BUFFER)
				.build());
			builder.withColorTargetState(ColorTargetState.DEFAULT);
			overlayPipeline = builder.build();
		}

		if (overlayUbo == null) {
			Std140SizeCalculator calc = new Std140SizeCalculator();
			for (int i = 0; i < 3; i++) {
				calc.putVec4();
			}
			overlayUbo = RenderSystem.getDevice().createBuffer(
				() -> "RearOverlayConfig",
				GpuBuffer.USAGE_UNIFORM | GpuBuffer.USAGE_MAP_WRITE,
				calc.get());
			overlayUboFresh = false;
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
		double stdHalf = Math.min(fovx, Fov360Projection.RECTILINEAR.maxFov()) * DEG2RAD / 2.0;
		double panHalf = Math.min(fovx, Fov360Projection.PANINI.maxFov()) * DEG2RAD / 2.0;
		double steHalf = Math.min(fovx, Fov360Projection.STEREOGRAPHIC.maxFov()) * DEG2RAD / 2.0;
		scaleStd = (float) Math.tan(stdHalf);
		scalePan = (float) ((2.0 / (1.0 + Math.cos(panHalf))) * Math.sin(panHalf));
		scaleSte = (float) Math.tan(steHalf / 2.0);
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

	private void selectProjections(float fovx) {
		Fov360Config.ProjectionBlend blend = Fov360Config.sample(config().projectionPoints, fovx);
		blendFrom = blend.from();
		blendTo = blend.to();
		blendT = blend.t();
	}

	private boolean blendsTo() {
		return blendT > 0.0F && blendTo != blendFrom;
	}

	private void projectionRay(Fov360Projection projection, Vector3f d, float cx, float cy, float pitchDeg) {
		switch (projection) {
			case RECTILINEAR -> standardRay(d, cx, cy);
			case PANINI_STEREOGRAPHIC -> hybridRay(d, cx, cy, pitchDeg);
			case PANINI -> paniniRay(d, cx, cy);
			case STEREOGRAPHIC -> stereoRay(d, cx, cy);
			case FISHEYE -> fisheyeRay(d, cx, cy);
			case MERCATOR -> mercatorRay(d, cx, cy);
			case EQUIRECTANGULAR -> equirectRay(d, cx, cy);
		}
	}

	private boolean screenToRay(float cx, float cy, float fovx, float pitchDeg, boolean rear) {
		projectionRay(blendFrom, rayOut, cx, cy, pitchDeg);
		if (blendsTo()) {
			projectionRay(blendTo, rayB, cx, cy, pitchDeg);
			rayOut.lerp(rayB, blendT);
		}
		rayOut.z = -rayOut.z;
		if (rear) {
			rayOut.x = -rayOut.x;
			rayOut.z = -rayOut.z;
		}
		return rayOut.lengthSquared() > 1.0e-12F;
	}

	private float centerScale(Fov360Projection projection) {
		return switch (projection) {
			case RECTILINEAR -> scaleStd;
			case PANINI_STEREOGRAPHIC, PANINI -> scalePan;
			case STEREOGRAPHIC -> 2.0F * scaleSte;
			case FISHEYE -> scaleFish;
			case MERCATOR -> scaleMer;
			case EQUIRECTANGULAR -> scaleEqu;
		};
	}

	private int requiredFaceSize(float projW) {
		double density = projW / (2.0 * centerScale(blendFrom));
		if (blendsTo()) {
			density = Math.max(density, projW / (2.0 * centerScale(blendTo)));
		}
		int rounded = (int) (Math.ceil(2.0 * density / 128.0) * 128);
		int cap = Mth.clamp(config().faceSizeCap, 256, 4096);
		return Mth.clamp(rounded, 256, cap);
	}

	private int halvedSize(int fullSize) {
		int rounded = ((fullSize / 2 + 127) / 128) * 128;
		return Math.max(256, rounded);
	}

	private void ensureDevice() {
		GpuDevice device = RenderSystem.getDevice();
		if (resourceDevice == device) {
			return;
		}
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
		rearTarget = null;
		rearOutlineTarget = null;
		rearWidth = 0;
		rearHeight = 0;
		overlayPipeline = null;
		uboBufferRear = null;
		uboBufferRearOutline = null;
		overlayUbo = null;
		rearValid = false;
		resourceDevice = device;
	}

	private void ensureResources(int fullSize, int lowSize, int centerFace) {
		ensureDevice();
		GpuDevice device = RenderSystem.getDevice();

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
			for (int i = 0; i < 7; i++) {
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
			b.putVec4(blendFrom.ordinal(), blendTo.ordinal(), blendsTo() ? blendT : 0.0F, 0.0F);
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
		return Mth.clamp(fovOption, MIN_SLIDER_FOV, MAX_SLIDER_FOV);
	}

	private float remapBoundaryFovx(float rawFov, float aspect) {
		if (rawFov >= 180.0F) {
			return rawFov;
		}
		if (rawFov < 90.0F) {
			return (float) Math.toDegrees(2.0 * Math.atan(aspect * Math.tan(rawFov * DEG2RAD / 2.0)));
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
