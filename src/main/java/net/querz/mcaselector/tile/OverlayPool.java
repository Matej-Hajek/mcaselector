package net.querz.mcaselector.tile;

import javafx.application.Platform;
import javafx.scene.image.Image;
import javafx.scene.image.PixelFormat;
import javafx.scene.image.WritableImage;
import net.querz.mcaselector.config.ConfigProvider;
import net.querz.mcaselector.io.FileHelper;
import net.querz.mcaselector.io.JobHandler;
import net.querz.mcaselector.io.NamedThreadFactory;
import net.querz.mcaselector.io.RegionDirectories;
import net.querz.mcaselector.io.db.CacheHandler;
import net.querz.mcaselector.io.job.ParseDataJob;
import net.querz.mcaselector.io.mca.EntitiesMCAFile;
import net.querz.mcaselector.io.mca.PoiMCAFile;
import net.querz.mcaselector.io.mca.RegionMCAFile;
import net.querz.mcaselector.util.point.Point2i;
import net.querz.mcaselector.util.property.DataProperty;
import net.querz.mcaselector.overlay.Overlay;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.iq80.leveldb.DBException;
import java.awt.*;
import java.io.IOException;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

public class OverlayPool {

	private static final Logger LOGGER = LogManager.getLogger(OverlayPool.class);

	private final TileMap tileMap;
	private final Set<Point2i> noData = ConcurrentHashMap.newKeySet();

	// makes sure a region is only parsed once when several image exports need it as
	// a neighbour at the same time
	private final ConcurrentHashMap<Long, Object> parseLocks = new ConcurrentHashMap<>();

	// used to load and render data asynchronously from db
	private final ThreadPoolExecutor overlayCacheLoaders = new ThreadPoolExecutor(
			4, 4,
			0L, TimeUnit.MILLISECONDS,
			new LinkedBlockingQueue<>(),
			new NamedThreadFactory("overlayCachePool"));

	// used to load region data from db asynchronously to be displayed in the status bar
	private final ThreadPoolExecutor overlayValueLoader = new ThreadPoolExecutor(
			1, 1,
			0L, TimeUnit.MILLISECONDS,
			new LinkedBlockingQueue<>(),
			new NamedThreadFactory("overlayValuePool"));

	// The overlay image contains a one chunk wide border holding the values of the
	// neighbouring regions. Without it image smoothing has nothing to interpolate
	// against at the edges of the image and clamps to the outermost value instead,
	// which makes every region border look like a hard cut in the overlay.
	public static final int PADDING = 1;
	public static final int IMAGE_SIZE = Tile.SIZE_IN_CHUNKS + PADDING * 2;
	private static final int CHUNK_MASK = Tile.SIZE_IN_CHUNKS - 1;

	private Overlay parser;

	private Point2i hoveredRegion;
	private int[] hoveredRegionData;

	public OverlayPool(TileMap tileMap) {
		this.tileMap = tileMap;
	}

	public Overlay getParser() {
		return parser;
	}

	public void setParser(Overlay overlay) {
		this.parser = overlay;
		if (overlay != null && overlay.isValid() && overlay.isActive()) {
			hoveredRegion = null;
			hoveredRegionData = null;
		}
	}

	public void requestImage(Tile tile, Overlay parser) {
		if (parser == null || !parser.isActive() || !parser.isValid()) {
			return;
		}

		// check if data for this region exists
		if (noData.contains(tile.location)) {
			return;
		}

		if (ParseDataJob.isLoading(tile)) {
			// skip if we are already loading this tile
			return;
		}

		ParseDataJob.setLoading(tile, true);

		Overlay parserClone = parser.clone();

		overlayCacheLoaders.execute(() -> {
			int[] data = null;
			try {
				data = CacheHandler.getData(parserClone, tile.location);
			} catch (Exception ex) {
				LOGGER.warn("failed to load cached overlay data for region {}", tile.location, ex);
			}

			if (data != null) {
				Image overlay = createOverlayImage(parserClone, tile.location, data, false);
				if (parserClone.equals(this.parser)) {
					tile.overlay = overlay;
					tile.overlayLoaded = true;
					tileMap.draw();
				}
				ParseDataJob.setLoading(tile, false);
			} else {
				// calculate data
				JobHandler.executeParseData(new ParseDataJob(tile, FileHelper.createRegionDirectories(tile.location), ConfigProvider.WORLD.getWorldUUID(),
						(d, u) -> {
					if (u.equals(ConfigProvider.WORLD.getWorldUUID())) {
						if (d == null) {
							noData.add(tile.location);
							tile.overlayLoaded = true;
							return;
						}
						if (parserClone.equals(this.parser)) {
							push(tile.location, d);
							tile.overlay = createOverlayImage(parserClone, tile.location, d, false);
							tile.overlayLoaded = true;
							// the neighbours were rendered without the data of this region
							tileMap.invalidateOverlayBorders(tile.location);
							tileMap.draw();
						}
					}
				}, parser, () -> tileMap.getTilePriority(tile.location)));
			}
		});
	}

	public Image getImage(Point2i location, RegionMCAFile region, PoiMCAFile poi, EntitiesMCAFile entities) {
		Overlay parser = this.parser;
		int[] data = loadData(parser, location);
		if (data == null) {
			data = parseData(parser, location, region, poi, entities);
		}
		if (data == null) {
			return null;
		}
		// there is no tile map here that parses the surrounding regions in the
		// background, so the neighbours needed for the border are parsed on demand
		return createOverlayImage(parser, location, data, true);
	}

	private Image createOverlayImage(Overlay parser, Point2i location, int[] data, boolean parseNeighbours) {
		int[][] regions = new int[9][];
		for (int z = -1; z <= 1; z++) {
			for (int x = -1; x <= 1; x++) {
				if (x == 0 && z == 0) {
					regions[4] = data;
					continue;
				}
				Point2i neighbour = location.add(x, z);
				int[] neighbourData = loadData(parser, neighbour);
				if (neighbourData == null && parseNeighbours) {
					neighbourData = parseData(parser, neighbour, null, null, null);
				}
				regions[(z + 1) * 3 + x + 1] = neighbourData;
			}
		}
		return parseColorGrades(regions, parser.min(), parser.max(), parser.getMinHue(), parser.getMaxHue());
	}

	// only returns data that has already been parsed, null if the region is still
	// unknown. a neighbour without data makes the border of the image fall back to
	// the values of this region, which is what the image smoothing did before.
	private int[] loadData(Overlay parser, Point2i location) {
		if (noData.contains(location)) {
			return null;
		}
		try {
			return CacheHandler.getData(parser, location);
		} catch (Exception ex) {
			LOGGER.warn("failed to load cached overlay data for region {}", location, ex);
			return null;
		}
	}

	// parses a region and caches the result, so that the regions around it can use
	// it for their border without parsing it again
	private int[] parseData(Overlay parser, Point2i location, RegionMCAFile region, PoiMCAFile poi, EntitiesMCAFile entities) {
		Object lock = parseLocks.computeIfAbsent(location.asLong(), k -> new Object());
		try {
			synchronized (lock) {
				// another thread might have parsed this region in the meantime
				int[] parsed = loadData(parser, location);
				if (parsed != null) {
					return parsed;
				}

				RegionDirectories dirs = ConfigProvider.WORLD.getWorldDirs().makeRegionDirectories(location);
				DataProperty<int[]> data = new DataProperty<>();
				new ParseDataJob(
						new Tile(location),
						dirs,
						ConfigProvider.WORLD.getWorldUUID(),
						region, poi, entities,
						(d, u) -> data.set(d),
						parser,
						null
				).execute();

				if (data.get() == null) {
					noData.add(location);
					return null;
				}
				try {
					CacheHandler.setData(parser, location, data.get());
				} catch (Exception ex) {
					LOGGER.warn("failed to cache overlay data for region {}", location, ex);
				}
				return data.get();
			}
		} finally {
			parseLocks.remove(location.asLong(), lock);
		}
	}

	// regions holds the data of this region and its 8 neighbours, indexed by
	// (regionZ + 1) * 3 + regionX + 1, where the center is this region
	private static Image parseColorGrades(int[][] regions, int min, int max, float minHue, float maxHue) {
		int[] colors = new int[IMAGE_SIZE * IMAGE_SIZE];
		for (int z = 0; z < IMAGE_SIZE; z++) {
			for (int x = 0; x < IMAGE_SIZE; x++) {
				int value = valueAt(regions, x - PADDING, z - PADDING);
				colors[z * IMAGE_SIZE + x] = getColorGrade(value, min, max, minHue, maxHue);
			}
		}

		WritableImage image = new WritableImage(IMAGE_SIZE, IMAGE_SIZE);
		image.getPixelWriter().setPixels(0, 0, IMAGE_SIZE, IMAGE_SIZE, PixelFormat.getIntArgbPreInstance(), colors, 0, IMAGE_SIZE);

		return image;
	}

	// chunk coordinates are relative to this region and range from -1 to 32
	private static int valueAt(int[][] regions, int chunkX, int chunkZ) {
		int regionX = chunkX < 0 ? -1 : chunkX >= Tile.SIZE_IN_CHUNKS ? 1 : 0;
		int regionZ = chunkZ < 0 ? -1 : chunkZ >= Tile.SIZE_IN_CHUNKS ? 1 : 0;
		int[] data = regions[(regionZ + 1) * 3 + regionX + 1];
		if (data != null) {
			return data[(chunkZ & CHUNK_MASK) * Tile.SIZE_IN_CHUNKS + (chunkX & CHUNK_MASK)];
		}
		// no data for that neighbour, repeat the closest value of this region
		return regions[4][clampToRegion(chunkZ) * Tile.SIZE_IN_CHUNKS + clampToRegion(chunkX)];
	}

	private static int clampToRegion(int chunkCoord) {
		return Math.max(0, Math.min(chunkCoord, Tile.SIZE_IN_CHUNKS - 1));
	}

	private static int getColorGrade(int value, int min, int max, float minHue, float maxHue) {
		if (value <= min) {
			return Color.HSBtoRGB(minHue, 1, 1);
		}
		if (value >= max) {
			return Color.HSBtoRGB(maxHue, 1, 1);
		}

		float percent = (float) (value - min) / (max - min);
		float hue = minHue + percent * (maxHue - minHue);

		return Color.HSBtoRGB(hue, 1, 1);
	}

	public void push(Point2i location, int[] data) {
		try {
			CacheHandler.setData(tileMap.getOverlay(), location, data);
		} catch (Exception ex) {
			LOGGER.warn("failed to cache data for region {}", location, ex);
		}
	}

	public void switchTo(String dbPath) {
		try {
			CacheHandler.switchTo(dbPath);
			hoveredRegion = null;
			hoveredRegionData = null;
		} catch (IOException ex) {
			LOGGER.warn("failed to switch cache db", ex);
		}
	}

	public void clear(boolean initCache) {
		try {
			CacheHandler.clear(ConfigProvider.WORLD.getCacheDBDir(), initCache);
			hoveredRegion = null;
			hoveredRegionData = null;
		} catch (IOException ex) {
			LOGGER.warn("failed to clear data cache", ex);
		}
		noData.clear();
	}

	public void discardData(Point2i region) {
		try {
			CacheHandler.deleteData(region);
			if (region.equals(hoveredRegion)) {
				hoveredRegion = null;
				hoveredRegionData = null;
			}
			LOGGER.debug("removed data for {} from data pool", region);
		} catch (IOException ex) {
			LOGGER.warn("failed to remove data from cache", ex);
		}
		noData.remove(region);
	}

	public void getHoveredChunkValue(Point2i chunk, Consumer<Integer> callback) {
		if (parser == null) {
			callback.accept(null);
		}
		Point2i region = chunk.chunkToRegion();
		Point2i normalizedChunk = chunk.asRelativeChunk();
		if (region.equals(hoveredRegion)) {
			if (hoveredRegionData != null) {
				callback.accept(hoveredRegionData[normalizedChunk.getZ() * 32 + normalizedChunk.getX()]);
			} else {
				callback.accept(null);
			}
		} else {
			overlayValueLoader.getQueue().clear(); // no need to load anything else
			overlayValueLoader.execute(() -> {
				try {
					int[] regionData = CacheHandler.getData(parser, region);
					hoveredRegion = region;
					hoveredRegionData = regionData;
					if (regionData == null) {
						Platform.runLater(() -> callback.accept(null));
						return;
					}
					Platform.runLater(() -> callback.accept(regionData[normalizedChunk.getZ() * 32 + normalizedChunk.getX()]));
				} catch (IOException | DBException ex) {
					LOGGER.warn("failed to load data for overlay value", ex);
					Platform.runLater(() -> callback.accept(null));
				}
			});
		}
	}
}
