package org.toltec.demo;

import org.toltec.engine.EngineOptions;
import org.toltec.engine.GameCanvas;
import org.toltec.engine.GpuAcceleration;
import org.toltec.engine.MapCell;
import org.toltec.engine.TileGameEngine;
import org.toltec.mapeditor.io.CatalogLoader;
import org.toltec.mapeditor.io.MapFormat;
import org.toltec.mapeditor.model.MapDocument;
import org.toltec.mapeditor.model.PaletteEntry;
import org.toltec.unit.Direction8;
import org.toltec.unit.Unit;

import javax.swing.JFrame;
import javax.swing.SwingUtilities;
import java.awt.BorderLayout;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * Loads and plays a {@code .tmap} file made with the map editor
 * ({@code org.toltec.mapeditor}) — proof the format round-trips into an
 * actual playable game, not just back into the editor. Pass a path to a
 * {@code .tmap} file as the first argument; with no argument, builds a
 * small sample map in memory instead (using whatever's in the legacy
 * {@code tiles.ini}/{@code units.ini}), so this runs out of the box even
 * before you've made anything in the editor.
 * <p>
 * If the loaded map has at least one unit, the first one found becomes
 * click-to-move controllable — left-click any walkable cell — exactly like
 * {@link UnitDemoGame}, just without combat/HUD.
 */
public class MapPlayDemo extends TileGameEngine {

    private final MapDocument document;
    private final Map<String, PaletteEntry> catalog;
    private Unit controlled;

    private MapPlayDemo(MapDocument doc) {
        super(MapFormat.optionsFor(doc));
        this.document = doc;

        // `assets` (inherited from TileGameEngine) is already usable here —
        // it's assigned as part of the super(...) call above, before this
        // constructor body runs.
        CatalogLoader loader = new CatalogLoader(this.assets);
        List<PaletteEntry> floors = loader.loadFloors();
        List<PaletteEntry> units = loader.loadUnits();
        List<PaletteEntry> objects = loader.loadObjects();
        this.catalog = MapFormat.indexByKey(floors, units, objects);
    }

    @Override
    protected void onStart() {
        MapFormat.applyToEngine(document, this, catalog);

        findFirstUnit();
        if (controlled != null) {
            setSelfUnit(controlled);
            setCellClickListener((col, row, button) -> controlled.moveTo(col, row));
        }
        setCenterToCell(options.mapWidthCells / 2, options.mapHeightCells / 2);
    }

    private void findFirstUnit() {
        for (int r = 0; r < options.mapHeightCells && controlled == null; r++) {
            for (int c = 0; c < options.mapWidthCells && controlled == null; c++) {
                MapCell cell = getCell(c, r);
                for (var o : cell.getObjects()) {
                    if (o instanceof Unit u) {
                        controlled = u;
                        break;
                    }
                }
            }
        }
    }

    @Override
    protected void tick() {
        // Nothing extra needed — Unit's own tick() (via MapCell.tick()) drives
        // both idle animation and any in-progress click-to-move path-following.
    }

    // =========================================================================
    // Sample map (used when no .tmap path is given)
    // =========================================================================

    private static MapDocument buildSampleMap() {
        MapDocument doc = new MapDocument(12, 12, 64, 32, EngineOptions.ViewType.ISOMETRIC);
        for (int r = 0; r < 12; r++) {
            for (int c = 0; c < 12; c++) {
                doc.floor[r][c] = (c + r) % 5 == 0 ? "water" : "stone";
            }
        }
        // Keys matching CatalogLoader's legacy-source naming — see its javadoc.
        doc.units.add(new MapDocument.Placement(2, 2, "legacy/woman/sword", Direction8.S));
        doc.units.add(new MapDocument.Placement(9, 9, "legacy/goblin/unarmed", Direction8.W));
        return doc;
    }

    // =========================================================================
    // Entry point
    // =========================================================================

    public static void main(String[] args) throws IOException {
        GpuAcceleration.enable();

        MapDocument doc = args.length > 0 ? MapFormat.load(Path.of(args[0])) : buildSampleMap();
        String title = args.length > 0 ? args[0] : "встроенный пример";

        SwingUtilities.invokeLater(() -> {
            MapPlayDemo engine = new MapPlayDemo(doc);
            GameCanvas canvas = new GameCanvas(engine);

            JFrame frame = new JFrame("TileGameEngine – карта: " + title);
            frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
            frame.setLayout(new BorderLayout());
            frame.add(canvas, BorderLayout.CENTER);
            frame.setSize(1024, 640);
            frame.setLocationRelativeTo(null);
            frame.setVisible(true);

            engine.start(canvas);
        });
    }
}
