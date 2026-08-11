package org.toltec.editor.io;

import org.toltec.unit.Direction8;
import org.toltec.unit.Gender;
import org.toltec.unit.UnitStatus;
import org.toltec.editor.model.ClipGroup;
import org.toltec.editor.model.ClipParams;
import org.toltec.editor.model.EditableObject;
import org.toltec.editor.model.UnitClipKey;
import org.toltec.editor.util.SheetSlicer;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns an {@link EditableObject} into the .ini text the engine's own config
 * loaders ({@code TileTextureConfig} / {@code UnitAnimationConfig} / {@code
 * ObjectTextureConfig}) can read, and turns that text back into an {@link
 * EditableObject} when re-opening a saved object. File references in the
 * model are assumed to already point at their final on-disk location — the
 * writer only ever emits {@link File#getName()}; relocating source files
 * into the object's folder is {@link ObjectRepository}'s job.
 */
public final class IniFormat {

    private IniFormat() {}

    private static final List<String> FLOOR_DIRS = List.of("N", "E", "S", "W");

    // =========================================================================
    // Writing
    // =========================================================================

    public static String write(EditableObject obj) {
        return switch (obj.category) {
            case FLOOR -> writeFloor(obj);
            case UNIT -> writeUnit(obj);
            case OBJECT -> writeObject(obj);
        };
    }

    private static String writeFloor(EditableObject obj) {
        String type = tokenOrName(obj.name);
        StringBuilder sb = new StringBuilder();
        header(sb, obj, "пол");

        for (String dir : FLOOR_DIRS) {
            File f = obj.floorImages.get(dir);
            if (f != null) {
                sb.append("img[").append(type).append("][").append(dir).append("] = ").append(f.getName()).append('\n');
            }
        }
        sb.append('\n');
        sb.append("walkable[").append(type).append("]        = ").append(obj.walkable).append('\n');
        sb.append("speedMultiplier[").append(type).append("] = ").append(num(obj.speedMultiplier)).append('\n');
        sb.append("damagePerSecond[").append(type).append("] = ").append(num(obj.damagePerSecond)).append('\n');
        return sb.toString();
    }

    private static String writeUnit(EditableObject obj) {
        StringBuilder sb = new StringBuilder();
        header(sb, obj, "юнит");

        for (Map.Entry<UnitClipKey, ClipGroup> e : obj.unitClips.entrySet()) {
            ClipGroup group = e.getValue();
            if (group.isEmpty()) continue;
            UnitClipKey key = e.getKey();
            String g = key.gender().key();
            String w = bracketSafe(key.weapon());
            String st = key.status().key();

            sb.append("# ").append(g).append(" / ").append(w).append(" / ").append(st).append('\n');
            for (Direction8 d : Direction8.values()) {
                writeFrameLine(sb, group.peek(d), "[" + g + "][" + w + "][" + st + "][" + d.key() + "]");
            }
            writeParamLines(sb, group.params, "[" + g + "][" + w + "][" + st + "][*]");
            sb.append('\n');
        }

        sb.append("# combat stats — apply no matter which gender/weapon/state ends up active\n");
        sb.append("hitChance[*][*][*][*]             = ").append(num(obj.hitChance)).append('\n');
        sb.append("dodgeChance[*][*][*][*]           = ").append(num(obj.dodgeChance)).append('\n');
        sb.append("blockChance[*][*][*][*]           = ").append(num(obj.blockChance)).append('\n');
        sb.append("attackInterruptChance[*][*][*][*] = ").append(num(obj.attackInterruptChance)).append('\n');
        sb.append("# fit/footprint — see UnitAnimationConfig's javadoc for fitToCell/sizeCells\n");
        sb.append("fitToCell[*][*][*][*]             = ").append(obj.fitToCell).append('\n');
        sb.append("scale[*][*][*][*]                 = ").append(num(obj.fitScale)).append('\n');
        sb.append("sizeCells[*][*][*][*]             = ").append(obj.sizeCells).append('\n');
        return sb.toString();
    }

    private static String writeObject(EditableObject obj) {
        StringBuilder sb = new StringBuilder();
        header(sb, obj, "объект");

        for (Map.Entry<String, ClipGroup> e : obj.objectClips.entrySet()) {
            ClipGroup group = e.getValue();
            if (group.isEmpty()) continue;
            String st = bracketSafe(e.getKey());

            sb.append("# ").append(e.getKey()).append('\n');
            if (obj.omnidirectional) {
                writeFrameLine(sb, group.peek(Direction8.S), "[" + st + "][*]");
            } else {
                for (Direction8 d : Direction8.values()) {
                    writeFrameLine(sb, group.peek(d), "[" + st + "][" + d.key() + "]");
                }
            }
            writeParamLines(sb, group.params, "[" + st + "][*]");
            sb.append('\n');
        }

        sb.append("# object properties\n");
        sb.append("collision  = ").append(obj.objCollision).append('\n');
        sb.append("layer      = ").append(obj.objLayer).append('\n');
        sb.append("isometric  = ").append(obj.isometric).append('\n');
        sb.append("drawWidth  = ").append(obj.drawWidth).append('\n');
        sb.append("drawHeight = ").append(obj.drawHeight).append('\n');
        sb.append("fitToCell  = ").append(obj.fitToCell).append('\n');
        sb.append("fitScale   = ").append(num(obj.fitScale)).append('\n');
        sb.append("xOffset    = ").append(obj.xOffset).append('\n');
        sb.append("yOffset    = ").append(obj.yOffset).append('\n');
        sb.append("sizeCols   = ").append(obj.sizeCols).append('\n');
        sb.append("sizeRows   = ").append(obj.sizeRows).append('\n');
        return sb.toString();
    }

    private static void header(StringBuilder sb, EditableObject obj, String kindRu) {
        sb.append("# ").append(obj.name).append(" — ").append(kindRu)
                .append(" (сгенерировано в редакторе объектов)\n");
        sb.append("basedir=./\n\n");
    }

    private static void writeFrameLine(StringBuilder sb, List<File> frames, String brackets) {
        if (frames.isEmpty()) return;
        String directive = looksStatic(frames) ? "img" : "anim";
        sb.append(directive).append(brackets).append(" = ");
        for (int i = 0; i < frames.size(); i++) {
            if (i > 0) sb.append('|');
            sb.append(frames.get(i).getName());
        }
        sb.append('\n');
    }

    /**
     * A slot with more than one file is always an already-cut animation
     * ({@code anim[...]=f1|f2|...}). A slot with exactly one file could
     * still be a whole multi-frame sheet on one transparent canvas though —
     * see {@link SheetSlicer} — in which case it's written the same way
     * ({@code anim[...]=onefile.png}, a single entry), letting the real
     * engine loader slice it at load time. Only a genuinely single, single-
     * pose file is written as {@code img[...]} (never sliced).
     */
    private static boolean looksStatic(List<File> frames) {
        return frames.size() == 1 && !SheetSlicer.looksLikeMultiFrameSheet(frames.get(0));
    }

    private static void writeParamLines(StringBuilder sb, ClipParams p, String brackets) {
        if (p.durationMs != null)    sb.append("duration").append(brackets).append(" = ").append(p.durationMs).append('\n');
        if (p.ticksPerFrame != null) sb.append("speed").append(brackets).append("    = ").append(p.ticksPerFrame).append('\n');
        if (p.loop != null)          sb.append("loop").append(brackets).append("     = ").append(p.loop).append('\n');
        if (p.reverse)                sb.append("order").append(brackets).append("    = reverse\n");
        if (p.scale != null)         sb.append("scale").append(brackets).append("    = ").append(num(p.scale)).append('\n');
    }

    private static String num(double d) {
        if (d == Math.rint(d) && !Double.isInfinite(d)) return String.format(Locale.ROOT, "%.1f", d);
        return String.valueOf(d);
    }

    private static String bracketSafe(String s) {
        return s == null ? "" : s.replace("[", "(").replace("]", ")");
    }

    private static String tokenOrName(String name) {
        return ObjectRepository.sanitizeToken(name);
    }

    // =========================================================================
    // Parsing
    // =========================================================================

    private static final Pattern FLOOR_IMG  = Pattern.compile("^img\\[([^\\]]*)]\\[([^\\]]*)]\\s*=\\s*(.+)$");
    private static final Pattern FLOOR_PROP = Pattern.compile("^(walkable|speedMultiplier|damagePerSecond)\\[([^\\]]*)]\\s*=\\s*(.+)$");

    public static void parseFloor(EditableObject obj, List<String> lines) {
        for (String raw : lines) {
            String line = stripLine(raw);
            if (line == null) continue;

            Matcher img = FLOOR_IMG.matcher(line);
            if (img.matches()) {
                String dir = img.group(2).trim().toUpperCase(Locale.ROOT);
                obj.floorImages.put(dir, new File(obj.folder, img.group(3).trim()));
                continue;
            }
            Matcher prop = FLOOR_PROP.matcher(line);
            if (prop.matches()) {
                String value = prop.group(3).trim();
                switch (prop.group(1)) {
                    case "walkable" -> obj.walkable = Boolean.parseBoolean(value);
                    case "speedMultiplier" -> obj.speedMultiplier = Double.parseDouble(value);
                    case "damagePerSecond" -> obj.damagePerSecond = Double.parseDouble(value);
                }
            }
        }
    }

    private static final Pattern UNIT_ENTRY = Pattern.compile(
            "^(anim|img|fps|speed|duration|loop|order|scale)\\[([^\\]]*)]\\[([^\\]]*)]\\[([^\\]]*)]\\[([^\\]]*)]\\s*=\\s*(.+)$");
    private static final Pattern UNIT_STAT = Pattern.compile(
            "^(hitChance|dodgeChance|blockChance|attackInterruptChance)\\[([^\\]]*)]\\[([^\\]]*)]\\[([^\\]]*)]\\[([^\\]]*)]\\s*=\\s*(.+)$");
    /** The whole-unit (always {@code [*][*][*][*]}) fit/footprint directives — see {@link org.toltec.unit.UnitAnimationConfig}'s javadoc. */
    private static final Pattern UNIT_FIT = Pattern.compile(
            "^(fitToCell|sizeCells)\\[([^\\]]*)]\\[([^\\]]*)]\\[([^\\]]*)]\\[([^\\]]*)]\\s*=\\s*(.+)$");

    public static void parseUnit(EditableObject obj, List<String> lines) {
        for (String raw : lines) {
            String line = stripLine(raw);
            if (line == null) continue;

            Matcher stat = UNIT_STAT.matcher(line);
            if (stat.matches()) {
                double v = Double.parseDouble(stat.group(6).trim());
                switch (stat.group(1)) {
                    case "hitChance" -> obj.hitChance = v;
                    case "dodgeChance" -> obj.dodgeChance = v;
                    case "blockChance" -> obj.blockChance = v;
                    case "attackInterruptChance" -> obj.attackInterruptChance = v;
                }
                continue;
            }

            // Whole-unit fitToCell/sizeCells are written with all four brackets wildcarded
            // (see #writeUnit) — matched here, BEFORE UNIT_ENTRY, since UNIT_ENTRY's own
            // "scale" case skips any line with a wildcarded gender/weapon/status (it can't
            // map a global override onto one specific combo's ClipParams).
            Matcher fit = UNIT_FIT.matcher(line);
            if (fit.matches()) {
                String value = fit.group(6).trim();
                switch (fit.group(1)) {
                    case "fitToCell" -> obj.fitToCell = Boolean.parseBoolean(value);
                    case "sizeCells" -> obj.sizeCells = Integer.parseInt(value);
                }
                continue;
            }
            if (line.matches("^scale\\[\\*]\\[\\*]\\[\\*]\\[\\*]\\s*=\\s*.+$")) {
                obj.fitScale = Double.parseDouble(line.substring(line.indexOf('=') + 1).trim());
                continue;
            }

            Matcher m = UNIT_ENTRY.matcher(line);
            if (!m.matches()) continue;
            String directive = m.group(1);
            String gRaw = m.group(2), wRaw = m.group(3), stRaw = m.group(4), aRaw = m.group(5);
            String value = m.group(6).trim();
            if (gRaw.equals("*") || wRaw.equals("*") || stRaw.equals("*")) continue; // can't map onto one combo — skip

            Gender gender = genderFromKey(gRaw);
            UnitStatus status = statusFromKey(stRaw);
            if (gender == null || status == null) continue;

            ClipGroup group = obj.unitClips.computeIfAbsent(new UnitClipKey(gender, wRaw.trim(), status), k -> new ClipGroup());
            applyEntry(group, directive, aRaw, value, obj.folder);
        }
    }

    private static final Pattern OBJ_ENTRY = Pattern.compile(
            "^(anim|img|fps|speed|duration|loop|order|scale)\\[([^\\]]*)]\\[([^\\]]*)]\\s*=\\s*(.+)$");
    private static final Pattern OBJ_FLAT = Pattern.compile(
            "^(collision|layer|isometric|drawWidth|drawHeight|fitToCell|fitScale|xOffset|yOffset|sizeCols|sizeRows)\\s*=\\s*(.+)$");

    public static void parseObject(EditableObject obj, List<String> lines) {
        for (String raw : lines) {
            String line = stripLine(raw);
            if (line == null) continue;

            Matcher flat = OBJ_FLAT.matcher(line);
            if (flat.matches()) {
                String value = flat.group(2).trim();
                switch (flat.group(1)) {
                    case "collision" -> obj.objCollision = Boolean.parseBoolean(value);
                    case "layer" -> obj.objLayer = Integer.parseInt(value);
                    case "isometric" -> obj.isometric = Boolean.parseBoolean(value);
                    case "drawWidth" -> obj.drawWidth = Integer.parseInt(value);
                    case "drawHeight" -> obj.drawHeight = Integer.parseInt(value);
                    case "fitToCell" -> obj.fitToCell = Boolean.parseBoolean(value);
                    case "fitScale" -> obj.fitScale = Double.parseDouble(value);
                    case "xOffset" -> obj.xOffset = Integer.parseInt(value);
                    case "yOffset" -> obj.yOffset = Integer.parseInt(value);
                    case "sizeCols" -> obj.sizeCols = Integer.parseInt(value);
                    case "sizeRows" -> obj.sizeRows = Integer.parseInt(value);
                }
                continue;
            }

            Matcher m = OBJ_ENTRY.matcher(line);
            if (!m.matches()) continue;
            String directive = m.group(1);
            String stRaw = m.group(2), aRaw = m.group(3);
            String value = m.group(4).trim();

            String state = EditableObject.normState(stRaw);
            ClipGroup group = obj.objectClips.computeIfAbsent(state, k -> new ClipGroup());

            if (aRaw.equals("*") && (directive.equals("anim") || directive.equals("img"))) {
                obj.omnidirectional = true;
                group.setFrames(Direction8.S, toFiles(splitFrames(value), obj.folder));
                continue;
            }
            applyEntry(group, directive, aRaw, value, obj.folder);
        }
    }

    private static void applyEntry(ClipGroup group, String directive, String aRaw, String value, File folder) {
        if (aRaw.equals("*")) {
            switch (directive) {
                case "duration" -> group.params.durationMs = Integer.parseInt(value);
                case "fps", "speed" -> group.params.ticksPerFrame = Integer.parseInt(value);
                case "loop" -> group.params.loop = Boolean.parseBoolean(value);
                case "order" -> group.params.reverse = value.equalsIgnoreCase("reverse");
                case "scale" -> group.params.scale = Double.parseDouble(value);
            }
            return;
        }
        if (directive.equals("anim") || directive.equals("img")) {
            Direction8 dir = directionFromKey(aRaw);
            if (dir != null) group.setFrames(dir, toFiles(splitFrames(value), folder));
        }
    }

    private static List<File> toFiles(List<String> names, File folder) {
        List<File> files = new ArrayList<>(names.size());
        for (String n : names) files.add(new File(folder, n));
        return files;
    }

    private static List<String> splitFrames(String value) {
        List<String> out = new ArrayList<>();
        for (String part : value.split("\\|")) {
            String p = part.trim();
            if (!p.isEmpty()) out.add(p);
        }
        return out;
    }

    private static Gender genderFromKey(String key) {
        for (Gender g : Gender.values()) if (g.key().equalsIgnoreCase(key.trim())) return g;
        return null;
    }

    private static UnitStatus statusFromKey(String key) {
        for (UnitStatus s : UnitStatus.values()) if (s.key().equalsIgnoreCase(key.trim())) return s;
        return null;
    }

    private static Direction8 directionFromKey(String key) {
        try {
            return Direction8.fromAngle(Integer.parseInt(key.trim()));
        } catch (NumberFormatException e) {
            for (Direction8 d : Direction8.values()) if (d.name().equalsIgnoreCase(key.trim())) return d;
            return null;
        }
    }

    /** @return the trimmed line, or {@code null} if it's blank/a comment/a basedir directive (nothing left to parse). */
    private static String stripLine(String raw) {
        String line = raw.trim();
        if (line.isEmpty() || line.startsWith("#") || line.startsWith(";") || line.startsWith("basedir=") || line.startsWith("bgcolor=")) {
            return null;
        }
        return line;
    }
}
