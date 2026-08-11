package org.toltec.editor.preview;

import org.toltec.engine.EngineOptions;
import org.toltec.render.GraphicObject;
import org.toltec.engine.MapCell;
import org.toltec.engine.TileGameEngine;
import org.toltec.render.TileTextureConfig;
import org.toltec.unit.Unit;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.util.List;

/**
 * The engine backing the editor's live preview cell: a fixed 5x5 map,
 * sitting on a plain generated ground (so units/objects have somewhere to
 * stand), with whatever is currently being edited placed in the middle.
 * <p>
 * All the mutation methods below ({@link #showUnit}, {@link #showObject},
 * {@link #showFloorTiling}) are safe to call from any thread — they only
 * ever touch {@link MapCell}, which does its own internal locking — so the
 * (slow, disk-touching) work of reloading a clip from the temp preview ini
 * can happen on a background thread without ever blocking the Swing EDT
 * that's busy repainting this same engine 30-60 times a second.
 */
public class PreviewEngine extends TileGameEngine {

    public static final int MAP_SIZE = 5;
    public static final int CENTER = MAP_SIZE / 2;

    public PreviewEngine(EngineOptions options) {
        super(options);
    }

    /**
     * The base engine's right-mouse-drag pans the camera — fine for a full
     * game view, but the editor's preview cell is meant to only ever zoom
     * (see {@link org.toltec.editor.ui.PreviewPane}), never scroll off its
     * subject. No-oping these two keeps the base class's internal
     * "is the right button down" flag permanently false, so the inherited
     * {@code mouseDragged} never starts a pan — simplest way to disable it
     * without duplicating any of the base class's drag-tracking state.
     */
    @Override
    public void mouseRightDown(int x, int y) { /* panning disabled in the preview */ }

    @Override
    public void mouseRightUp(int x, int y) { /* panning disabled in the preview */ }

    @Override
    protected void tick() {
        // Nothing extra — MapCell.tick() (called automatically for every
        // cell every logic tick) already advances each GraphicObject/Unit's
        // own animation, which is all the preview needs.
    }

    @Override
    protected void onStart() {
        setCenterToCell(CENTER, CENTER);
        buildNeutralGround();
    }

    // =========================================================================
    // Neutral ground (for UNIT / OBJECT previews — FLOOR previews replace it)
    // =========================================================================

    private void buildNeutralGround() {
        String a = "editor_ground_a", b = "editor_ground_b";
        if (!assets.has(a)) assets.put(a, groundTile(new Color(83, 122, 83)));
        if (!assets.has(b)) assets.put(b, groundTile(new Color(72, 108, 72)));

        for (int r = 0; r < MAP_SIZE; r++) {
            for (int c = 0; c < MAP_SIZE; c++) {
                GraphicObject g = new GraphicObject((r + c) % 2 == 0 ? a : b, -1000);
                g.isFloor = true;
                g.drawWidth = options.cellWidth;
                g.drawHeight = options.cellHeight;
                getCell(c, r).addObject(g);
            }
        }
    }

    private BufferedImage groundTile(Color fill) {
        int w = options.cellWidth, h = options.cellHeight;
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            int[] xs = {w / 2, w, w / 2, 0};
            int[] ys = {0, h / 2, h, h / 2};
            g.setColor(fill);
            g.fillPolygon(xs, ys, 4);
            g.setColor(new Color(0, 0, 0, 60));
            g.drawPolygon(xs, ys, 4);
        } finally {
            g.dispose();
        }
        return img;
    }

    // =========================================================================
    // Swapping in whatever's being edited
    // =========================================================================

    /** Replaces whatever unit currently occupies the center cell with {@code unit}. */
    public void showUnit(Unit unit) {
        MapCell cell = getCell(CENTER, CENTER);
        clearNonFloor(cell);
        unit.placeOn(this, CENTER, CENTER);
        setSelfUnit(unit);
        setSelectedUnit(null);
    }

    /** Replaces whatever prop currently occupies the center cell with {@code obj}. */
    public void showObject(GraphicObject obj) {
        MapCell cell = getCell(CENTER, CENTER);
        clearNonFloor(cell);
        obj.layer = Math.max(obj.layer, 1);
        cell.addObject(obj);
        setSelfUnit(null);
        setSelectedUnit(null);
    }

    /** Clears whatever's in the center cell (used while a fresh preview object is still loading). */
    public void clearCenter() {
        clearNonFloor(getCell(CENTER, CENTER));
        setSelfUnit(null);
        setSelectedUnit(null);
    }

    private void clearNonFloor(MapCell cell) {
        for (GraphicObject o : cell.getObjects()) {
            if (!o.isFloor) cell.removeObject(o);
        }
    }

    /** Retiles the whole 5x5 ground with {@code type} from {@code cfg} — used for the FLOOR category. */
    public void showFloorTiling(TileTextureConfig cfg, String type) {
        for (int r = 0; r < MAP_SIZE; r++) {
            for (int c = 0; c < MAP_SIZE; c++) {
                MapCell cell = getCell(c, r);
                List<GraphicObject> existing = cell.getObjects();
                for (GraphicObject o : existing) if (o.isFloor) cell.removeObject(o);

                GraphicObject floor = cfg.createFloorObject(type, c, r);
                floor.drawWidth = options.cellWidth;
                floor.drawHeight = options.cellHeight;
                floor.layer = -1000;
                cell.addObject(floor);
            }
        }
    }

    /** Restores the plain neutral ground — used when switching away from the FLOOR category. */
    public void restoreNeutralGround() {
        for (int r = 0; r < MAP_SIZE; r++) {
            for (int c = 0; c < MAP_SIZE; c++) {
                MapCell cell = getCell(c, r);
                for (GraphicObject o : cell.getObjects()) if (o.isFloor) cell.removeObject(o);
            }
        }
        buildNeutralGround();
    }
}
