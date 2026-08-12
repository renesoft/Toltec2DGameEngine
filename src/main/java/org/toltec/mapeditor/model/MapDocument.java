package org.toltec.mapeditor.model;

import org.toltec.engine.EngineOptions;
import org.toltec.unit.Direction8;

/**
 * In-memory representation of a level, independent of any running
 * {@code TileGameEngine} — this is what the map editor paints into and what
 * gets serialised to / parsed from a {@code .tmap} text file (see
 * {@link org.toltec.mapeditor.io.MapFormat}).
 * <p>
 * {@link #floor} holds a {@link PaletteEntry#key} per cell (or {@code null}
 * for "no floor painted yet"); {@link #units} / {@link #objects} are simple
 * placement lists — several placements can share a cell (e.g. a unit
 * standing next to a crate), unlike floor which is one-per-cell by
 * definition.
 */
public final class MapDocument {

    /** One concrete unit or object placed on the map. */
    public static final class Placement {
        public int col, row;
        public String key; // PaletteEntry#key
        public Direction8 direction = Direction8.S;

        public Placement(int col, int row, String key, Direction8 direction) {
            this.col = col;
            this.row = row;
            this.key = key;
            this.direction = direction;
        }
    }

    public int widthCells;
    public int heightCells;
    public int cellWidth;
    public int cellHeight;
    public EngineOptions.ViewType viewType;

    /** [row][col] -> floor PaletteEntry#key, or {@code null}. */
    public String[][] floor;

    public final java.util.List<Placement> units = new java.util.ArrayList<>();
    public final java.util.List<Placement> objects = new java.util.ArrayList<>();

    public MapDocument(int widthCells, int heightCells, int cellWidth, int cellHeight,
                        EngineOptions.ViewType viewType) {
        this.widthCells = widthCells;
        this.heightCells = heightCells;
        this.cellWidth = cellWidth;
        this.cellHeight = cellHeight;
        this.viewType = viewType;
        this.floor = new String[heightCells][widthCells];
    }

    public boolean isValid(int col, int row) {
        return col >= 0 && col < widthCells && row >= 0 && row < heightCells;
    }

    /** Removes every unit placement at (col,row). */
    public void clearUnitsAt(int col, int row) {
        units.removeIf(p -> p.col == col && p.row == row);
    }

    /** Removes every object placement at (col,row). */
    public void clearObjectsAt(int col, int row) {
        objects.removeIf(p -> p.col == col && p.row == row);
    }
}
