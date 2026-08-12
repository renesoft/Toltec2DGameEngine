package org.toltec.mapeditor.engine;

import org.toltec.engine.EngineOptions;
import org.toltec.engine.MapCell;
import org.toltec.engine.TileGameEngine;
import org.toltec.mapeditor.model.EraseMode;
import org.toltec.mapeditor.model.MapDocument;
import org.toltec.mapeditor.model.PaletteEntry;
import org.toltec.render.GraphicObject;
import org.toltec.unit.Direction8;
import org.toltec.unit.Unit;

import java.awt.Color;
import java.awt.Graphics2D;

/**
 * Live engine backing the map editor's canvas. Doesn't know anything about
 * brush size, multi-select, or per-cell placement probability — that's the
 * controller's job (see {@code MapEditorController}) — it only knows how to:
 * <ul>
 *   <li>report left-click/left-drag gestures as a "paint stroke"
 *       ({@link #setPaintListener}), one cell at a time, deduplicated so a
 *       slow drag across the same cell doesn't fire repeatedly;</li>
 *   <li>apply one concrete floor/unit/object/erase change to one cell,
 *       keeping the live {@link MapCell}s and the canonical
 *       {@link #getDocument()} in sync in the same call.</li>
 * </ul>
 * Always constructed fresh for a given map size (see {@code EngineOptions}
 * being fixed at {@code TileGameEngine} construction) — "New map" / "Load
 * map" in the controller means building a new {@code MapEditorEngine} +
 * {@code GameCanvas}, not resizing this one in place.
 */
public class MapEditorEngine extends TileGameEngine {

    /** One left-button paint gesture, cell by cell — see {@link #setPaintListener}. */
    public interface PaintListener {
        /** Mouse went down on a valid cell — start of a new stroke. */
        void onStrokeStart(int col, int row);
        /** Mouse dragged into a <em>different</em> valid cell than the last report, same stroke. */
        void onStrokeDrag(int col, int row);
        /** Mouse released — end of the stroke. */
        void onStrokeEnd();
    }

    private final MapDocument document;
    private PaintListener paintListener;

    private volatile boolean leftDown = false;
    private int lastPaintCol = Integer.MIN_VALUE, lastPaintRow = Integer.MIN_VALUE;

    /** Whether the light per-cell grid overlay draws — on by default, useful while editing an empty map. */
    public volatile boolean showGrid = true;

    /**
     * The object currently shown as a translucent "ghost" following the
     * cursor — set by {@link #setPreviewFloor}/{@link #setPreviewObject}/
     * {@link #setPreviewUnit}, cleared by {@link #clearPreview}. Never added
     * to a {@link MapCell} or the document; it only exists for {@link #onDraw}
     * to render each frame at whatever cell the mouse is currently over.
     */
    private volatile GraphicObject previewObject;

    /** Opacity the ghost preview draws at — tweak to taste. */
    public volatile float previewAlpha = 0.55f;

    public MapEditorEngine(EngineOptions options) {
        super(options);
        this.document = new MapDocument(options.mapWidthCells, options.mapHeightCells,
                options.cellWidth, options.cellHeight, options.viewType);
    }

    public void setPaintListener(PaintListener l) { this.paintListener = l; }

    public MapDocument getDocument() { return document; }

    @Override
    protected void onStart() {
        setCenterToCell(options.mapWidthCells / 2, options.mapHeightCells / 2);
    }

    @Override
    protected void tick() {
        // Nothing extra — MapCell.tick() (called automatically every logic
        // tick) already advances each placed object/unit's own idle animation.
    }

    // =========================================================================
    // Cursor-follow preview ("ghost") — shows what a click will place, before
    // it's actually placed. Committing still happens the existing way: the
    // controller listens for onStrokeStart/onStrokeDrag and calls
    // setFloor/placeObject/placeUnit as before. This just draws a preview.
    // =========================================================================

    /** Shows a translucent preview of {@code entry}'s floor, following the cursor. */
    public void setPreviewFloor(PaletteEntry entry) {
        if (entry == null || entry.floorConfig == null) { clearPreview(); return; }
        GraphicObject ghost = entry.floorConfig.createFloorObject(entry.floorType, -1, -1);
        ghost.drawWidth = options.cellWidth;
        ghost.drawHeight = options.cellHeight;
        ghost.tick(); // primes imageName/animation frame — never added to a MapCell, so never ticks otherwise
        previewObject = ghost;
    }

    /** Shows a translucent preview of {@code entry} as an object facing {@code direction}, following the cursor. */
    public void setPreviewObject(PaletteEntry entry, Direction8 direction) {
        if (entry == null || entry.objectConfig == null) { clearPreview(); return; }
        var clip = entry.objectConfig.resolve(entry.objectState, direction);
        GraphicObject ghost = clip != null
                ? new org.toltec.editor.preview.AnimatedPreviewObject(clip)
                : new GraphicObject("");
        entry.objectConfig.applyTo(ghost);
        ghost.tick(); // primes imageName/animation frame — never added to a MapCell, so never ticks otherwise
        previewObject = ghost;
    }

    /** Shows a translucent preview of {@code entry} as a unit facing {@code direction}, following the cursor. */
    public void setPreviewUnit(PaletteEntry entry, Direction8 direction) {
        if (entry == null || entry.unitConfig == null) { clearPreview(); return; }
        Unit ghost = new Unit(entry.unitConfig, entry.gender, entry.weapon);
        ghost.setDirection(direction);
        ghost.setIsometricType(options.viewType == EngineOptions.ViewType.ISOMETRIC);
        ghost.tick(); // primes imageName/animation frame — never added to a MapCell, so never ticks otherwise
        previewObject = ghost;
    }

    /** Hides the cursor-follow preview — call when the palette selection is cleared, or an erase tool is active. */
    public void clearPreview() { previewObject = null; }

    // =========================================================================
    // Paint-stroke mouse handling
    // =========================================================================

    @Override
    protected void onMouseLeftDown(int x, int y) {
        leftDown = true;
        lastPaintCol = lastPaintRow = Integer.MIN_VALUE;
        int[] cell = screenToCell(x, y);
        if (cell[0] < 0) return;
        lastPaintCol = cell[0];
        lastPaintRow = cell[1];
        if (paintListener != null) paintListener.onStrokeStart(cell[0], cell[1]);
    }

    @Override
    protected void onMouseLeftUp(int x, int y) {
        leftDown = false;
        if (paintListener != null) paintListener.onStrokeEnd();
    }

    @Override
    public void mouseDragged(int x, int y) {
        super.mouseDragged(x, y); // preserves right-drag camera pan
        if (!leftDown) return;
        int[] cell = screenToCell(x, y);
        if (cell[0] < 0) return;
        if (cell[0] == lastPaintCol && cell[1] == lastPaintRow) return; // still inside the same cell
        lastPaintCol = cell[0];
        lastPaintRow = cell[1];
        if (paintListener != null) paintListener.onStrokeDrag(cell[0], cell[1]);
    }

    // =========================================================================
    // Low-level cell edits — keep live rendering and the document in sync
    // =========================================================================

    /** Paints {@code entry}'s floor type at (col,row), replacing whatever floor was there. */
    public void setFloor(int col, int row, PaletteEntry entry) {
        MapCell cell = getCell(col, row);
        if (cell == null || entry == null || entry.floorConfig == null) return;
        GraphicObject old = cell.getFloorObject();
        if (old != null) cell.removeObject(old);

        GraphicObject floor = entry.floorConfig.createFloorObject(entry.floorType, col, row);
        floor.drawWidth = options.cellWidth;
        floor.drawHeight = options.cellHeight;
        floor.layer = -1000;
        cell.addObject(floor);

        document.floor[row][col] = entry.key;
    }

    /** Places {@code entry} as a unit at (col,row) facing {@code direction}, replacing any unit already there. */
    public void placeUnit(int col, int row, PaletteEntry entry, Direction8 direction) {
        MapCell cell = getCell(col, row);
        if (cell == null || entry == null || entry.unitConfig == null) return;
        removeUnitsAt(col, row);

        Unit unit = new Unit(entry.unitConfig, entry.gender, entry.weapon);
        unit.setDirection(direction);
        unit.setIsometricType(options.viewType == EngineOptions.ViewType.ISOMETRIC);
        unit.placeOn(this, col, row);

        document.units.add(new MapDocument.Placement(col, row, entry.key, direction));
    }

    /** Adds {@code entry} as an object at (col,row) facing {@code direction} — objects stack, unlike units. */
    public void placeObject(int col, int row, PaletteEntry entry, Direction8 direction) {
        MapCell cell = getCell(col, row);
        if (cell == null || entry == null || entry.objectConfig == null) return;

        var clip = entry.objectConfig.resolve(entry.objectState, direction);
        GraphicObject obj = clip != null
                ? new org.toltec.editor.preview.AnimatedPreviewObject(clip)
                : new GraphicObject("");
        entry.objectConfig.applyTo(obj);
        obj.layer = Math.max(obj.layer, 1);
        cell.addObject(obj);

        document.objects.add(new MapDocument.Placement(col, row, entry.key, direction));
    }

    /** Erases from (col,row) according to {@code mode} — see {@link EraseMode}. */
    public void eraseCell(int col, int row, EraseMode mode) {
        MapCell cell = getCell(col, row);
        if (cell == null) return;
        switch (mode) {
            case UNITS -> removeUnitsAt(col, row);
            case OBJECTS -> removeObjectsAt(col, row);
            case UNITS_AND_OBJECTS -> {
                removeUnitsAt(col, row);
                removeObjectsAt(col, row);
            }
            case ALL -> {
                removeUnitsAt(col, row);
                removeObjectsAt(col, row);
                removeFloorAt(col, row);
            }
        }
    }

    private void removeUnitsAt(int col, int row) {
        MapCell cell = getCell(col, row);
        if (cell != null)
            for (GraphicObject o : cell.getObjects())
                if (o instanceof Unit) cell.removeObject(o);
        document.clearUnitsAt(col, row);
    }

    private void removeObjectsAt(int col, int row) {
        MapCell cell = getCell(col, row);
        if (cell != null)
            for (GraphicObject o : cell.getObjects())
                if (!o.isFloor && !(o instanceof Unit)) cell.removeObject(o);
        document.clearObjectsAt(col, row);
    }

    private void removeFloorAt(int col, int row) {
        MapCell cell = getCell(col, row);
        if (cell != null) {
            GraphicObject floor = cell.getFloorObject();
            if (floor != null) cell.removeObject(floor);
        }
        document.floor[row][col] = null;
    }

    /** Wipes every live cell (floor/units/objects) and the document alike — used before rebuilding from a loaded map. */
    public void clearAll() {
        for (int r = 0; r < options.mapHeightCells; r++) {
            for (int c = 0; c < options.mapWidthCells; c++) {
                getCell(c, r).clearObjects();
                document.floor[r][c] = null;
            }
        }
        document.units.clear();
        document.objects.clear();
    }

    /**
     * Repopulates this (assumed freshly-built and empty) engine from
     * {@code doc}, resolving each placement through {@code catalog} (see
     * {@link org.toltec.mapeditor.io.MapFormat#indexByKey}). Routes through
     * {@link #setFloor}/{@link #placeUnit}/{@link #placeObject} like any
     * other edit, so {@link #getDocument()} stays in sync with what's
     * actually rendered — unlike {@code MapFormat.applyToEngine}, which is
     * meant for engines with no document to keep in sync (e.g. a plain game
     * loading a map to play, not edit).
     */
    public void loadFromDocument(MapDocument doc, java.util.Map<String, PaletteEntry> catalog) {
        clearAll();
        for (int r = 0; r < doc.heightCells && r < options.mapHeightCells; r++) {
            for (int c = 0; c < doc.widthCells && c < options.mapWidthCells; c++) {
                String key = doc.floor[r][c];
                if (key == null) continue;
                PaletteEntry pe = catalog.get(key);
                if (pe != null) setFloor(c, r, pe);
            }
        }
        for (MapDocument.Placement p : doc.objects) {
            PaletteEntry pe = catalog.get(p.key);
            if (pe != null && isCellValid(p.col, p.row)) placeObject(p.col, p.row, pe, p.direction);
        }
        for (MapDocument.Placement p : doc.units) {
            PaletteEntry pe = catalog.get(p.key);
            if (pe != null && isCellValid(p.col, p.row)) placeUnit(p.col, p.row, pe, p.direction);
        }
    }

    // =========================================================================
    // Grid overlay — keeps empty cells visible while editing
    // =========================================================================

    @Override
    protected void onDraw(Graphics2D gfx) {
        double z = getZoom();
        int w = getCanvasW();
        int h = getCanvasH();

        if (showGrid) drawGridOverlay(gfx, z, w, h);

        // Cursor-follow ghost preview — drawn last so it sits on top of the grid.
        GraphicObject ghost = previewObject;
        if (ghost != null) {
            int[] hover = screenToCell(getMouseX(), getMouseY());
            System.out.println("[preview] mouse=" + getMouseX() + "," + getMouseY()
                    + " hoverCell=" + hover[0] + "," + hover[1]
                    + " image=" + ghost.imageName + " loaded=" + (assets.get(ghost.imageName) != null));
            if (hover[0] >= 0) renderPreview(gfx, ghost, hover[0], hover[1], previewAlpha);
        }
    }

    private void drawGridOverlay(Graphics2D gfx, double z, int w, int h) {
        // w/h are the engine's actual physical canvas size (see onDraw) —
        // deliberately not gfx.getClipBounds(): on a freshly created
        // Graphics2D (from buffer.createGraphics()) no clip is set, so
        // getClipBounds() returns null almost always.

        // screenToCell() rejects anything outside the map (returns {-1,-1}) —
        // fine for mouse-click purposes, but useless here for sizing the
        // visible range. So: use it only when all four corners land inside
        // the map (the common case, and the one where skipping most of a
        // large map's cells actually matters for perf); the moment any
        // corner is off-map (viewport extends past an edge, or the whole map
        // fits on screen at once), just fall back to the full grid — for any
        // map size worth hand-painting in an editor, drawing every cell's
        // outline once a frame is cheap regardless.
        int[] tl = screenToCell(0, 0), tr = screenToCell(w, 0);
        int[] bl = screenToCell(0, h), br = screenToCell(w, h);
        int colMin = 0, colMax = options.mapWidthCells - 1;
        int rowMin = 0, rowMax = options.mapHeightCells - 1;
        if (tl[0] >= 0 && tr[0] >= 0 && bl[0] >= 0 && br[0] >= 0) {
            colMin = Math.max(0, min4(tl[0], tr[0], bl[0], br[0]) - 1);
            colMax = Math.min(options.mapWidthCells - 1, max4(tl[0], tr[0], bl[0], br[0]) + 1);
            rowMin = Math.max(0, min4(tl[1], tr[1], bl[1], br[1]) - 1);
            rowMax = Math.min(options.mapHeightCells - 1, max4(tl[1], tr[1], bl[1], br[1]) + 1);
        }

        gfx.setColor(new Color(255, 255, 255, 40));
        boolean iso = options.viewType == EngineOptions.ViewType.ISOMETRIC;

        // Must match TileGameEngine.renderIsometric/renderTopDown's own
        // screenCw/screenCh exactly (round cellWidth/cellHeight * zoom
        // FIRST, then halve) — halving options.cellWidth/cellHeight*z
        // directly, as this used to, rounds at a different point and can
        // land the grid a pixel off from the real tile edges at some zoom
        // levels.
        int cw = (int) Math.round(options.cellWidth  * z);
        int ch = (int) Math.round(options.cellHeight * z);
        int halfW = cw / 2;
        int halfH = ch / 2;

        // cellToScreen(c,r) independently re-rounds ((col-row)*cellWidth/2 -
        // viewCenterX) * zoom for every single cell — exactly the per-cell
        // rounding TileGameEngine.renderTopDown's own comment says was
        // replaced because two neighbouring cells can each round to the
        // *nearest* pixel in opposite directions. That's fine for one-off
        // lookups (hit-testing, HUD anchors) but it means the grid drifted
        // away from the actually-rendered tile position the farther a cell
        // sits from the camera center — barely visible on a small map,
        // clearly visible on something like 300x300. Building every cell's
        // anchor from ONE shared origin (cellToScreen(0,0) — equivalent to
        // TileGameEngine's private mapPixelToScreen(0,0), since
        // cellToMapPixel(0,0) is (0,0) in both projections) plus a constant
        // integer step per column/row, exactly like renderIsometric/
        // renderTopDown do internally, keeps the grid pixel-identical to
        // the real tiles at any distance from center and any zoom.
        int[] origin = cellToScreen(0, 0);

        // Reused across every cell this frame — a fresh Path2D/array per cell
        // (400+ of them a frame on even a modest map) was needless per-frame
        // garbage for what's just 4 fixed points shifted to each anchor.
        int[] xs = new int[4];
        int[] ys = new int[4];

        for (int r = rowMin; r <= rowMax; r++) {
            for (int c = colMin; c <= colMax; c++) {
                int sx, sy;
                if (iso) {
                    sx = origin[0] + (c - r) * halfW;
                    sy = origin[1] + (c + r) * halfH;
                } else {
                    sx = origin[0] + c * cw;
                    sy = origin[1] + r * ch;
                }

                // (sx,sy) is TileGameEngine.renderCell's own anchor: the
                // diamond's TOP vertex in isometric (see renderCell's
                // placeholder polygon: {sx, sx+cw/2, sx, sx-cw/2} /
                // {sy, sy+ch/2, sy+ch, sy+ch/2}), or the rect's TOP-LEFT
                // corner in top-down (renderCell's gfx.fillRect(sx, sy, cw,
                // ch)) — so the outline below is built from that same point,
                // not centered on it.
                if (iso) {
                    xs[0] = sx;         ys[0] = sy;
                    xs[1] = sx + halfW; ys[1] = sy + halfH;
                    xs[2] = sx;         ys[2] = sy + ch;
                    xs[3] = sx - halfW; ys[3] = sy + halfH;
                } else {
                    xs[0] = sx;      ys[0] = sy;
                    xs[1] = sx + cw; ys[1] = sy;
                    xs[2] = sx + cw; ys[2] = sy + ch;
                    xs[3] = sx;      ys[3] = sy + ch;
                }
                gfx.drawPolygon(xs, ys, 4);
            }
        }
    }

    private static int min4(int a, int b, int c, int d) { return Math.min(Math.min(a, b), Math.min(c, d)); }
    private static int max4(int a, int b, int c, int d) { return Math.max(Math.max(a, b), Math.max(c, d)); }
}