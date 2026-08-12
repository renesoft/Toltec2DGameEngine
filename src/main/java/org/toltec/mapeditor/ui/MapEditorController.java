package org.toltec.mapeditor.ui;

import javafx.application.Platform;
import javafx.embed.swing.SwingNode;
import javafx.scene.layout.StackPane;
import javafx.stage.FileChooser;
import javafx.stage.Stage;
import org.toltec.editor.model.ObjectCategory;
import org.toltec.engine.EngineOptions;
import org.toltec.engine.GameCanvas;
import org.toltec.mapeditor.engine.MapEditorEngine;
import org.toltec.mapeditor.io.CatalogLoader;
import org.toltec.mapeditor.io.MapFormat;
import org.toltec.mapeditor.model.MapDocument;
import org.toltec.mapeditor.model.PaletteEntry;
import org.toltec.mapeditor.model.Tool;
import org.toltec.unit.Direction8;

import javax.swing.SwingUtilities;
import java.io.File;
import java.io.IOException;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * Wires {@link LeftPalettePanel} + {@link TopToolbar} to a live
 * {@link MapEditorEngine}, and owns the (re)creation of that engine —
 * "New map" / "Load map" both mean tearing down whatever engine/canvas is
 * currently mounted and building a fresh one sized to match, since a
 * {@code TileGameEngine}'s map dimensions are fixed at construction.
 */
public class MapEditorController {

    private final Stage stage;
    private final LeftPalettePanel palette;
    private final TopToolbar toolbar;
    private final StackPane canvasHost;

    private MapEditorEngine engine;
    private List<PaletteEntry> floors = List.of();
    private List<PaletteEntry> units = List.of();
    private List<PaletteEntry> objects = List.of();

    private Direction8 currentDirection = Direction8.S;
    private final Random rng = new Random();
    private final Set<Long> touchedThisStroke = new HashSet<>();

    public MapEditorController(Stage stage, LeftPalettePanel palette, TopToolbar toolbar, StackPane canvasHost) {
        this.stage = stage;
        this.palette = palette;
        this.toolbar = toolbar;
        this.canvasHost = canvasHost;

        toolbar.setOnNewMap(this::promptNewMap);
        toolbar.setOnSave(this::promptSave);
        toolbar.setOnLoad(this::promptLoad);
        toolbar.setOnRotate(this::rotateDirection);
        toolbar.setDirection(currentDirection);
        toolbar.toolProperty().addListener((obs, was, now) -> updateStatus());
        palette.setOnSelectionChanged(this::updateStatus);
        EngineOptions o = new EngineOptions();
        o.mapWidthCells    = 20;
        o.mapHeightCells   = 20;
        o.cellWidth        = 64;
        o.cellHeight       = 32;
        o.viewType         = EngineOptions.ViewType.ISOMETRIC;
        o.tickIntervalMs   = 10;
        o.renderIntervalMs = 10;
        buildEngine(o);
    }

    // =========================================================================
    // Engine (re)creation
    // =========================================================================

    private void buildEngine(EngineOptions opts) {
        if (engine != null) engine.stop();
        touchedThisStroke.clear();

        MapEditorEngine fresh = new MapEditorEngine(opts);

        CatalogLoader loader = new CatalogLoader(fresh.assets);
        floors = loader.loadFloors();
        units = loader.loadUnits();
        objects = loader.loadObjects();
        palette.setEntries(ObjectCategory.FLOOR, floors);
        palette.setEntries(ObjectCategory.UNIT, units);
        palette.setEntries(ObjectCategory.OBJECT, objects);

        fresh.setPaintListener(new MapEditorEngine.PaintListener() {
            @Override public void onStrokeStart(int col, int row) {
                Platform.runLater(() -> { touchedThisStroke.clear(); paintBrush(col, row); });
            }
            @Override public void onStrokeDrag(int col, int row) {
                Platform.runLater(() -> paintBrush(col, row));
            }
            @Override public void onStrokeEnd() {
                Platform.runLater(touchedThisStroke::clear);
            }
        });
        fresh.setKeyPressedListener(keyCode -> {
            if (keyCode == java.awt.event.KeyEvent.VK_R) Platform.runLater(this::rotateDirection);
        });

        this.engine = fresh;
        mountCanvas(fresh);
        updateStatus();
    }

    private void mountCanvas(MapEditorEngine eng) {
        SwingNode node = new SwingNode();
        canvasHost.getChildren().setAll(node);
        SwingUtilities.invokeLater(() -> {
            GameCanvas canvas = new GameCanvas(eng);
            node.setContent(canvas);
            eng.start(canvas);
        });
    }

    // =========================================================================
    // Painting
    // =========================================================================

    /** Expands the brush around (col,row), skipping cells already touched during this stroke. */
    private void paintBrush(int col, int row) {
        Tool tool = toolbar.toolProperty().get();
        int size = Math.max(1, toolbar.brushSizeProperty().get());
        int startCol = col - (size - 1) / 2;
        int startRow = row - (size - 1) / 2;

        for (int r = startRow; r < startRow + size; r++) {
            for (int c = startCol; c < startCol + size; c++) {
                if (!engine.isCellValid(c, r)) continue;
                long id = ((long) r << 24) | (c & 0xFFFFFFL);
                if (!touchedThisStroke.add(id)) continue;
                applyToolAt(tool, c, r);
            }
        }
    }

    private void applyToolAt(Tool tool, int col, int row) {
        switch (tool) {
            case FLOOR_BRUSH -> {
                Set<PaletteEntry> sel = palette.selectedFor(ObjectCategory.FLOOR);
                if (sel.isEmpty()) return;
                engine.setFloor(col, row, pickRandom(sel));
            }
            case UNIT_BRUSH -> {
                Set<PaletteEntry> sel = palette.selectedFor(ObjectCategory.UNIT);
                if (sel.isEmpty() || rng.nextDouble() >= toolbar.probabilityProperty().get()) return;
                engine.placeUnit(col, row, pickRandom(sel), currentDirection);
            }
            case OBJECT_BRUSH -> {
                Set<PaletteEntry> sel = palette.selectedFor(ObjectCategory.OBJECT);
                if (sel.isEmpty() || rng.nextDouble() >= toolbar.probabilityProperty().get()) return;
                engine.placeObject(col, row, pickRandom(sel), currentDirection);
            }
            case ERASER -> engine.eraseCell(col, row, toolbar.eraseModeProperty().get());
        }
    }

    private PaletteEntry pickRandom(Set<PaletteEntry> set) {
        int idx = rng.nextInt(set.size());
        int i = 0;
        for (PaletteEntry e : set) {
            if (i++ == idx) return e;
        }
        throw new IllegalStateException("unreachable");
    }

    private void rotateDirection() {
        currentDirection = Direction8.values()[(currentDirection.ordinal() + 1) % Direction8.values().length];
        toolbar.setDirection(currentDirection);
    }

    private void updateStatus() {
        Tool t = toolbar.toolProperty().get();
        String msg = switch (t) {
            case FLOOR_BRUSH -> palette.selectedFor(ObjectCategory.FLOOR).isEmpty()
                    ? "Выберите один или несколько типов пола слева" : "";
            case UNIT_BRUSH -> palette.selectedFor(ObjectCategory.UNIT).isEmpty()
                    ? "Выберите одного или нескольких юнитов слева" : "";
            case OBJECT_BRUSH -> palette.selectedFor(ObjectCategory.OBJECT).isEmpty()
                    ? "Выберите один или несколько объектов слева" : "";
            case ERASER -> "";
        };
        toolbar.setStatus(msg);
    }

    // =========================================================================
    // File actions
    // =========================================================================

    private void promptNewMap() {
        NewMapDialog.show().ifPresent(spec -> {
            EngineOptions opts = new EngineOptions();
            opts.mapWidthCells = spec.widthCells();
            opts.mapHeightCells = spec.heightCells();
            opts.cellWidth = spec.cellWidth();
            opts.cellHeight = spec.cellHeight();
            opts.viewType = spec.viewType();
            buildEngine(opts);
            toolbar.setStatus("Новая карта " + spec.widthCells() + "×" + spec.heightCells());
        });
    }

    private void promptSave() {
        FileChooser fc = new FileChooser();
        fc.setTitle("Сохранить карту");
        fc.getExtensionFilters().add(new FileChooser.ExtensionFilter("Toltec map (*.tmap)", "*.tmap"));
        fc.setInitialFileName("map.tmap");
        File f = fc.showSaveDialog(stage);
        if (f == null) return;
        try {
            MapFormat.save(engine.getDocument(), f.toPath());
            toolbar.setStatus("Сохранено: " + f.getName());
        } catch (IOException e) {
            toolbar.setStatus("Ошибка сохранения: " + e.getMessage());
        }
    }

    private void promptLoad() {
        FileChooser fc = new FileChooser();
        fc.setTitle("Загрузить карту");
        fc.getExtensionFilters().add(new FileChooser.ExtensionFilter("Toltec map (*.tmap)", "*.tmap"));
        File f = fc.showOpenDialog(stage);
        if (f == null) return;
        try {
            MapDocument doc = MapFormat.load(f.toPath());
            buildEngine(MapFormat.optionsFor(doc));
            Map<String, PaletteEntry> catalog = MapFormat.indexByKey(floors, units, objects);
            engine.loadFromDocument(doc, catalog);
            toolbar.setStatus("Загружено: " + f.getName());
        } catch (IOException e) {
            toolbar.setStatus("Ошибка загрузки: " + e.getMessage());
        }
    }
}
