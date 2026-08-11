package org.toltec.editor.ui;

import javafx.geometry.HPos;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.geometry.VPos;
import javafx.scene.layout.GridPane;
import org.toltec.unit.Direction8;
import org.toltec.editor.model.ClipGroup;
import org.toltec.editor.model.EditableObject;
import org.toltec.editor.model.ObjectCategory;

import java.io.File;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

public class CenterGrid extends GridPane {

    private static final int CELL_SIZE = 130;
    private static final int PREVIEW_SIZE = 3 * CELL_SIZE + 2 * 10; // spans the same footprint as the 3x3 grid

    private final Map<Direction8, DropCell> cells = new EnumMap<>(Direction8.class);
    private final PreviewPane previewPane;

    private Consumer<Direction8> onDirectionSelected;
    private BiConsumer<Direction8, List<File>> onFramesDropped;
    private Consumer<Direction8> onOpenEditor;
    private Consumer<Direction8> onClear;

    public CenterGrid(ThumbBackground thumbBg) {
        setHgap(10);
        setVgap(10);
        setAlignment(Pos.CENTER);
        setPadding(new Insets(20));

        addCell(Direction8.NW, "СЗ", 0, 0, thumbBg);
        addCell(Direction8.N, "С", 1, 0, thumbBg);
        addCell(Direction8.NE, "СВ", 2, 0, thumbBg);
        addCell(Direction8.W, "З", 0, 1, thumbBg);
        addCell(Direction8.E, "В", 2, 1, thumbBg);
        addCell(Direction8.SW, "ЮЗ", 0, 2, thumbBg);
        addCell(Direction8.S, "Ю", 1, 2, thumbBg);
        addCell(Direction8.SE, "ЮВ", 2, 2, thumbBg);

        previewPane = new PreviewPane(PREVIEW_SIZE > 360 ? 360 : PREVIEW_SIZE);
        add(previewPane, 1, 1);
        GridPane.setHalignment(previewPane, HPos.CENTER);
        GridPane.setValignment(previewPane, VPos.CENTER);

        for (int c = 0; c < 3; c++) {
            javafx.scene.layout.ColumnConstraints cc = new javafx.scene.layout.ColumnConstraints();
            cc.setHalignment(HPos.CENTER);
            getColumnConstraints().add(cc);
        }
        for (int r = 0; r < 3; r++) {
            javafx.scene.layout.RowConstraints rc = new javafx.scene.layout.RowConstraints();
            rc.setValignment(VPos.CENTER);
            getRowConstraints().add(rc);
        }
    }

    private void addCell(Direction8 dir, String label, int col, int row, ThumbBackground thumbBg) {
        DropCell cell = new DropCell(dir, label + " · " + dir.name(), CELL_SIZE, thumbBg);
        cell.setOnSelect(d -> { if (onDirectionSelected != null) onDirectionSelected.accept(d); });
        cell.setOnFramesDropped((d, files) -> { if (onFramesDropped != null) onFramesDropped.accept(d, files); });
        cell.setOnOpenEditor(d -> { if (onOpenEditor != null) onOpenEditor.accept(d); });
        cell.setOnClear(d -> { if (onClear != null) onClear.accept(d); });
        cells.put(dir, cell);
        add(cell, col, row);
        GridPane.setHalignment(cell, HPos.CENTER);
        GridPane.setValignment(cell, VPos.CENTER);
    }

    // =========================================================================
    // Binding to the model
    // =========================================================================

    /** Repopulates every cell's thumbnail/enabled-state from {@code obj}'s currently active clip group. */
    public void bind(EditableObject obj) {
        if (obj == null) {
            for (DropCell c : cells.values()) { c.setFrames(List.of()); c.setEnabledSlot(false); c.setSelected(false); }
            return;
        }

        if (obj.category == ObjectCategory.FLOOR) {
            bindFloor(obj);
        } else if (obj.category == ObjectCategory.OBJECT && obj.omnidirectional) {
            bindOmniObject(obj);
        } else {
            bindDirectional(obj.activeClipGroup(), obj.curDirection);
        }
    }

    private void bindFloor(EditableObject obj) {
        for (Direction8 d : Direction8.values()) {
            DropCell cell = cells.get(d);
            boolean active = d == Direction8.N || d == Direction8.E || d == Direction8.S || d == Direction8.W;
            cell.setEnabledSlot(active);
            cell.setSelected(false);
            File f = active ? obj.floorImages.get(d.name()) : null;
            cell.setFrames(f != null ? List.of(f) : List.of());
        }
    }

    private void bindOmniObject(EditableObject obj) {
        ClipGroup group = obj.activeClipGroup();
        for (Direction8 d : Direction8.values()) {
            DropCell cell = cells.get(d);
            if (d == Direction8.S) {
                cell.setEnabledSlot(true);
                cell.setSelected(true);
                cell.setFrames(group != null ? group.peek(Direction8.S) : List.of());
            } else {
                cell.setEnabledSlot(false);
                cell.setSelected(false);
                cell.setFrames(List.of());
            }
        }
    }

    private void bindDirectional(ClipGroup group, Direction8 selected) {
        for (Direction8 d : Direction8.values()) {
            DropCell cell = cells.get(d);
            cell.setEnabledSlot(true);
            cell.setSelected(d == selected);
            cell.setFrames(group != null ? group.peek(d) : List.of());
        }
    }

    /** Just refreshes the highlighted cell (cheap — called on every direction click/rotate). */
    public void setSelectedDirection(Direction8 d) {
        for (Map.Entry<Direction8, DropCell> e : cells.entrySet()) {
            e.getValue().setSelected(e.getKey() == d);
        }
    }

    public PreviewPane previewPane() { return previewPane; }

    // =========================================================================
    // Callbacks
    // =========================================================================

    public void setOnDirectionSelected(Consumer<Direction8> cb) { this.onDirectionSelected = cb; }
    public void setOnFramesDropped(BiConsumer<Direction8, List<File>> cb) { this.onFramesDropped = cb; }
    public void setOnOpenEditor(Consumer<Direction8> cb) { this.onOpenEditor = cb; }
    public void setOnClear(Consumer<Direction8> cb) { this.onClear = cb; }
}
