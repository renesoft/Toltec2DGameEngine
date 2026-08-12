package org.toltec.mapeditor.io;

import org.toltec.editor.preview.AnimatedPreviewObject;
import org.toltec.engine.EngineOptions;
import org.toltec.engine.MapCell;
import org.toltec.engine.TileGameEngine;
import org.toltec.mapeditor.model.MapDocument;
import org.toltec.mapeditor.model.PaletteEntry;
import org.toltec.render.AnimationClip;
import org.toltec.render.GraphicObject;
import org.toltec.unit.Direction8;
import org.toltec.unit.Unit;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Reads/writes a {@link MapDocument} as a small, human-readable text format
 * ({@code .tmap}), and applies one onto a live {@link TileGameEngine} given
 * a catalog of {@link PaletteEntry} keyed by {@link PaletteEntry#key}.
 * <p>
 * {@link #applyToEngine} is the one piece of this class any other game
 * needs to actually load a map made in the editor — build the same catalog
 * with {@link CatalogLoader} against your own engine's {@code assets}, index
 * it with {@link #indexByKey}, then call {@code applyToEngine(doc, engine,
 * catalog)} on a freshly-constructed (empty) engine whose
 * {@code EngineOptions} match the document's {@link MapDocument#widthCells}/
 * {@link MapDocument#heightCells}/{@link MapDocument#cellWidth}/
 * {@link MapDocument#cellHeight}/{@link MapDocument#viewType} — see
 * {@link #optionsFor(MapDocument)}.
 */
public final class MapFormat {

    private MapFormat() {}

    // =========================================================================
    // Save / load (filesystem)
    // =========================================================================

    public static void save(MapDocument doc, Path file) throws IOException {
        Files.writeString(file, write(doc), StandardCharsets.UTF_8);
    }

    public static MapDocument load(Path file) throws IOException {
        return read(Files.readString(file, StandardCharsets.UTF_8));
    }

    // =========================================================================
    // Text (de)serialisation
    // =========================================================================

    public static String write(MapDocument doc) {
        StringBuilder sb = new StringBuilder();
        sb.append("# Toltec map file v1\n");
        sb.append("width=").append(doc.widthCells).append('\n');
        sb.append("height=").append(doc.heightCells).append('\n');
        sb.append("cellWidth=").append(doc.cellWidth).append('\n');
        sb.append("cellHeight=").append(doc.cellHeight).append('\n');
        sb.append("view=").append(doc.viewType.name()).append('\n');

        sb.append('\n').append("[FLOOR]\n");
        for (int r = 0; r < doc.heightCells; r++) {
            sb.append(r).append(": ");
            for (int c = 0; c < doc.widthCells; c++) {
                if (c > 0) sb.append(',');
                String key = doc.floor[r][c];
                sb.append(key == null ? "-" : escape(key));
            }
            sb.append('\n');
        }

        sb.append('\n').append("[UNIT]\n");
        for (MapDocument.Placement p : doc.units) {
            sb.append(p.col).append(',').append(p.row).append(',')
                    .append(escape(p.key)).append(',').append(p.direction.name()).append('\n');
        }

        sb.append('\n').append("[OBJECT]\n");
        for (MapDocument.Placement p : doc.objects) {
            sb.append(p.col).append(',').append(p.row).append(',')
                    .append(escape(p.key)).append(',').append(p.direction.name()).append('\n');
        }
        return sb.toString();
    }

    public static MapDocument read(String text) {
        String[] lines = text.split("\n", -1);

        int width = 20, height = 20, cellW = 64, cellH = 32;
        EngineOptions.ViewType view = EngineOptions.ViewType.ISOMETRIC;

        int i = 0;
        // ── Header ───────────────────────────────────────────────────────────
        for (; i < lines.length; i++) {
            String line = lines[i].trim();
            if (line.isEmpty() || line.startsWith("#")) continue;
            if (line.startsWith("[")) break;
            int eq = line.indexOf('=');
            if (eq < 0) continue;
            String k = line.substring(0, eq).trim();
            String v = line.substring(eq + 1).trim();
            switch (k) {
                case "width" -> width = Integer.parseInt(v);
                case "height" -> height = Integer.parseInt(v);
                case "cellWidth" -> cellW = Integer.parseInt(v);
                case "cellHeight" -> cellH = Integer.parseInt(v);
                case "view" -> view = EngineOptions.ViewType.valueOf(v);
            }
        }

        MapDocument doc = new MapDocument(width, height, cellW, cellH, view);

        // ── Sections ─────────────────────────────────────────────────────────
        String section = null;
        for (; i < lines.length; i++) {
            String raw = lines[i];
            String line = raw.trim();
            if (line.isEmpty() || line.startsWith("#")) continue;
            if (line.startsWith("[") && line.endsWith("]")) {
                section = line.substring(1, line.length() - 1).trim().toUpperCase(Locale.ROOT);
                continue;
            }
            if (section == null) continue;

            switch (section) {
                case "FLOOR" -> parseFloorLine(doc, line);
                case "UNIT" -> parsePlacementLine(doc.units, line);
                case "OBJECT" -> parsePlacementLine(doc.objects, line);
                default -> { /* unknown section — ignore, forward-compatible */ }
            }
        }
        return doc;
    }

    private static void parseFloorLine(MapDocument doc, String line) {
        int colon = line.indexOf(':');
        if (colon < 0) return;
        int row;
        try {
            row = Integer.parseInt(line.substring(0, colon).trim());
        } catch (NumberFormatException e) {
            return;
        }
        if (row < 0 || row >= doc.heightCells) return;
        String[] cells = line.substring(colon + 1).split(",", -1);
        for (int c = 0; c < cells.length && c < doc.widthCells; c++) {
            String v = cells[c].trim();
            doc.floor[row][c] = v.equals("-") || v.isEmpty() ? null : unescape(v);
        }
    }

    private static void parsePlacementLine(List<MapDocument.Placement> out, String line) {
        String[] parts = line.split(",", 4);
        if (parts.length < 4) return;
        try {
            int col = Integer.parseInt(parts[0].trim());
            int row = Integer.parseInt(parts[1].trim());
            String key = unescape(parts[2].trim());
            Direction8 dir = Direction8.valueOf(parts[3].trim());
            out.add(new MapDocument.Placement(col, row, key, dir));
        } catch (RuntimeException e) {
            System.err.println("MapFormat: пропущена некорректная строка размещения: " + line);
        }
    }

    private static String escape(String s) {
        return s.replace("\\", "\\b").replace(",", "\\c").replace("\n", "\\n");
    }

    private static String unescape(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (ch == '\\' && i + 1 < s.length()) {
                char next = s.charAt(++i);
                sb.append(switch (next) {
                    case 'c' -> ',';
                    case 'n' -> '\n';
                    case 'b' -> '\\';
                    default -> next;
                });
            } else {
                sb.append(ch);
            }
        }
        return sb.toString();
    }

    // =========================================================================
    // Applying to a live engine
    // =========================================================================

    /** {@code EngineOptions} matching the document's geometry — construct your engine with this. */
    public static EngineOptions optionsFor(MapDocument doc) {
        EngineOptions opts = new EngineOptions();
        opts.mapWidthCells = doc.widthCells;
        opts.mapHeightCells = doc.heightCells;
        opts.cellWidth = doc.cellWidth;
        opts.cellHeight = doc.cellHeight;
        opts.viewType = doc.viewType;
        return opts;
    }

    /** Combines separately-loaded floor/unit/object catalogs into one key-indexed map, for {@link #applyToEngine}. */
    @SafeVarargs
    public static Map<String, PaletteEntry> indexByKey(List<PaletteEntry>... lists) {
        Map<String, PaletteEntry> out = new java.util.HashMap<>();
        for (List<PaletteEntry> list : lists)
            for (PaletteEntry e : list)
                out.put(e.key, e);
        return out;
    }

    /**
     * Populates {@code engine}'s map from {@code doc}, resolving each floor
     * cell / unit placement / object placement through {@code catalog}
     * (see {@link #indexByKey}). Meant to run once against a freshly built,
     * empty engine (matching {@code doc}'s own size — see
     * {@link #optionsFor}); entries in {@code doc} whose key isn't in
     * {@code catalog} (e.g. an asset that was renamed/deleted since the map
     * was saved) are silently skipped rather than failing the whole load.
     */
    public static void applyToEngine(MapDocument doc, TileGameEngine engine, Map<String, PaletteEntry> catalog) {
        for (int r = 0; r < doc.heightCells; r++) {
            for (int c = 0; c < doc.widthCells; c++) {
                MapCell cell = engine.getCell(c, r);
                if (cell == null) continue;
                String key = doc.floor[r][c];
                if (key == null) continue;
                PaletteEntry pe = catalog.get(key);
                if (pe == null || pe.floorConfig == null) continue;
                GraphicObject floor = pe.floorConfig.createFloorObject(pe.floorType, c, r);
                floor.drawWidth = engine.options.cellWidth;
                floor.drawHeight = engine.options.cellHeight;
                floor.layer = -1000;
                cell.addObject(floor);
            }
        }

        for (MapDocument.Placement p : doc.objects) {
            PaletteEntry pe = catalog.get(p.key);
            if (pe == null || pe.objectConfig == null) continue;
            MapCell cell = engine.getCell(p.col, p.row);
            if (cell == null) continue;
            AnimationClip clip = pe.objectConfig.resolve(pe.objectState, p.direction);
            GraphicObject obj = clip != null ? new AnimatedPreviewObject(clip) : new GraphicObject("");
            pe.objectConfig.applyTo(obj);
            obj.layer = Math.max(obj.layer, 1);
            cell.addObject(obj);
        }

        for (MapDocument.Placement p : doc.units) {
            PaletteEntry pe = catalog.get(p.key);
            if (pe == null || pe.unitConfig == null) continue;
            if (!engine.isCellValid(p.col, p.row)) continue;
            Unit unit = new Unit(pe.unitConfig, pe.gender, pe.weapon);
            unit.setDirection(p.direction);
            unit.setIsometricType(engine.options.viewType == EngineOptions.ViewType.ISOMETRIC);
            unit.placeOn(engine, p.col, p.row);
        }
    }

    /** Convenience: build a fresh {@link ArrayList} the way most callers will want it — see {@link #indexByKey}. */
    public static List<PaletteEntry> concat(List<PaletteEntry> a, List<PaletteEntry> b, List<PaletteEntry> c) {
        List<PaletteEntry> out = new ArrayList<>(a.size() + b.size() + c.size());
        out.addAll(a);
        out.addAll(b);
        out.addAll(c);
        return out;
    }
}
