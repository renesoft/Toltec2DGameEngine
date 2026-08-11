package org.toltec.render;

import org.toltec.unit.Direction8;
import org.toltec.unit.Unit;
import org.toltec.unit.UnitAnimationConfig;

import java.awt.Color;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Loads a single decorative/interactive prop — a {@link GraphicObject} that is
 * neither a floor tile ({@link TileTextureConfig}) nor a full combat
 * {@link Unit} ({@link UnitAnimationConfig}): crates, doors, torches,
 * signposts, rocks, chests, and so on. Sits deliberately between the two —
 * simpler than a unit (no gender/weapon/combat stats), but still supports an
 * optional free-form {@code state} (e.g. "closed"/"open"/"broken") and an
 * optional facing direction per {@link Direction8}, each of which may be a
 * single static pose or a multi-frame animation.
 *
 * <h3>File format</h3>
 * <pre>
 *   # lines starting with # or ; are comments
 *   basedir=.                        # optional; paths below resolve against this
 *   bgcolor=0,0,0                    # optional; only needed to auto-slice a single sheet image
 *
 *   # brackets are [state][angle] — either may be "*" (fallback wildcard, most-specific wins)
 *   anim[open][*]   = open_0.png|open_1.png|open_2.png   # already-cut frames
 *   anim[idle][*]   = idle_sheet.png                     # single file: auto-sliced (needs bgcolor=)
 *   img[closed][*]  = closed.png                         # static pose, never sliced
 *
 *   duration[open][*] = 400          # total ms to play once (loops or one-shot — see loop[...] below)
 *   speed[open][*]    = 6            # OR ticks-per-frame, used only if duration is absent
 *   loop[idle][*]     = true         # default: true if this clip has >1 frame, false otherwise
 *   order[open][*]    = reverse      # play frames back-to-front
 *   scale[*][*]       = 1.0          # size multiplier, feet/base stay anchored to the tile
 *
 *   # PATTERN: a wildcarded angle bracket whose value contains {angle} expands
 *   # into one concrete entry per direction, e.g. for a file-per-direction sheet:
 *   anim[idle][*] = Torch/Torch_{angle}.png
 *
 *   # flat, whole-object properties (not bracketed) — see GraphicObject:
 *   collision  = true      # blocks movement onto this object's cell, default false
 *   layer      = 3         # draw order within the cell, default 0
 *   isometric  = true       # draw as an isometric diamond-anchored sprite, default true
 *   drawWidth  = 64         # fixed pixel size; -1 (default) = use the asset's natural size
 *   drawHeight = 64
 *   fitToCell  = false      # if true, scale proportionally so width == cell width * fitScale
 *   fitScale   = 1.0
 *   xOffset    = 0          # pixel nudge, e.g. to fine-tune anchor point
 *   yOffset    = 0
 *   sizeCols   = 1          # footprint size in map cells, along the col (east/right)
 *   sizeRows   = 1          # and row (south/down) axes — e.g. sizeCols=2,sizeRows=3 for
 *                           # an object covering a 2-wide x 3-deep rectangle, anchored at
 *                           # (and drawn extending down-and-right from) the cell it's
 *                           # placed in. Drives the automatic isometric drop (see
 *                           # GraphicObject#footprintCols/footprintRows) and, when
 *                           # fitToCell=true, proportional width scaling, so a bigger
 *                           # object's base still lines up with the ground it covers
 *                           # instead of the same fixed offset/size regardless of
 *                           # footprint. Default 1x1 (a plain single-cell object).
 *                           # NOTE: this only affects the drop/drawn size — it does NOT
 *                           # make the object occupy/collide across the extra cells on
 *                           # the map; it still lives in exactly the one cell it's placed
 *                           # in. Multi-cell occupancy/collision isn't implemented.
 * </pre>
 *
 * <h3>Image loading — no cropping</h3>
 * Every image an {@code ObjectTextureConfig} loads (single poses AND
 * animation sheets) keeps its full, uncropped canvas — see {@link
 * AssetStorage#loadImageNoTrim}. A sheet referenced by a single {@code
 * anim[...]} file is auto-sliced into an even N-row × M-column grid (rows/
 * columns auto-detected the same way {@code bgcolor=}/transparency-based
 * separator detection always has — see {@link AssetStorage#loadAnimationGrid}/
 * {@link AssetStorage#loadAnimationGridTrimAlpha}), never cropped per frame.
 *
 * At lookup time {@link #resolve} tries the exact key first, then falls back
 * through wildcards in either bracket, preferring the most specific match —
 * identical precedence rule to {@link UnitAnimationConfig#resolve}.
 */
public class ObjectTextureConfig {

    private static final String WILDCARD = "*";

    private static final Pattern ENTRY_PATTERN = Pattern.compile(
            "^(anim|img|fps|speed|duration|loop|order|scale)\\[([^\\]]*)]\\[([^\\]]*)]\\s*=\\s*(.+)$");

    private static final Pattern FLAT_PATTERN = Pattern.compile(
            "^(collision|layer|isometric|drawWidth|drawHeight|fitToCell|fitScale|xOffset|yOffset|sizeCols|sizeRows)\\s*=\\s*(.+)$");

    private static final List<String> ANGLE_DOMAIN =
            Arrays.stream(Direction8.values()).map(Direction8::key).toList();

    private static final class RawClip {
        List<String> frameFiles;
        boolean      isAnim;
        Integer      ticksPerFrame;
        Integer      durationMs;
        Boolean      loop;
        Boolean      reverse;
        Double       scale;
    }

    private final Map<String, RawClip>       raw   = new HashMap<>();
    private final Map<String, AnimationClip> clips = new HashMap<>();
    private final AssetStorage assets;
    private final Set<String> ownedKeys = new HashSet<>();

    // ── Flat, whole-object properties — see GraphicObject for what each means. ──
    public boolean collision  = false;
    public int     layer      = 0;
    public boolean isometric  = true;
    public int     drawWidth  = -1;
    public int     drawHeight = -1;
    public boolean fitToCell  = false;
    public double  fitScale   = 1.0;
    public int     xOffset    = 0;
    public int     yOffset    = 0;

    /**
     * Footprint size in map cells, along the col (east/right) and row
     * (south/down) axes — default {@code 1x1}, i.e. a plain single-cell
     * object. See {@link GraphicObject#footprintCols}/{@link
     * GraphicObject#footprintRows}, which these are copied onto in {@link #applyTo}.
     */
    public int     sizeCols   = 1;
    public int     sizeRows   = 1;

    public ObjectTextureConfig(AssetStorage assets) {
        this.assets = assets;
    }

    // =========================================================================
    // Loading — classpath
    // =========================================================================

    /** Classpath-resource variant — see {@link UnitAnimationConfig#load(String)} for the resolution rules. */
    public void load(String configResource) throws IOException {
        String configPath = configResource.startsWith("/") ? configResource : "/" + configResource;
        String baseDir    = parentResourceDir(configPath);
        Color bgColor     = null;

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
                    baseDir = resolveResourceDir(baseDir, line.substring(8).trim());
                    continue;
                }
                if (line.startsWith("bgcolor=")) {
                    bgColor = parseColor(line.substring(8).trim());
                    continue;
                }
                if (parseFlatLine(line)) continue;

                Matcher m = ENTRY_PATTERN.matcher(line);
                if (!m.matches()) {
                    System.err.println("ObjectTextureConfig: skipping unrecognised line: " + line);
                    continue;
                }
                parseEntry(m);
            }
        }

        materialise(baseDir, bgColor);
        normalizeFrameSizes();
    }

    // =========================================================================
    // Loading — filesystem
    // =========================================================================

    /** Filesystem variant — see {@link UnitAnimationConfig#load(Path)} for the resolution rules. */
    public void load(Path configFile) throws IOException {
        Path  baseDir = configFile.toAbsolutePath().getParent();
        Color bgColor = null;

        try (BufferedReader r = Files.newBufferedReader(configFile, StandardCharsets.UTF_8)) {
            String line;
            while ((line = r.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#") || line.startsWith(";")) continue;

                if (line.startsWith("basedir=")) {
                    baseDir = resolvePathDir(baseDir, line.substring(8).trim());
                    continue;
                }
                if (line.startsWith("bgcolor=")) {
                    bgColor = parseColor(line.substring(8).trim());
                    continue;
                }
                if (parseFlatLine(line)) continue;

                Matcher m = ENTRY_PATTERN.matcher(line);
                if (!m.matches()) {
                    System.err.println("ObjectTextureConfig: skipping unrecognised line: " + line);
                    continue;
                }
                parseEntry(m);
            }
        }

        materialisePath(baseDir, bgColor);
        normalizeFrameSizes();
    }

    /** @return true if the line was a flat (non-bracketed) property and was consumed. */
    private boolean parseFlatLine(String line) {
        Matcher f = FLAT_PATTERN.matcher(line);
        if (!f.matches()) return false;
        String value = f.group(2).trim();
        switch (f.group(1)) {
            case "collision"  -> collision  = Boolean.parseBoolean(value);
            case "layer"      -> layer      = Integer.parseInt(value);
            case "isometric"  -> isometric  = Boolean.parseBoolean(value);
            case "drawWidth"  -> drawWidth  = Integer.parseInt(value);
            case "drawHeight" -> drawHeight = Integer.parseInt(value);
            case "fitToCell"  -> fitToCell  = Boolean.parseBoolean(value);
            case "fitScale"   -> fitScale   = Double.parseDouble(value);
            case "xOffset"    -> xOffset    = Integer.parseInt(value);
            case "yOffset"    -> yOffset    = Integer.parseInt(value);
            case "sizeCols"   -> sizeCols   = Integer.parseInt(value);
            case "sizeRows"   -> sizeRows   = Integer.parseInt(value);
        }
        return true;
    }

    private void parseEntry(Matcher m) {
        String directive = m.group(1);
        String sRaw = m.group(2), aRaw = m.group(3);
        String value = m.group(4).trim();

        for (Object[] combo : expandPattern(sRaw, aRaw, value)) {
            String key = normalizedKey((String) combo[0], (String) combo[1]);
            String v   = (String) combo[2];

            RawClip rc = raw.computeIfAbsent(key, k -> new RawClip());
            switch (directive) {
                case "anim"         -> { rc.frameFiles = splitFrames(v); rc.isAnim = true; }
                case "img"          -> { rc.frameFiles = List.of(v);     rc.isAnim = false; }
                case "fps", "speed" -> rc.ticksPerFrame = Integer.parseInt(v);
                case "duration"     -> rc.durationMs    = Integer.parseInt(v);
                case "loop"         -> rc.loop          = Boolean.parseBoolean(v);
                case "order"        -> rc.reverse       = v.trim().equalsIgnoreCase("reverse");
                case "scale"        -> rc.scale         = Double.parseDouble(v);
            }
        }
    }

    /** Applies the flat, whole-object properties this config carries onto {@code obj}. */
    public void applyTo(GraphicObject obj) {
        obj.collision  = collision;
        obj.layer      = layer;
        obj.setIsometricType(isometric);
        obj.drawWidth  = drawWidth;
        obj.drawHeight = drawHeight;
        obj.fitToCell  = fitToCell;
        obj.fitScale   = fitScale;
        obj.xOffset    = xOffset;
        obj.yOffset    = yOffset;
        obj.footprintCols = Math.max(1, sizeCols);
        obj.footprintRows = Math.max(1, sizeRows);
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
    // Filesystem-path helpers (mirrors UnitAnimationConfig#load(Path))
    // =========================================================================

    private static Path resolvePathDir(Path currentDir, String rel) {
        if (rel.isEmpty() || rel.equals(".") || rel.equals("./")) return currentDir;
        Path p = Path.of(rel);
        return p.isAbsolute() ? p : currentDir.resolve(rel).normalize();
    }

    private static Path joinPath(Path baseDir, String fileName) {
        Path p = Path.of(fileName);
        return p.isAbsolute() ? p : baseDir.resolve(fileName).normalize();
    }

    // =========================================================================
    // Pattern expansion — {angle} in a wildcarded angle bracket's value
    // =========================================================================

    private static List<Object[]> expandPattern(String s, String a, String value) {
        if (!(a.equals(WILDCARD) && value.contains("{angle}"))) {
            List<Object[]> single = new ArrayList<>();
            single.add(new Object[]{s, a, value});
            return single;
        }
        List<Object[]> combos = new ArrayList<>();
        for (String angle : ANGLE_DOMAIN) {
            String padded = angle.length() < 3 ? ("0".repeat(3 - angle.length()) + angle) : angle;
            combos.add(new Object[]{s, angle, value.replace("{angle}", padded)});
        }
        return combos;
    }

    private static List<String> splitFrames(String value) {
        List<String> out = new ArrayList<>();
        for (String part : value.split("\\|")) {
            String p = part.trim();
            if (!p.isEmpty()) out.add(p);
        }
        return out;
    }

    private static Color parseColor(String csv) {
        String[] parts = csv.split(",");
        int r = Integer.parseInt(parts[0].trim());
        int g = Integer.parseInt(parts[1].trim());
        int b = Integer.parseInt(parts[2].trim());
        int a = parts.length > 3 ? Integer.parseInt(parts[3].trim()) : 255;
        return new Color(r, g, b, a);
    }

    // =========================================================================
    // Materialisation — classpath
    // =========================================================================

    private void materialise(String baseDir, Color bgColor) throws IOException {
        for (Map.Entry<String, RawClip> e : raw.entrySet()) {
            RawClip rc = e.getValue();
            if (rc.frameFiles == null || rc.frameFiles.isEmpty()) continue;

            List<String> frameKeys;
            if (rc.isAnim && rc.frameFiles.size() == 1) {
                frameKeys = loadAutoSliced(e.getKey(), rc.frameFiles.get(0), baseDir, bgColor);
            } else {
                frameKeys = new ArrayList<>();
                for (String fileName : rc.frameFiles) {
                    if (!assets.has(fileName)) {
                        assets.loadImageResourceNoTrim(fileName, joinResource(baseDir, fileName));
                    }
                    frameKeys.add(fileName);
                }
            }
            ownedKeys.addAll(frameKeys);
            clips.put(e.getKey(), buildClip(e.getKey(), rc, frameKeys));
        }
    }

    /** Filesystem counterpart of {@link #materialise} — see {@link #load(Path)}. */
    private void materialisePath(Path baseDir, Color bgColor) throws IOException {
        for (Map.Entry<String, RawClip> e : raw.entrySet()) {
            RawClip rc = e.getValue();
            if (rc.frameFiles == null || rc.frameFiles.isEmpty()) continue;

            List<String> frameKeys;
            if (rc.isAnim && rc.frameFiles.size() == 1) {
                frameKeys = loadAutoSlicedPath(e.getKey(), rc.frameFiles.get(0), baseDir, bgColor);
            } else {
                frameKeys = new ArrayList<>();
                for (String fileName : rc.frameFiles) {
                    Path imgPath = joinPath(baseDir, fileName);
                    String key = fileName + "@" + imgPath;
                    if (!assets.has(key)) {
                        assets.loadImageNoTrim(key, imgPath.toString());
                    }
                    frameKeys.add(key);
                }
            }
            ownedKeys.addAll(frameKeys);
            clips.put(e.getKey(), buildClip(e.getKey(), rc, frameKeys));
        }
    }

    private AnimationClip buildClip(String key, RawClip rc, List<String> frameKeys) {
        if (Boolean.TRUE.equals(rc.reverse)) Collections.reverse(frameKeys);

        boolean loop = rc.loop != null ? rc.loop : frameKeys.size() > 1;
        int ticksPerFrame = rc.ticksPerFrame != null ? rc.ticksPerFrame : 6;
        int durationMs     = rc.durationMs    != null ? rc.durationMs    : 0;

        return new AnimationClip(frameKeys, loop, ticksPerFrame, durationMs,
                rc.scale, null, null, null, null);
    }

    /**
     * Slices a single sheet file into frames via {@link AssetStorage#loadAnimationGridResource}/
     * {@link AssetStorage#loadAnimationGridResourceTrimAlpha} — an even N-row
     * × M-column grid, auto-detected but never cropped per frame (see
     * {@link AssetStorage} for why). A sheet that turns out to be just one
     * frame (no separators found at all) falls out of this the same way it
     * always did — {@code loadAnimationGrid*} still returns 1 frame in that
     * case, so there's no separate single-frame fallback needed here.
     */
    private List<String> loadAutoSliced(String key, String fileName, String baseDir, Color bgColor) throws IOException {
        String baseName = "objanim_" + Integer.toHexString(key.hashCode());
        if (assets.getFrameCount(baseName) == 0) {
            String resource = joinResource(baseDir, fileName);
            int n = bgColor != null
                    ? assets.loadAnimationGridResource(baseName, resource, bgColor)
                    : assets.loadAnimationGridResourceTrimAlpha(baseName, resource);
            if (n == 0) return loadAsSingleFrame(fileName, baseDir);
        }
        int count = assets.getFrameCount(baseName);
        List<String> frames = new ArrayList<>(count);
        for (int i = 0; i < count; i++) frames.add(baseName + "[" + i + "]");
        return frames;
    }

    private List<String> loadAsSingleFrame(String fileName, String baseDir) throws IOException {
        if (!assets.has(fileName)) {
            assets.loadImageResourceNoTrim(fileName, joinResource(baseDir, fileName));
        }
        List<String> single = new ArrayList<>(1);
        single.add(fileName);
        return single;
    }

    /** Filesystem counterpart of {@link #loadAutoSliced} — see {@link #load(Path)}. */
    private List<String> loadAutoSlicedPath(String key, String fileName, Path baseDir, Color bgColor) throws IOException {
        Path imgPath = joinPath(baseDir, fileName);
        String baseName = "objanim_" + Integer.toHexString((key + "@" + imgPath).hashCode());
        if (assets.getFrameCount(baseName) == 0) {
            int n = bgColor != null
                    ? assets.loadAnimationGrid(baseName, imgPath.toString(), bgColor)
                    : assets.loadAnimationGridTrimAlpha(baseName, imgPath.toString());
            if (n == 0) return loadAsSingleFramePath(fileName, baseDir);
        }
        int count = assets.getFrameCount(baseName);
        List<String> frames = new ArrayList<>(count);
        for (int i = 0; i < count; i++) frames.add(baseName + "[" + i + "]");
        return frames;
    }

    private List<String> loadAsSingleFramePath(String fileName, Path baseDir) throws IOException {
        Path imgPath = joinPath(baseDir, fileName);
        String key = fileName + "@" + imgPath;
        if (!assets.has(key)) {
            assets.loadImageNoTrim(key, imgPath.toString());
        }
        List<String> single = new ArrayList<>(1);
        single.add(key);
        return single;
    }

    /**
     * Final pass: normalises every frame this config has loaded to the same
     * pixel size, centred horizontally and bottom-aligned vertically — see
     * {@link UnitAnimationConfig#normalizeFrameSizes()} for why (identical
     * reasoning, so a pose/direction change never makes the sprite "jump").
     */
    private void normalizeFrameSizes() {
        if (ownedKeys.isEmpty()) return;
        int maxW = 0, maxH = 0;
        for (String key : ownedKeys) {
            java.awt.image.BufferedImage img = assets.get(key);
            if (img == null) continue;
            maxW = Math.max(maxW, img.getWidth());
            maxH = Math.max(maxH, img.getHeight());
        }
        if (maxW == 0 || maxH == 0) return;

        for (String key : ownedKeys) {
            java.awt.image.BufferedImage img = assets.get(key);
            if (img == null || (img.getWidth() == maxW && img.getHeight() == maxH)) continue;

            java.awt.image.BufferedImage padded = new java.awt.image.BufferedImage(
                    maxW, maxH, java.awt.image.BufferedImage.TYPE_INT_ARGB);
            java.awt.Graphics2D g = padded.createGraphics();
            try {
                int dx = (maxW - img.getWidth()) / 2;
                int dy = maxH - img.getHeight();
                g.drawImage(img, dx, dy, null);
            } finally {
                g.dispose();
            }
            assets.put(key, padded);
        }
    }

    // =========================================================================
    // Resolution
    // =========================================================================

    /**
     * Look up the best-matching clip for this (state, direction) combination,
     * falling back through wildcards in either bracket and preferring the
     * most specific match available — same precedence rule as
     * {@link UnitAnimationConfig#resolve}.
     *
     * @return the clip, or {@code null} if nothing in the config matches even
     *         with both brackets wildcarded.
     */
    public AnimationClip resolve(String state, Direction8 direction) {
        String[] s = {normState(state),    WILDCARD};
        String[] a = {direction.key(),     WILDCARD};

        AnimationClip best = null;
        int bestWildcards = Integer.MAX_VALUE;
        for (int mask = 0; mask < 4; mask++) {
            int wildcards = Integer.bitCount(mask);
            if (wildcards >= bestWildcards) continue;
            String key = normalizedKey(s[mask & 1], a[(mask >> 1) & 1]);
            AnimationClip clip = clips.get(key);
            if (clip != null) {
                best = clip;
                bestWildcards = wildcards;
            }
        }
        return best;
    }

    private static String normalizedKey(String state, String angle) {
        return normState(state) + "|" + normAngle(angle);
    }

    private static String normState(String s) {
        s = s.trim();
        return s.equals(WILDCARD) ? WILDCARD : s.toLowerCase();
    }

    private static String normAngle(String s) {
        s = s.trim();
        if (s.equals(WILDCARD)) return WILDCARD;
        try {
            return Direction8.fromAngle(Integer.parseInt(s)).key();
        } catch (NumberFormatException ignored) {
            try {
                return Direction8.valueOf(s.toUpperCase()).key();
            } catch (IllegalArgumentException ignored2) {
                return s;
            }
        }
    }
}
