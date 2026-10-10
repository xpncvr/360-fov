package xpncvr.fov360;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public final class Fov360Config {
	public static final int MIN_POINT_FOV = 30;
	public static final int MAX_POINT_FOV = 400;
	public static final int MAX_POINTS = 12;

	public boolean splitScreen = false;

	public boolean invertSplitScreen = false;

	public int faceSizeCap = 2048;

	public boolean lowResTopBottomFaces = false;

	public int antialiasSamples = 4;

	public List<ProjectionPoint> projectionPoints = defaultProjectionPoints();

	public static final class ProjectionPoint {
		public int fov;
		public Fov360Projection projection;
		public Fov360Blend blend;

		public ProjectionPoint() {
		}

		public ProjectionPoint(int fov, Fov360Projection projection, Fov360Blend blend) {
			this.fov = fov;
			this.projection = projection;
			this.blend = blend;
		}

		public ProjectionPoint copy() {
			return new ProjectionPoint(fov, projection, blend);
		}
	}

	public record ProjectionBlend(Fov360Projection from, Fov360Projection to, float t) {
	}

	public static List<ProjectionPoint> defaultProjectionPoints() {
		List<ProjectionPoint> points = new ArrayList<>();
		points.add(new ProjectionPoint(120, Fov360Projection.RECTILINEAR, Fov360Blend.EASE_OUT));
		points.add(new ProjectionPoint(160, Fov360Projection.PANINI_STEREOGRAPHIC, Fov360Blend.EASE_OUT));
		points.add(new ProjectionPoint(220, Fov360Projection.FISHEYE, Fov360Blend.EASE_OUT));
		points.add(new ProjectionPoint(300, Fov360Projection.MERCATOR, Fov360Blend.EASE_OUT));
		points.add(new ProjectionPoint(340, Fov360Projection.MERCATOR, Fov360Blend.EASE_OUT));
		points.add(new ProjectionPoint(360, Fov360Projection.EQUIRECTANGULAR, Fov360Blend.LINEAR));
		return points;
	}

	public static ProjectionBlend sample(List<ProjectionPoint> points, float fov) {
		ProjectionPoint first = points.getFirst();
		if (fov < first.fov) {
			return new ProjectionBlend(first.projection, first.projection, 0.0F);
		}
		for (int i = 0; i + 1 < points.size(); i++) {
			ProjectionPoint from = points.get(i);
			ProjectionPoint to = points.get(i + 1);
			if (fov < to.fov) {
				float linear = (fov - from.fov) / (float) (to.fov - from.fov);
				return new ProjectionBlend(from.projection, to.projection, to.blend.apply(linear));
			}
		}
		ProjectionPoint last = points.getLast();
		return new ProjectionBlend(last.projection, last.projection, 0.0F);
	}

	public void copyFrom(Fov360Config other) {
		splitScreen = other.splitScreen;
		invertSplitScreen = other.invertSplitScreen;
		faceSizeCap = other.faceSizeCap;
		lowResTopBottomFaces = other.lowResTopBottomFaces;
		antialiasSamples = other.antialiasSamples;
		projectionPoints.clear();
		for (ProjectionPoint point : other.projectionPoints) {
			projectionPoints.add(point.copy());
		}
	}

	private void normalize() {
		List<ProjectionPoint> points = new ArrayList<>();
		if (projectionPoints != null) {
			for (ProjectionPoint point : projectionPoints) {
				if (point == null || point.projection == null) {
					continue;
				}
				if (point.blend == null) {
					point.blend = Fov360Blend.EASE_OUT;
				}
				point.fov = Math.clamp(point.fov, MIN_POINT_FOV, MAX_POINT_FOV);
				points.add(point);
			}
		}
		points.sort(Comparator.comparingInt(point -> point.fov));
		for (int i = points.size() - 1; i > 0; i--) {
			if (points.get(i).fov == points.get(i - 1).fov) {
				points.remove(i);
			}
		}
		while (points.size() > MAX_POINTS) {
			points.removeLast();
		}
		projectionPoints = points.isEmpty() ? defaultProjectionPoints() : points;
	}

	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

	private static Path path() {
		return FabricLoader.getInstance().getConfigDir().resolve("fov360.json");
	}

	public static Fov360Config load() {
		Path path = path();
		if (Files.exists(path)) {
			try (Reader reader = Files.newBufferedReader(path)) {
				Fov360Config config = GSON.fromJson(reader, Fov360Config.class);
				if (config != null) {
					config.normalize();
					return config;
				}
				Main.LOGGER.warn("Empty config at {}; using defaults", path);
			} catch (IOException | RuntimeException e) {
				Main.LOGGER.warn("Failed to read config at {}; using defaults", path, e);
			}
			return new Fov360Config();
		}
		Fov360Config config = new Fov360Config();
		config.save();
		return config;
	}

	public void save() {
		Path path = path();
		try (Writer writer = Files.newBufferedWriter(path)) {
			GSON.toJson(this, writer);
		} catch (IOException e) {
			Main.LOGGER.warn("Failed to write config to {}", path, e);
		}
	}
}
