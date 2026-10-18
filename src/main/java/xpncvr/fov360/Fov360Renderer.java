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

	private static final float MIN_ANGLE_SCALE = 1.0e-3F;

	private static final int EDGE_SAMPLES = 128;

	private static final int GRID_SAMPLES = (MASK_GRID + 1) * (MASK_GRID + 1);

	private static final int MAX_SAMPLES = GRID_SAMPLES + 4 * (EDGE_SAMPLES + 1);

	private static final float SAMPLE_STEP_PX = 2.0F;

	private static final double DENSITY_MARGIN = 1.05;

	private static final double MAX_ANISOTROPY = 4.0;

	private static final double STRIP_WIDTH_MARGIN = 1.001;

	private static final double STRIP_HEIGHT_MARGIN = 1.02;

	private static final double STRIP_LON_MARGIN = Math.toRadians(0.5);

	private static final double MAX_STRIP_SPAN = Math.toRadians(150.0);

	private static final double MAX_STRIP_TAN = Math.tan(Math.toRadians(75.0));

	private static final int MAX_STRIPS = 3;

	private static final double PASS_COST_PIXELS = 1.0e6;

	private static final double LAYOUT_KEEP_COST = 1.15;

	private static final double STRIP_COST_BUDGET = 1.5;

	private static final double LAYOUT_WORST_TOLERANCE = 0.02;

	private static final double TILE_KEEP_OVERSIZE = 1.25;

	private static final int TILE_ROUND = 128;

	private static final int MIN_TILE_SIZE = 256;

	private static final int MIN_SLIDER_FOV = 30;

	private static final int MAX_SLIDER_FOV = 400;

	private static final float MIN_ZOOMED_FOV = 1.0F;

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
	public static volatile float captureFaceRoll = 0.0F;
	public static final Vector3f captureViewForward = new Vector3f(0.0F, 0.0F, -1.0F);
	public static volatile FogType capturedFogType = FogType.NONE;
	public static volatile float captureFov = CUBE_FACE_FOV;
	public static volatile float captureAspect = 1.0F;
	public static volatile float captureCullFov = CUBE_CULL_FOV;
	public static volatile boolean rearOverlayPreview = false;
	public static volatile boolean suppressHand = false;


	private Fov360Config config = null;

	private final RenderTarget[] faces = new RenderTarget[6];
	private final int[] faceWidths = new int[6];
	private final int[] faceHeights = new int[6];
	private final boolean[] faceEnabled = new boolean[6];

	private final RenderTarget[] outlineFaces = new RenderTarget[6];
	private final int[] outlineWidths = new int[6];
	private final int[] outlineHeights = new int[6];

	private final int[] tileWidths = new int[6];
	private final int[] tileHeights = new int[6];
	private final float[] tileTanX = new float[6];
	private final float[] tileTanY = new float[6];
	private final Quaternionf[] qTiles = new Quaternionf[6];
	private int stripCount = 0;

	private final Matrix4f[] cubeFrames = new Matrix4f[6];
	private final Vector3f[] cubeForward = new Vector3f[6];
	private final Matrix4f[] layoutFrames = new Matrix4f[6];
	private final Vector3f[] layoutForward = new Vector3f[6];
	private final boolean[] layoutEnabled = new boolean[6];
	private final double[] layoutDensity = new double[6];
	private final double[] layoutTanY = new double[6];
	private final int[] layoutWidths = new int[6];
	private final int[] layoutHeights = new int[6];
	private double layoutWorst = 0.0;
	private final double[] layoutPsi = new double[6];
	private final double[] layoutTanX = new double[6];
	private final double[] layoutCosts = new double[MAX_STRIPS + 1];
	private final double[] layoutWorsts = new double[MAX_STRIPS + 1];

	private final float[] samples = new float[MAX_SAMPLES * 3];
	private final float[] samplesX = new float[GRID_SAMPLES * 3];
	private final float[] samplesY = new float[GRID_SAMPLES * 3];
	private final boolean[] sampleValid = new boolean[MAX_SAMPLES];
	private final boolean[] sampleDiffValid = new boolean[GRID_SAMPLES];
	private int sampleCount = 0;
	private float sampleStep = 0.0F;
	private float sampleProjW = 1.0F;
	private final Vector3f local = new Vector3f();
	private final Vector3f eulerTmp = new Vector3f();

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
	private float paniniD = 1.0F;
	private float stereographicK = 0.5F;
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
			cubeFrames[i] = new Matrix4f();
			cubeForward[i] = new Vector3f();
			layoutFrames[i] = new Matrix4f();
			layoutForward[i] = new Vector3f();
			qTiles[i] = new Quaternionf();
			tileTanX[i] = 1.0F;
			tileTanY[i] = 1.0F;
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
				rawFov = Mth.clamp(rawFov * dynamicFov / client.options.fov().get(), MIN_ZOOMED_FOV, MAX_SLIDER_FOV);
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
				cubeFrames[k].rotation(qTmp);
				cubeForward[k].set(-cubeFrames[k].m02(), -cubeFrames[k].m12(), -cubeFrames[k].m22());
			}

			capturedFogType = realCamera.getFluidInCamera();

			sampleScreen(fovx, aspect, projW, split, invert, viewPitch);
			chooseLayout(split, fovx, viewPitch);
			ensureResources();

			GameRendererInvoker inv = (GameRendererInvoker) gameRenderer;

			capturing = true;
			beginOutlineCapture(client);
			boolean anyOutline = false;

			for (int k = 0; k < 6; k++) {
				if (!faceEnabled[k]) {
					continue;
				}
				captureFace = k;
				if (stripCount > 0) {
					qTiles[k].getEulerAnglesYXZ(eulerTmp);
					captureFaceYaw = (float) Math.toDegrees(Math.PI - eulerTmp.y);
					captureFacePitch = (float) -Math.toDegrees(eulerTmp.x);
					captureFaceRoll = eulerTmp.z;
					captureFov = (float) Math.toDegrees(2.0 * Math.atan(tileTanY[k]));
					captureAspect = tileTanX[k] / tileTanY[k];
					captureCullFov = Math.min(captureFov + REAR_CULL_MARGIN, MAX_CULL_FOV);
				} else {
					captureFaceYaw = faceYaw(viewYaw, k);
					captureFacePitch = facePitch(k);
				}
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
			captureFaceRoll = 0.0F;
			captureFov = CUBE_FACE_FOV;
			captureAspect = 1.0F;
			captureCullFov = CUBE_CULL_FOV;
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
			captureFaceRoll = 0.0F;
			captureFov = CUBE_FACE_FOV;
			captureAspect = 1.0F;
			captureCullFov = CUBE_CULL_FOV;
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
		if (pipeline == null || uboSize <= 0 || stripCount > 0) {
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
			b.putVec4(paniniD, stereographicK, 0.0F, 0.0F);
			putTileTans(b);
		}
	}

	private void putTileTans(Std140Builder b) {
		for (int i = 0; i < 6; i++) {
			b.putVec4(1.0F / tileTanX[i], 1.0F / tileTanY[i], 0.0F, 0.0F);
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
		paniniD = config().paniniD;
		stereographicK = config().stereographicK;
		scaleStd = (float) Math.tan(stdHalf);
		scalePan = (float) ((paniniD + 1.0) * Math.sin(panHalf) / (paniniD + Math.cos(panHalf)));
		scaleSte = (float) (stereographicK < MIN_ANGLE_SCALE ? steHalf : Math.tan(stereographicK * steHalf));
		scaleMer = (float) rHalf;
		scaleEqu = (float) rHalf;
		scaleFish = (float) rHalf;
	}

	private void sampleScreen(float fovx, float aspect, float projW, boolean split, boolean invert, float pitchDeg) {
		sampleStep = SAMPLE_STEP_PX * 2.0F / projW;
		sampleProjW = projW;
		int n = 0;
		for (int iy = 0; iy <= MASK_GRID; iy++) {
			float ty = iy / (float) MASK_GRID;
			for (int ix = 0; ix <= MASK_GRID; ix++) {
				float tx = ix / (float) MASK_GRID;
				sampleValid[n] = screenRay(tx, ty, 0.0F, 0.0F, fovx, aspect, split, invert, pitchDeg);
				storeRay(samples, n);
				boolean validX = screenRay(tx, ty, sampleStep, 0.0F, fovx, aspect, split, invert, pitchDeg);
				storeRay(samplesX, n);
				boolean validY = screenRay(tx, ty, 0.0F, sampleStep, fovx, aspect, split, invert, pitchDeg);
				storeRay(samplesY, n);
				sampleDiffValid[n] = validX && validY;
				n++;
			}
		}
		for (int i = 0; i <= EDGE_SAMPLES; i++) {
			float t = i / (float) EDGE_SAMPLES;
			for (int e = 0; e < 4; e++) {
				float tx = e < 2 ? t : e - 2;
				float ty = e < 2 ? e : t;
				sampleValid[n] = screenRay(tx, ty, 0.0F, 0.0F, fovx, aspect, split, invert, pitchDeg);
				storeRay(samples, n);
				n++;
			}
		}
		sampleCount = n;
	}

	private boolean screenRay(float tx, float ty, float dx, float dy, float fovx, float aspect,
			boolean split, boolean invert, float pitchDeg) {
		boolean rear = false;
		float ux = tx;
		if (split) {
			rear = (tx >= 0.5F) != invert;
			ux = (tx * 2.0F) % 1.0F;
		}
		return screenToRay((ux - 0.5F) * 2.0F + dx, (ty - 0.5F) * (2.0F / aspect) + dy, fovx, pitchDeg, rear);
	}

	private void storeRay(float[] dest, int i) {
		dest[i * 3] = rayOut.x;
		dest[i * 3 + 1] = rayOut.y;
		dest[i * 3 + 2] = rayOut.z;
	}

	private void chooseLayout(boolean split, float fovx, float pitchDeg) {
		for (int n = 0; n <= MAX_STRIPS; n++) {
			layoutCosts[n] = n > 0 && split ? Double.NaN : evaluateLayout(n, fovx, pitchDeg);
			layoutWorsts[n] = layoutWorst;
		}
		int best = 0;
		for (int n = 1; n <= MAX_STRIPS; n++) {
			if (stripsEligible(n) && (best == 0
					|| layoutWorsts[n] > layoutWorsts[best] + LAYOUT_WORST_TOLERANCE
					|| (layoutWorsts[n] >= layoutWorsts[best] - LAYOUT_WORST_TOLERANCE && layoutCosts[n] < layoutCosts[best]))) {
				best = n;
			}
		}
		if (stripCount != best && (stripCount == 0 || stripsEligible(stripCount))
				&& layoutWorsts[stripCount] >= layoutWorsts[best] - LAYOUT_WORST_TOLERANCE
				&& layoutCosts[stripCount] <= layoutCosts[best] * LAYOUT_KEEP_COST) {
			best = stripCount;
		}
		evaluateLayout(best, fovx, pitchDeg);
		stripCount = best;
		for (int k = 0; k < 6; k++) {
			faceEnabled[k] = layoutEnabled[k];
			coordFrames[k].set(layoutFrames[k]);
			faceForward[k].set(layoutForward[k]);
			tileTanX[k] = layoutEnabled[k] ? (float) layoutTanX[k] : 1.0F;
			tileTanY[k] = layoutEnabled[k] ? (float) layoutTanY[k] : 1.0F;
			tileWidths[k] = layoutWidths[k];
			tileHeights[k] = layoutHeights[k];
			if (best > 0 && k < best) {
				qTiles[k].set(qPlayer).rotateY((float) -layoutPsi[k]);
			}
		}
	}

	private boolean stripsEligible(int n) {
		return !Double.isNaN(layoutCosts[n])
			&& layoutWorsts[n] >= layoutWorsts[0] - LAYOUT_WORST_TOLERANCE
			&& layoutCosts[n] <= layoutCosts[0] * STRIP_COST_BUDGET;
	}

	private double evaluateLayout(int strips, float fovx, float pitchDeg) {
		layoutWorst = 0.0;
		Arrays.fill(layoutEnabled, false);
		Arrays.fill(layoutDensity, 0.0);
		Arrays.fill(layoutTanY, 0.0);
		Arrays.fill(layoutWidths, 0);
		Arrays.fill(layoutHeights, 0);
		int tiles = strips == 0 ? 6 : strips;
		double span = 0.0;
		if (strips == 0) {
			for (int k = 0; k < 6; k++) {
				layoutFrames[k].set(cubeFrames[k]);
				layoutForward[k].set(cubeForward[k]);
			}
		} else {
			double lonMin = Double.MAX_VALUE;
			double lonMax = -Double.MAX_VALUE;
			for (int i = 0; i < sampleCount; i++) {
				if (sampleValid[i]) {
					double lon = Math.atan2(samples[i * 3], -samples[i * 3 + 2]);
					lonMin = Math.min(lonMin, lon);
					lonMax = Math.max(lonMax, lon);
				}
			}
			if (lonMin > lonMax) {
				return Double.NaN;
			}
			lonMin -= STRIP_LON_MARGIN;
			lonMax += STRIP_LON_MARGIN;
			span = (lonMax - lonMin) / strips;
			if (span > MAX_STRIP_SPAN) {
				return Double.NaN;
			}
			for (int k = 0; k < strips; k++) {
				double psi = lonMin + span * (k + 0.5);
				layoutPsi[k] = psi;
				layoutFrames[k].rotationY((float) psi);
				layoutForward[k].set((float) Math.sin(psi), 0.0F, (float) -Math.cos(psi));
			}
		}

		for (int i = 0; i < sampleCount; i++) {
			if (!sampleValid[i]) {
				continue;
			}
			int k = nearestTile(i, tiles);
			layoutEnabled[k] = true;
			toLocal(layoutFrames[k], samples, i);
			if (local.z >= 0.0F) {
				if (strips > 0) {
					return Double.NaN;
				}
				continue;
			}
			layoutTanY[k] = Math.max(layoutTanY[k], Math.abs(local.y / local.z));
			if (i < GRID_SAMPLES && sampleDiffValid[i]) {
				layoutDensity[k] = Math.max(layoutDensity[k], requiredDensity(layoutFrames[k], i));
			}
		}
		if (strips == 0) {
			layoutEnabled[0] = true;
		}

		double maxDensity = 0.0;
		for (int k = 0; k < tiles; k++) {
			maxDensity = Math.max(maxDensity, layoutDensity[k]);
		}
		int centerFace = -1;
		if (strips == 0 && config().lowResTopBottomFaces && screenToRay(0.0F, 0.0F, fovx, pitchDeg, false)) {
			centerFace = nearestCubeFace(rayOut);
		}
		int cap = Mth.clamp(config().faceSizeCap, MIN_TILE_SIZE, 4096);
		int cubeSize = strips == 0 ? requiredFaceSize(sampleProjW) : 0;
		double worst = 1.0;
		double cost = 0.0;
		for (int k = 0; k < tiles; k++) {
			if (!layoutEnabled[k]) {
				continue;
			}
			double tanX = strips == 0 ? 1.0 : Math.tan(span / 2.0) * STRIP_WIDTH_MARGIN;
			double tanY = strips == 0 ? 1.0 : layoutTanY[k] * STRIP_HEIGHT_MARGIN;
			if (tanY > MAX_STRIP_TAN) {
				return Double.NaN;
			}
			double density = Math.max(layoutDensity[k] > 0.0 ? layoutDensity[k] : maxDensity, 1.0);
			double w = 2.0 * tanX * density;
			double h = 2.0 * tanY * density;
			int tw = strips == 0 ? cubeSize : roundTile(w * DENSITY_MARGIN, cap);
			int th = strips == 0 ? cubeSize : roundTile(h * DENSITY_MARGIN, cap);
			worst = Math.min(worst, Math.min(tw / w, th / h));
			if ((k == 4 || k == 5) && centerFace >= 0 && k != centerFace) {
				tw = halvedSize(tw);
				th = halvedSize(th);
			}
			layoutTanX[k] = tanX;
			layoutTanY[k] = tanY;
			layoutWidths[k] = tw;
			layoutHeights[k] = th;
			cost += (double) tw * th + PASS_COST_PIXELS;
		}
		layoutWorst = worst;
		return cost;
	}

	private int nearestTile(int i, int tiles) {
		float x = samples[i * 3], y = samples[i * 3 + 1], z = samples[i * 3 + 2];
		int index = 0;
		float best = -Float.MAX_VALUE;
		for (int k = 0; k < tiles; k++) {
			Vector3f f = layoutForward[k];
			float d = x * f.x + y * f.y + z * f.z;
			if (d > best) {
				best = d;
				index = k;
			}
		}
		return index;
	}

	private int nearestCubeFace(Vector3f ray) {
		int index = 0;
		float best = -2.0F;
		for (int k = 0; k < 6; k++) {
			float d = ray.dot(cubeForward[k]);
			if (d > best) {
				best = d;
				index = k;
			}
		}
		return index;
	}

	private void toLocal(Matrix4f frame, float[] rays, int i) {
		frame.transformDirection(rays[i * 3], rays[i * 3 + 1], rays[i * 3 + 2], local);
	}

	private double requiredDensity(Matrix4f frame, int i) {
		toLocal(frame, samples, i);
		double u0 = local.x / -local.z, v0 = local.y / -local.z;
		toLocal(frame, samplesX, i);
		if (local.z >= 0.0F) {
			return 0.0;
		}
		double u1 = local.x / -local.z, v1 = local.y / -local.z;
		toLocal(frame, samplesY, i);
		if (local.z >= 0.0F) {
			return 0.0;
		}
		double u2 = local.x / -local.z, v2 = local.y / -local.z;
		double a = (u1 - u0) / SAMPLE_STEP_PX, b = (u2 - u0) / SAMPLE_STEP_PX;
		double c = (v1 - v0) / SAMPLE_STEP_PX, d = (v2 - v0) / SAMPLE_STEP_PX;
		double s = a * a + b * b + c * c + d * d;
		double det = a * d - b * c;
		double root = Math.sqrt(Math.max(0.0, s * s - 4.0 * det * det));
		double maxStretch = Math.sqrt((s + root) / 2.0);
		double minStretch = Math.sqrt(Math.max(0.0, (s - root) / 2.0));
		if (maxStretch < 1.0e-12) {
			return 0.0;
		}
		return Math.min(1.0 / Math.max(minStretch, 1.0e-12), MAX_ANISOTROPY / maxStretch);
	}

	private static int roundTile(double size, int cap) {
		int rounded = (int) Math.ceil(Math.min(size, cap) / TILE_ROUND) * TILE_ROUND;
		return Mth.clamp(rounded, MIN_TILE_SIZE, cap);
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
		double pd = paniniD;
		double k = x * x / ((pd + 1.0) * (pd + 1.0));
		double dscr = Math.max(0.0, k * k * pd * pd - (k + 1.0) * (k * pd * pd - 1.0));
		double clon = (-k * pd + Math.sqrt(dscr)) / (k + 1.0);
		double S = (pd + 1.0) / (pd + clon);
		latlonToRay(d, Math.atan2(y, S), Math.atan2(x, S * clon));
	}

	private void stereoRay(Vector3f d, float cx, float cy) {
		double x = cx * scaleSte, y = cy * scaleSte;
		double r = Math.sqrt(x * x + y * y);
		if (r < 1.0e-9) {
			d.set(0.0F, 0.0F, 1.0F);
			return;
		}
		double th = stereographicK < MIN_ANGLE_SCALE ? r : Math.atan(r) / stereographicK, s = Math.sin(th);
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
			case STEREOGRAPHIC -> stereographicK < MIN_ANGLE_SCALE ? scaleSte : scaleSte / stereographicK;
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
		return roundTile(2.0 * density, Mth.clamp(config().faceSizeCap, MIN_TILE_SIZE, 4096));
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
			faceWidths[i] = 0;
			faceHeights[i] = 0;
			outlineFaces[i] = null;
			outlineWidths[i] = 0;
			outlineHeights[i] = 0;
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

	private void ensureResources() {
		ensureDevice();
		GpuDevice device = RenderSystem.getDevice();

		for (int k = 0; k < 6; k++) {
			if (!faceEnabled[k]) {
				continue;
			}
			faces[k] = fitTarget(faces[k], "fov360_face_" + k, k, faceWidths, faceHeights);
			outlineFaces[k] = fitTarget(outlineFaces[k], "fov360_outline_" + k, k, outlineWidths, outlineHeights);
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
			for (int i = 0; i < 14; i++) {
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

	private RenderTarget fitTarget(RenderTarget target, String name, int k, int[] widths, int[] heights) {
		int w = tileWidths[k];
		int h = tileHeights[k];
		if (target == null) {
			widths[k] = w;
			heights[k] = h;
			return new TextureTarget(name, w, h, GpuFormat.RGBA8_UNORM, GpuFormat.D32_FLOAT);
		}
		boolean fits = widths[k] >= w && heights[k] >= h
			&& widths[k] <= w * TILE_KEEP_OVERSIZE && heights[k] <= h * TILE_KEEP_OVERSIZE;
		if (!fits) {
			target.resize(w, h);
			widths[k] = w;
			heights[k] = h;
		}
		return target;
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
			b.putVec4(paniniD, stereographicK, 0.0F, 0.0F);
			putTileTans(b);
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
				pass.setUniform("Face" + i + "Sampler", f.getColorTextureView(), RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR));
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
