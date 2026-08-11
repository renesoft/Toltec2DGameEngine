package org.toltec.unit;

import org.toltec.engine.TileGameEngine;
import org.toltec.render.AnimationClip;
import org.toltec.render.AssetStorage;
import org.toltec.render.GraphicObject;
import org.toltec.render.ObjectTextureConfig;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
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
 * Loads per-unit animation sets from an ini-style config file and resolves
 * them at runtime for a given (gender, weapon, status, direction) combo.
 *
 * <h3>File format</h3>
 * <pre>
 *   # lines starting with # or ; are comments
 *   basedir=res/units             # optional; frame paths below resolve against this
 *   bgcolor=0,0,0                 # trim colour; also the sheet-slicing separator colour (needed for auto-slicing, see below)
 *
 *   # already-cut frames: pipe-separated list, loaded as-is
 *   anim[woman][sword][walk][0]    = walk_0.1.png|walk_0.2.png|walk_0.3.png
 *
 *   # ONE file for an anim[...] entry is auto-sliced into frames (same grid
 *   # algorithm AssetStorage.loadAnimation uses: rows/cols that are solid
 *   # bgcolor are treated as separators) and size-normalised. Requires bgcolor= above.
 *   anim[woman][sword][run][0]     = run_0_sheet.png
 *
 *   # static pose: single image, never sliced, no animation
 *   img[woman][sword][idle][0]     = idle_0.png
 *
 *   # optional per-clip overrides (same 4 brackets)
 *   speed[woman][sword][walk][0]     = 6       # game ticks between frames (default 6)
 *   duration[woman][sword][walk][0]  = 500     # OR: total milliseconds to play this clip once —
 *                                               # one loop cycle for a looping status (idle, walk,
 *                                               # run, berserk) or the whole sequence for a one-shot
 *                                               # status (attack, hit, falling, dying). Fixed
 *                                               # wall-clock timing, independent of tickIntervalMs.
 *                                               # If both are given, duration wins and speed/fps is
 *                                               # ignored. For walk/run this is ALSO how long the
 *                                               # unit takes to cross one map cell — see Unit — so
 *                                               # the animation and the movement stay in sync; for
 *                                               # attack/hit/falling/dying it's how long that action
 *                                               # takes before Unit#onAnimationFinished fires (e.g.
 *                                               # damage should be applied then, not on click — see
 *                                               # Player#attack / Goblin#landHit).
 *   loop[woman][sword][attack][0]  = false     # default depends on the status, see UnitStatus#defaultLoop
 *   order[woman][sword][walk][0]   = reverse   # play sliced/listed frames back-to-front (default: forward)
 *   scale[woman][sword][*][*]      = 1.6       # draw this unit's sprite 1.6x its natural size;
 *                                               # feet stay anchored to the same point on the tile
 *                                               # (default 1.0)
 *   sizeCells[woman][sword][*][*]  = 1         # footprint side length in map cells (e.g. 2 for
 *                                               # "2x2") — drives the automatic isometric drop (see
 *                                               # GraphicObject#footprintCols) the same way it does
 *                                               # for ObjectTextureConfig: sizeCells=1 sits
 *                                               # cellHeight/2 lower, sizeCells=2 ("2x2")
 *                                               # 2*cellHeight/2, instead of the same fixed drop
 *                                               # regardless of size. Unlike ObjectTextureConfig,
 *                                               # there's no engine-level default here — omit this
 *                                               # line entirely and the unit's footprint drop stays
 *                                               # off (0), exactly as before this option existed, so
 *                                               # hand-written configs that never set it render
 *                                               # unchanged. The object editor writes it explicitly
 *                                               # (defaulting to 1) for units created through it.
 *   fitToCell[woman][sword][*][*]  = true      # scale the sprite to fit the cell width (like
 *                                               # ObjectTextureConfig's fitToCell) instead of
 *                                               # drawing at native pixel size — combine with
 *                                               # scale[...] above as a multiplier on top of that
 *                                               # fit. Same "omit = unchanged" rule as sizeCells.
 *
 *   # combat rolls (see Unit#hitChance / dodgeChance / blockChance / attackInterruptChance) —
 *   # typically written once per unit with status/angle wildcarded, since they're unit stats
 *   # rather than per-clip properties, but they follow the exact same [gender][weapon][status]
 *   # [angle] + wildcard-fallback lookup as everything else above:
 *   hitChance[woman][sword][*][*]             = 0.9   # chance this unit's own attacks connect at all (default 1.0)
 *   dodgeChance[woman][sword][*][*]           = 0.1   # chance this unit dodges an incoming attack entirely (default 0.0)
 *   blockChance[woman][sword][*][*]           = 0.15  # chance this unit blocks an incoming attack, negating its damage (default 0.0)
 *   attackInterruptChance[woman][sword][*][*] = 0.3   # chance taking damage mid-swing cancels the swing (default 1.0 — always cancels)
 *   # None of the four above are ever unset by omission: leaving a directive out of the
 *   # config entirely leaves whatever value the game already assigned in Java untouched
 *   # (they don't fall back to the defaults above unless nothing — config OR Java — ever set
 *   # them; those numbers are just Unit's own field defaults). Only write the ones you want
 *   # to actually override from the config file.
 *
 *   # any bracket may be "*" — tried as a fallback, most-specific match wins
 *   anim[*][unarmed][idle][*]      = unarmed_idle.png
 *
 *   # PATTERN: if a bracket is "*" and its placeholder appears in the value,
 *   # the line expands into one concrete entry per possible value — e.g. your
 *   # own files named like ..._Body_225.png for every angle:
 *   anim[woman][sword][walk][*]    = WalkForward_Sword/WalkForward_Sword_Body_{angle}.png
 *   #   -> expands into angle 0,45,...,315, each with {angle} replaced by that number.
 *   #   {gender} and {status} work the same way when their bracket is "*".
 *   #   {weapon} is NOT expanded (weapon names are free-form, not a fixed set).
 * </pre>
 *
 * Brackets are always, in this order: {@code [gender][weapon][status][angle]}.
 * <ul>
 *   <li><b>gender</b> — "man" / "woman" / "*" (see {@link Gender#key()})</li>
 *   <li><b>weapon</b> — free-form, e.g. "sword", "bow", "unarmed" / "*"</li>
 *   <li><b>status</b> — one of {@link UnitStatus#key()} (idle, walk, run, attack, hit,
 *       falling, dying, lying, berserk) / "*"</li>
 *   <li><b>angle</b>  — "0","45",...,"315" (0 = facing up/north, clockwise),
 *       or a {@link Direction8} name (N, NE, E, ...) / "*"</li>
 * </ul>
 *
 * At lookup time {@link #resolve} tries the exact key first, then falls back
 * through every combination of wildcards, always preferring the match with
 * fewer wildcards — so you only need to author the specific combinations
 * that actually differ and can lean on "*" for the rest.
 */
public class UnitAnimationConfig {

    private static final String WILDCARD = "*";

    private static final Pattern ENTRY_PATTERN = Pattern.compile(
            "^(anim|img|fps|speed|duration|loop|order|scale|sizeCells|fitToCell|hitChance|dodgeChance|blockChance|attackInterruptChance)" +
            "\\[([^\\]]*)]\\[([^\\]]*)]\\[([^\\]]*)]\\[([^\\]]*)]\\s*=\\s*(.+)$");

    private static final List<String> GENDER_DOMAIN =
            Arrays.stream(Gender.values()).map(Gender::key).toList();
    private static final List<String> STATUS_DOMAIN =
            Arrays.stream(UnitStatus.values()).map(UnitStatus::key).toList();
    private static final List<String> ANGLE_DOMAIN =
            Arrays.stream(Direction8.values()).map(Direction8::key).toList();

    /** Raw, not-yet-materialised clip data collected while parsing. */
    private static final class RawClip {
        List<String> frameFiles;
        boolean      isAnim; // true = "anim" directive (may be auto-sliced), false = "img" (always static)
        Integer      ticksPerFrame;
        Integer      durationMs;
        Boolean      loop;
        Boolean      reverse;
        Double       scale;
        Integer      sizeCells;
        Boolean      fitToCell;
        Double       hitChance;
        Double       dodgeChance;
        Double       blockChance;
        Double       attackInterruptChance;
    }

    private final Map<String, RawClip>       raw   = new HashMap<>();
    private final Map<String, AnimationClip> clips = new HashMap<>();
    private final AssetStorage assets;

    /**
     * Every asset key this config has loaded (across every gender/weapon/
     * status/angle combination), so the final normalisation pass in
     * {@link #load} can size all of them consistently. See {@link #normalizeFrameSizes()}.
     */
    private final Set<String> ownedKeys = new HashSet<>();

    public UnitAnimationConfig(AssetStorage assets) {
        this.assets = assets;
    }

    // =========================================================================
    // Loading
    // =========================================================================

    /**
     * Parse a config file and load every referenced frame into {@link AssetStorage}.
     *
     * <p>{@code configResource} is a <b>classpath resource</b> path, not a filesystem
     * path — e.g. just {@code "units.ini"} (or {@code "/units.ini"}) for a file sitting
     * directly under {@code src/main/resources}, or {@code "config/units.ini"} for one
     * under {@code src/main/resources/config/}. This works regardless of the process's
     * current working directory or how the app is launched (IDE, {@code java -jar}, …),
     * so there's no need to hardcode an absolute filesystem path here. Frame paths
     * referenced inside the file (in {@code anim[...]}/{@code img[...]} lines) are
     * themselves resolved the same way — relative to the config resource's own
     * directory, unless a {@code basedir=} line overrides it.
     */
    public void load(String configResource) throws IOException {
        String configPath = configResource.startsWith("/") ? configResource : "/" + configResource;
        String baseDir    = parentResourceDir(configPath);
        Color bgColor     = null; // null = no trimming/slicing colour set

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

                Matcher m = ENTRY_PATTERN.matcher(line);
                if (!m.matches()) {
                    System.err.println("UnitAnimationConfig: skipping unrecognised line: " + line);
                    continue;
                }

                String directive = m.group(1);
                String gRaw = m.group(2), wRaw = m.group(3), sRaw = m.group(4), aRaw = m.group(5);
                String value = m.group(6).trim();

                for (Object[] combo : expandPattern(gRaw, wRaw, sRaw, aRaw, value)) {
                    String key = normalizedKey((String) combo[0], (String) combo[1], (String) combo[2], (String) combo[3]);
                    String v   = (String) combo[4];

                    RawClip rc = raw.computeIfAbsent(key, k -> new RawClip());
                    switch (directive) {
                        case "anim"         -> { rc.frameFiles = splitFrames(v); rc.isAnim = true; }
                        case "img"          -> { rc.frameFiles = List.of(v);     rc.isAnim = false; }
                        case "fps", "speed" -> rc.ticksPerFrame = Integer.parseInt(v);
                        case "duration"     -> rc.durationMs    = Integer.parseInt(v);
                        case "loop"         -> rc.loop          = Boolean.parseBoolean(v);
                        case "order"        -> rc.reverse       = v.trim().equalsIgnoreCase("reverse");
                        case "scale"        -> rc.scale         = Double.parseDouble(v);
                        case "sizeCells"    -> rc.sizeCells     = Integer.parseInt(v);
                        case "fitToCell"    -> rc.fitToCell     = Boolean.parseBoolean(v);
                        case "hitChance"              -> rc.hitChance              = Double.parseDouble(v);
                        case "dodgeChance"            -> rc.dodgeChance            = Double.parseDouble(v);
                        case "blockChance"            -> rc.blockChance            = Double.parseDouble(v);
                        case "attackInterruptChance"  -> rc.attackInterruptChance  = Double.parseDouble(v);
                    }
                }
            }
        }

        materialise(baseDir, bgColor);
        normalizeFrameSizes();
    }

    /**
     * Filesystem counterpart of {@link #load(String)} — identical file format
     * and wildcard/pattern-expansion rules, but {@code configFile} is an
     * arbitrary path on disk instead of a classpath resource, and every frame
     * path referenced inside it resolves relative to {@code configFile}'s own
     * parent directory (or absolutely, if the referenced path is itself
     * absolute). Used by the object editor's live preview and by any other
     * tooling that works with loose files outside the packaged classpath.
     */
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

                Matcher m = ENTRY_PATTERN.matcher(line);
                if (!m.matches()) {
                    System.err.println("UnitAnimationConfig: skipping unrecognised line: " + line);
                    continue;
                }

                String directive = m.group(1);
                String gRaw = m.group(2), wRaw = m.group(3), sRaw = m.group(4), aRaw = m.group(5);
                String value = m.group(6).trim();

                for (Object[] combo : expandPattern(gRaw, wRaw, sRaw, aRaw, value)) {
                    String key = normalizedKey((String) combo[0], (String) combo[1], (String) combo[2], (String) combo[3]);
                    String v   = (String) combo[4];

                    RawClip rc = raw.computeIfAbsent(key, k -> new RawClip());
                    switch (directive) {
                        case "anim"         -> { rc.frameFiles = splitFrames(v); rc.isAnim = true; }
                        case "img"          -> { rc.frameFiles = List.of(v);     rc.isAnim = false; }
                        case "fps", "speed" -> rc.ticksPerFrame = Integer.parseInt(v);
                        case "duration"     -> rc.durationMs    = Integer.parseInt(v);
                        case "loop"         -> rc.loop          = Boolean.parseBoolean(v);
                        case "order"        -> rc.reverse       = v.trim().equalsIgnoreCase("reverse");
                        case "scale"        -> rc.scale         = Double.parseDouble(v);
                        case "sizeCells"    -> rc.sizeCells     = Integer.parseInt(v);
                        case "fitToCell"    -> rc.fitToCell     = Boolean.parseBoolean(v);
                        case "hitChance"              -> rc.hitChance              = Double.parseDouble(v);
                        case "dodgeChance"            -> rc.dodgeChance            = Double.parseDouble(v);
                        case "blockChance"            -> rc.blockChance            = Double.parseDouble(v);
                        case "attackInterruptChance"  -> rc.attackInterruptChance  = Double.parseDouble(v);
                    }
                }
            }
        }

        materialisePath(baseDir, bgColor);
        normalizeFrameSizes();
    }

    // =========================================================================
    // Classpath resource-path helpers
    // =========================================================================

    /** Directory portion of a classpath resource path (always starts with "/"; "/" itself if there's no parent). */
    private static String parentResourceDir(String resourcePath) {
        int idx = resourcePath.lastIndexOf('/');
        return idx <= 0 ? "/" : resourcePath.substring(0, idx);
    }

    /** Resolves a {@code basedir=} value against the config's own resource directory (absolute if it starts with "/"). */
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

    // =========================================================================
    // Pattern expansion — {gender}/{status}/{angle} in a wildcarded bracket's value
    // =========================================================================

    private record Expandable(String placeholder, List<String> domain) {}

    /**
     * If a bracket is "*" and the value references its placeholder
     * ({@code {gender}}, {@code {status}}, {@code {angle}}), expands the line
     * into one concrete [gender,weapon,status,angle,value] combo per possible
     * value of that bracket, substituting the placeholder in each. Brackets
     * without a matching placeholder (or not wildcarded) pass through
     * unchanged. Returns a single combo, unchanged, if nothing to expand.
     */
    private static List<Object[]> expandPattern(String g, String w, String s, String a, String value) {
        List<Expandable> dims = new ArrayList<>();
        if (g.equals(WILDCARD) && value.contains("{gender}")) dims.add(new Expandable("gender", GENDER_DOMAIN));
        if (s.equals(WILDCARD) && value.contains("{status}")) dims.add(new Expandable("status", STATUS_DOMAIN));
        if (a.equals(WILDCARD) && value.contains("{angle}"))  dims.add(new Expandable("angle",  ANGLE_DOMAIN));
        // {weapon} is intentionally not auto-expanded — weapon names aren't a fixed enumerable set.

        List<Object[]> combos = new ArrayList<>();
        combos.add(new Object[]{g, w, s, a, value});

        for (Expandable dim : dims) {
            List<Object[]> next = new ArrayList<>();
            for (Object[] combo : combos) {
                for (String v : dim.domain()) {
                    Object[] nc = combo.clone();
                    String v_file = v;
                    if (v_file.length()<3)
                        v_file = "0"+v_file;
                    if (v_file.length()<3)
                        v_file = "0"+v_file;
                    nc[4] = ((String) nc[4]).replace("{" + dim.placeholder() + "}", v_file);
                    switch (dim.placeholder()) {
                        case "gender" -> nc[0] = v;
                        case "status" -> nc[2] = v;
                        case "angle"  -> nc[3] = v;
                    }
                    next.add(nc);
                }
            }
            combos = next;
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
    // Materialisation — actually loads pixels into AssetStorage
    // =========================================================================

    private void materialise(String baseDir, Color bgColor) throws IOException {
        for (Map.Entry<String, RawClip> e : raw.entrySet()) {
            RawClip rc = e.getValue();
            if (rc.frameFiles == null || rc.frameFiles.isEmpty()) continue;

            List<String> frameKeys;
            if (rc.isAnim && rc.frameFiles.size() == 1) {
                // a single file on an anim[...] line: try to auto-slice it into frames
                frameKeys = loadAutoSliced(e.getKey(), rc.frameFiles.get(0), baseDir, bgColor);
            } else {
                // already-cut frame list (anim[...] with "|") or a static img[...] pose
                frameKeys = new ArrayList<>();
                for (String fileName : rc.frameFiles) {
                    if (!assets.has(fileName)) {
                        assets.loadImageResourceNoTrim(fileName, joinResource(baseDir, fileName));
                    }
                    frameKeys.add(fileName);
                }
            }

            ownedKeys.addAll(frameKeys);

            // Overrides (speed/duration/loop/order) may have been declared under a
            // *less specific* key than this clip's own frames ended up at — e.g. an
            // "anim[...][attack][*] = .../foo_{angle}.png" line expands into one raw
            // entry per concrete angle (because {angle} appears in the value), while
            // "duration[...][attack][*] = 100" has nothing to expand and stays a single
            // entry keyed on angle "*". Those are different map keys, so a plain
            // rc.durationMs (etc.) lookup on THIS entry would miss it. Fall back to the
            // same most-specific-wildcard search used by resolve() to find it.
            boolean reverse = firstNonNull(rc.reverse, findOverride(e.getKey(), o -> o.reverse), false);
            if (reverse) Collections.reverse(frameKeys);

            UnitStatus status = statusFromKey(e.getKey());
            Boolean loopOverride = firstNonNull(rc.loop, findOverride(e.getKey(), o -> o.loop), null);
            boolean loop = loopOverride != null ? loopOverride
                    : (status != null ? status.defaultLoop() : frameKeys.size() > 1);
            Integer ticksOverride = firstNonNull(rc.ticksPerFrame, findOverride(e.getKey(), o -> o.ticksPerFrame), null);
            int ticksPerFrame = ticksOverride != null ? ticksOverride : 6;
            Integer durationOverride = firstNonNull(rc.durationMs, findOverride(e.getKey(), o -> o.durationMs), null);
            int durationMs = durationOverride != null ? durationOverride : 0;

            // scale and the four combat-roll fields are deliberately left null when
            // nothing configures them (rather than defaulted here like the fields
            // above) — Unit#applyCurrentFrame only overwrites its own fields when the
            // resolved clip actually carries a value, so omitting a directive means
            // "don't touch it", not "reset it to some hard-coded number".
            Double scale                 = firstNonNull(rc.scale,                 findOverride(e.getKey(), o -> o.scale),                 null);
            Integer sizeCells            = firstNonNull(rc.sizeCells,             findOverride(e.getKey(), o -> o.sizeCells),             null);
            Boolean fitToCell            = firstNonNull(rc.fitToCell,             findOverride(e.getKey(), o -> o.fitToCell),             null);
            Double hitChance             = firstNonNull(rc.hitChance,             findOverride(e.getKey(), o -> o.hitChance),             null);
            Double dodgeChance           = firstNonNull(rc.dodgeChance,           findOverride(e.getKey(), o -> o.dodgeChance),           null);
            Double blockChance           = firstNonNull(rc.blockChance,           findOverride(e.getKey(), o -> o.blockChance),           null);
            Double attackInterruptChance = firstNonNull(rc.attackInterruptChance, findOverride(e.getKey(), o -> o.attackInterruptChance), null);

            clips.put(e.getKey(), new AnimationClip(frameKeys, loop, ticksPerFrame, durationMs,
                    scale, hitChance, dodgeChance, blockChance, attackInterruptChance, sizeCells, fitToCell));
        }
    }

    /** Filesystem counterpart of {@link #materialise(String, Color)} — see {@link #load(Path)}. */
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
                    String key = fileName + "@" + imgPath; // unique per source path, safe to re-derive each load
                    if (!assets.has(key)) {
                        assets.loadImageNoTrim(key, imgPath.toString());
                    }
                    frameKeys.add(key);
                }
            }

            ownedKeys.addAll(frameKeys);

            boolean reverse = firstNonNull(rc.reverse, findOverride(e.getKey(), o -> o.reverse), false);
            if (reverse) Collections.reverse(frameKeys);

            UnitStatus status = statusFromKey(e.getKey());
            Boolean loopOverride = firstNonNull(rc.loop, findOverride(e.getKey(), o -> o.loop), null);
            boolean loop = loopOverride != null ? loopOverride
                    : (status != null ? status.defaultLoop() : frameKeys.size() > 1);
            Integer ticksOverride = firstNonNull(rc.ticksPerFrame, findOverride(e.getKey(), o -> o.ticksPerFrame), null);
            int ticksPerFrame = ticksOverride != null ? ticksOverride : 6;
            Integer durationOverride = firstNonNull(rc.durationMs, findOverride(e.getKey(), o -> o.durationMs), null);
            int durationMs = durationOverride != null ? durationOverride : 0;

            Double scale                 = firstNonNull(rc.scale,                 findOverride(e.getKey(), o -> o.scale),                 null);
            Integer sizeCells            = firstNonNull(rc.sizeCells,             findOverride(e.getKey(), o -> o.sizeCells),             null);
            Boolean fitToCell            = firstNonNull(rc.fitToCell,             findOverride(e.getKey(), o -> o.fitToCell),             null);
            Double hitChance             = firstNonNull(rc.hitChance,             findOverride(e.getKey(), o -> o.hitChance),             null);
            Double dodgeChance           = firstNonNull(rc.dodgeChance,           findOverride(e.getKey(), o -> o.dodgeChance),           null);
            Double blockChance           = firstNonNull(rc.blockChance,           findOverride(e.getKey(), o -> o.blockChance),           null);
            Double attackInterruptChance = firstNonNull(rc.attackInterruptChance, findOverride(e.getKey(), o -> o.attackInterruptChance), null);

            clips.put(e.getKey(), new AnimationClip(frameKeys, loop, ticksPerFrame, durationMs,
                    scale, hitChance, dodgeChance, blockChance, attackInterruptChance, sizeCells, fitToCell));
        }
    }

    private static <T> T firstNonNull(T a, T b, T fallback) {
        if (a != null) return a;
        if (b != null) return b;
        return fallback;
    }

    /**
     * Searches {@link #raw} for the most-specific override value for {@code key}
     * across every combination of exact/wildcard in each of the 4 brackets (same
     * precedence rule as {@link #resolve}), independent of whether that raw entry
     * carries its own frame data. Lets {@code duration[...][*] = 100} apply to
     * clips whose frames were expanded out to concrete angles by a {@code {angle}}
     * pattern, even though they now live under a different, more specific key.
     */
    private <T> T findOverride(String key, java.util.function.Function<RawClip, T> getter) {
        String[] parts = key.split("\\|", -1);
        String[] g = {parts[0], WILDCARD};
        String[] w = {parts[1], WILDCARD};
        String[] s = {parts[2], WILDCARD};
        String[] a = {parts[3], WILDCARD};

        T best = null;
        int bestWildcards = Integer.MAX_VALUE;
        for (int mask = 0; mask < 16; mask++) {
            int wildcards = Integer.bitCount(mask);
            if (wildcards >= bestWildcards) continue;
            String candidateKey = normalizedKey(
                    g[mask & 1], w[(mask >> 1) & 1], s[(mask >> 2) & 1], a[(mask >> 3) & 1]);
            RawClip candidate = raw.get(candidateKey);
            if (candidate == null) continue;
            T value = getter.apply(candidate);
            if (value != null) {
                best = value;
                bestWildcards = wildcards;
            }
        }
        return best;
    }

    /**
     * Slices a single sheet image into frames via {@link AssetStorage#loadAnimation},
     * which also size-normalises them. Needs {@code bgcolor=} to know where the
     * frame separators are; without it, falls back to treating the file as one
     * static frame (with a warning) rather than failing the whole load.
     */
    private List<String> loadAutoSliced(String key, String fileName, String baseDir, Color bgColor) throws IOException {
        String baseName = "unitanim_" + Integer.toHexString(key.hashCode());
        if (assets.getFrameCount(baseName) == 0) {
            String resource = joinResource(baseDir, fileName);
            int n = bgColor != null
                    ? assets.loadAnimationGridResource(baseName, resource, bgColor)
                    : assets.loadAnimationGridResourceTrimAlpha(baseName, resource);
            if (n == 0) {
                System.err.println("UnitAnimationConfig: couldn't find more than one frame in '" + fileName
                        + "' (key " + key + ") — treating it as a single static frame instead."
                        + (bgColor == null
                        ? " (sliced by fully-transparent separator rows/columns; add e.g. \"bgcolor=0,0,0\" near the top of the config to slice by a solid colour instead)"
                        : " Check that bgcolor matches the sheet's actual background."));
                return loadAsSingleFrame(fileName, baseDir);
            }
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
        String baseName = "unitanim_" + Integer.toHexString((key + "@" + imgPath).hashCode());
        if (assets.getFrameCount(baseName) == 0) {
            int n = bgColor != null
                    ? assets.loadAnimationGrid(baseName, imgPath.toString(), bgColor)
                    : assets.loadAnimationGridTrimAlpha(baseName, imgPath.toString());
            if (n == 0) {
                System.err.println("UnitAnimationConfig: couldn't find more than one frame in '" + fileName
                        + "' (key " + key + ") — treating it as a single static frame instead."
                        + (bgColor == null
                        ? " (sliced by fully-transparent separator rows/columns; add e.g. \"bgcolor=0,0,0\" near the top of the config to slice by a solid colour instead)"
                        : " Check that bgcolor matches the sheet's actual background."));
                return loadAsSingleFramePath(fileName, baseDir);
            }
        }

        int count = assets.getFrameCount(baseName);
        List<String> frames = new ArrayList<>(count);
        for (int i = 0; i < count; i++) frames.add(baseName + "[" + i + "]");
        return frames;
    }

    /** Filesystem counterpart of {@link #loadAsSingleFrame} — see {@link #load(Path)}. */
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
     * Final pass: makes every frame this config has loaded (across every
     * gender/weapon/status/angle it touched) exactly the same pixel size.
     *
     * Without this, each angle/status is sliced and size-normalised
     * independently — fine on its own, but different angles/poses end up
     * with different canvas sizes, so the sprite visibly resizes or "jumps"
     * every time the frame, direction or status changes.
     *
     * Padding is centred horizontally but bottom-aligned vertically (not
     * centred on both axes) — the character's feet stay glued to the same
     * screen row regardless of how much headroom a given frame needed,
     * which is what {@link TileGameEngine#renderCell} assumes when it
     * anchors a sprite by its bottom edge.
     */
    private void normalizeFrameSizes() {
        if (ownedKeys.isEmpty()) return;

        int maxW = 0, maxH = 0;
        for (String key : ownedKeys) {
            BufferedImage img = assets.get(key);
            if (img == null) continue;
            maxW = Math.max(maxW, img.getWidth());
            maxH = Math.max(maxH, img.getHeight());
        }
        if (maxW == 0 || maxH == 0) return;

        for (String key : ownedKeys) {
            BufferedImage img = assets.get(key);
            if (img == null || (img.getWidth() == maxW && img.getHeight() == maxH)) continue;

            BufferedImage padded = new BufferedImage(maxW, maxH, BufferedImage.TYPE_INT_ARGB);
            Graphics2D g = padded.createGraphics();
            try {
                int dx = (maxW - img.getWidth()) / 2; // centre horizontally
                int dy = maxH - img.getHeight();       // bottom-align — feet stay put
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
     * Look up the best-matching clip for this combination, falling back
     * through wildcards in any bracket and preferring the most specific
     * match available.
     *
     * @return the clip, or {@code null} if nothing matches even with all
     *         brackets wildcarded — meaning the config file has no entry
     *         that could possibly apply here.
     */
    public AnimationClip resolve(Gender gender, String weapon, UnitStatus status, Direction8 direction) {
        String[] g = {gender.key(),         WILDCARD};
        String[] w = {normToken(weapon),    WILDCARD};
        String[] s = {status.key(),         WILDCARD};
        String[] a = {direction.key(),      WILDCARD};

        AnimationClip best = null;
        int bestWildcards = Integer.MAX_VALUE;

        // 16 = every combination of "exact or wildcard" across the 4 brackets.
        for (int mask = 0; mask < 16; mask++) {
            int wildcards = Integer.bitCount(mask);
            if (wildcards >= bestWildcards) continue;

            String key = normalizedKey(
                    g[mask & 1], w[(mask >> 1) & 1], s[(mask >> 2) & 1], a[(mask >> 3) & 1]);
            AnimationClip clip = clips.get(key);
            if (clip != null) {
                best = clip;
                bestWildcards = wildcards;
            }
        }
        return best;
    }

    // =========================================================================
    // Key helpers
    // =========================================================================

    private static String normalizedKey(String gender, String weapon, String status, String angle) {
        return normGender(gender) + "|" + normToken(weapon) + "|" + normStatus(status) + "|" + normAngle(angle);
    }

    private static String normGender(String s) {
        s = s.trim();
        return s.equals(WILDCARD) ? WILDCARD : s.toLowerCase();
    }

    private static String normToken(String s) {
        s = s == null ? "" : s.trim();
        return s.isEmpty() ? WILDCARD : s.toLowerCase();
    }

    private static String normStatus(String s) {
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

    /** Best-effort extraction of the status portion of a normalised key, used to pick a default loop flag. */
    private static UnitStatus statusFromKey(String key) {
        String[] parts = key.split("\\|", -1);
        if (parts.length < 3 || parts[2].equals(WILDCARD)) return null;
        try { return UnitStatus.valueOf(parts[2].toUpperCase()); }
        catch (IllegalArgumentException e) { return null; }
    }
}
