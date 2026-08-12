package org.toltec.render;

import org.toltec.demo.UnitDemoGame;
import org.toltec.unit.UnitAnimationConfig;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Loads floor-tile texture types from a simple ini-style config file — each
 * type gets up to 4 orientation-variant images (see {@link TileType#pickVariant},
 * used purely for visual variety so a large tiled area doesn't look like the
 * same image repeated identically) plus 3 gameplay parameters.
 *
 * <p>Like {@link UnitAnimationConfig#load}, {@code configResource} is a
 * <b>classpath resource</b> path (e.g. {@code "tiles.ini"} for a file directly
 * under {@code src/main/resources}), not a filesystem path.
 *
 * <h3>File format</h3>
 * <pre>
 *   # lines starting with # or ; are comments
 *   basedir=tiles                      # optional; image paths below resolve against this
 *                                       # (relative to this config's own resource directory)
 *
 *   # up to 4 orientation-variant images per type — any subset is fine, but
 *   # matching the tileset's own _N/_E/_S/_W naming is the common case
 *   img[dirt][N] = dirt_N.png
 *   img[dirt][E] = dirt_E.png
 *   img[dirt][S] = dirt_S.png
 *   img[dirt][W] = dirt_W.png
 *
 *   walkable[dirt]        = true       # can a unit walk onto this tile? default true
 *   speedMultiplier[dirt] = 0.5        # movement-speed multiplier while standing on it, default 1.0
 *   damagePerSecond[dirt] = 0          # damage/sec applied while standing on it, default 0
 * </pre>
 *
 * See {@link #createFloorObject} to turn a loaded type into a ready-to-place
 * {@link GraphicObject} for a given cell.
 */
public class TileTextureConfig {

    private static final Pattern IMG_PATTERN  = Pattern.compile("^img\\[([^\\]]+)]\\[([^\\]]+)]\\s*=\\s*(.+)$");
    private static final Pattern PROP_PATTERN = Pattern.compile("^(walkable|speedMultiplier|damagePerSecond)\\[([^\\]]+)]\\s*=\\s*(.+)$");

    private final AssetStorage assets;

    private final Map<String, List<String>> imagesByType   = new LinkedHashMap<>();
    private final Map<String, Boolean>      walkableByType = new LinkedHashMap<>();
    private final Map<String, Double>       speedByType    = new LinkedHashMap<>();
    private final Map<String, Double>       damageByType   = new LinkedHashMap<>();
    private final Map<String, TileType>     types          = new LinkedHashMap<>();

    public TileTextureConfig(AssetStorage assets) {
        this.assets = assets;
    }

    public void load(String configResource) throws IOException {
        String configPath = configResource.startsWith("/") ? configResource : "/" + configResource;
        String baseDir    = parentResourceDir(configPath);

        try (InputStream is = getClass().getResourceAsStream(configPath)) {
            if (is == null) {
                throw new IOException("Classpath resource not found: " + configPath
                        + " — make sure the file sits under src/main/resources and the project has been (re)compiled.");
            }
            BufferedReader r = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8));
            String line;
            while ((line = r.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#") || line.startsWith(";")) continue;

                if (line.startsWith("basedir=")) {
                    baseDir = resolveResourceDir(baseDir, line.substring("basedir=".length()).trim());
                    continue;
                }

                Matcher img = IMG_PATTERN.matcher(line);
                if (img.matches()) {
                    String type = img.group(1).trim();
                    String dir  = img.group(2).trim().toUpperCase();
                    String file = img.group(3).trim();
                    String key  = "tile_" + type + "_" + dir;
                    if (!assets.has(key)) {
                        assets.loadImageResourceTrimAlpha(key, joinResource(baseDir, file));
                    }
                    imagesByType.computeIfAbsent(type, t -> new ArrayList<>()).add(key);
                    continue;
                }

                Matcher prop = PROP_PATTERN.matcher(line);
                if (prop.matches()) {
                    String directive = prop.group(1);
                    String type      = prop.group(2).trim();
                    String value     = prop.group(3).trim();
                    switch (directive) {
                        case "walkable"        -> walkableByType.put(type, Boolean.parseBoolean(value));
                        case "speedMultiplier" -> speedByType.put(type, Double.parseDouble(value));
                        case "damagePerSecond" -> damageByType.put(type, Double.parseDouble(value));
                    }
                    continue;
                }

                System.err.println("TileTextureConfig: skipping unrecognised line: " + line);
            }
        }

        finalizeTypes();
    }

    /**
     * Filesystem counterpart of {@link #load(String)} — same file format and
     * same {@code basedir=}/image resolution rules, but {@code configFile} is
     * an arbitrary path on disk instead of a classpath resource, and every
     * image path referenced inside it resolves relative to {@code configFile}'s
     * own parent directory (or absolutely, if the referenced path is itself
     * absolute — handy for tools that don't want to copy source art around
     * just to preview it). Used by the object editor's live preview and by
     * any other tooling that works with loose files outside the packaged
     * classpath.
     */
    public void load(Path configFile) throws IOException {
        Path baseDir = configFile.toAbsolutePath().getParent();

        try (BufferedReader r = Files.newBufferedReader(configFile, StandardCharsets.UTF_8)) {
            String line;
            while ((line = r.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#") || line.startsWith(";")) continue;

                if (line.startsWith("basedir=")) {
                    baseDir = resolvePathDir(baseDir, line.substring("basedir=".length()).trim());
                    continue;
                }

                Matcher img = IMG_PATTERN.matcher(line);
                if (img.matches()) {
                    String type = img.group(1).trim();
                    String dir  = img.group(2).trim().toUpperCase();
                    String file = img.group(3).trim();
                    String key  = "tile_" + type + "_" + dir;
                    if (!assets.has(key)) {
                        assets.loadImageTrimAlpha(key, joinPath(baseDir, file));
                    }
                    imagesByType.computeIfAbsent(type, t -> new ArrayList<>()).add(key);
                    continue;
                }

                Matcher prop = PROP_PATTERN.matcher(line);
                if (prop.matches()) {
                    String directive = prop.group(1);
                    String type      = prop.group(2).trim();
                    String value     = prop.group(3).trim();
                    switch (directive) {
                        case "walkable"        -> walkableByType.put(type, Boolean.parseBoolean(value));
                        case "speedMultiplier" -> speedByType.put(type, Double.parseDouble(value));
                        case "damagePerSecond" -> damageByType.put(type, Double.parseDouble(value));
                    }
                    continue;
                }

                System.err.println("TileTextureConfig: skipping unrecognised line: " + line);
            }
        }

        finalizeTypes();
    }

    private void finalizeTypes() {
        for (Map.Entry<String, List<String>> e : imagesByType.entrySet()) {
            String type = e.getKey();
            types.put(type, new TileType(
                    type,
                    e.getValue(),
                    walkableByType.getOrDefault(type, true),
                    damageByType.getOrDefault(type, 0.0),
                    speedByType.getOrDefault(type, 1.0)));
        }
    }

    public TileType get(String type) { return types.get(type); }

    public boolean has(String type) { return types.containsKey(type); }

    /**
     * Every tile type this config has loaded, in no particular order. Meant
     * for catalog/palette UIs (e.g. a map editor) that need to enumerate
     * "what floor types are available" without already knowing the type
     * name(s) up front — {@link #get}/{@link #has} still need those names.
     */
    public java.util.Set<String> typeNames() {
        return java.util.Collections.unmodifiableSet(types.keySet());
    }

    /**
     * Builds a ready-to-place floor {@link GraphicObject} for tile
     * {@code type} at (col,row): picks one of its orientation variants (see
     * {@link TileType#pickVariant}) and sets {@code isFloor}/{@code collision}/
     * {@code speedMultiplier}/{@code damagePerSecond} from the config. Caller
     * still needs to size it (drawWidth/drawHeight) and call
     * {@code cell.addObject(...)} — see {@code UnitDemoGame}'s floor-building loop.
     */
    public GraphicObject createFloorObject(String type, int col, int row) {
        TileType t = types.get(type);
        if (t == null) throw new IllegalArgumentException("Unknown tile type: " + type);

        GraphicObject obj = new GraphicObject(t.pickVariant(col, row));
        obj.isFloor         = true;
        obj.collision       = !t.walkable;
        obj.speedMultiplier = t.speedMultiplier;
        obj.damagePerSecond = t.damagePerSecond;
        return obj;
    }

    // =========================================================================
    // Classpath resource-path helpers (mirrors UnitAnimationConfig)
    // =========================================================================

    private static String parentResourceDir(String resourcePath) {
        int idx = resourcePath.lastIndexOf('/');
        return idx <= 0 ? "/" : resourcePath.substring(0, idx);
    }

    private static String resolveResourceDir(String currentDir, String rel) {
        if (rel.isEmpty() || rel.equals(".") || rel.equals("./")) return currentDir;
        if (rel.startsWith("/")) return rel;
        String base = currentDir.endsWith("/") ? currentDir : currentDir + "/";
        return base + rel;
    }

    private static String joinResource(String baseDir, String fileName) {
        String base = baseDir.endsWith("/") ? baseDir : baseDir + "/";
        return base + fileName;
    }

    // =========================================================================
    // Filesystem-path helpers (mirrors the classpath ones above, for load(Path))
    // =========================================================================

    private static Path resolvePathDir(Path currentDir, String rel) {
        if (rel.isEmpty() || rel.equals(".") || rel.equals("./")) return currentDir;
        Path p = Path.of(rel);
        return p.isAbsolute() ? p : currentDir.resolve(rel).normalize();
    }

    /** Resolves {@code fileName} against {@code baseDir} — absolute {@code fileName}s pass through untouched. */
    private static Path joinPath(Path baseDir, String fileName) {
        Path p = Path.of(fileName);
        return p.isAbsolute() ? p : baseDir.resolve(fileName).normalize();
    }
}
