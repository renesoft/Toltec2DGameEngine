package org.toltec.editor.ui;

import javafx.geometry.Pos;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.MenuItem;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.input.DragEvent;
import javafx.scene.input.MouseEvent;
import javafx.scene.input.TransferMode;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import org.toltec.unit.Direction8;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * One of the 8 direction slots (or the single slot used in "omnidirectional"
 * mode) around the live preview. Shows a thumbnail of the first frame and a
 * frame-count badge when there's more than one, accepts files dropped either
 * from the {@link AssetPalette} or straight from the OS file manager (both
 * arrive the same way — see {@link javafx.scene.input.Dragboard#getFiles()}),
 * and opens the full frame-list editor on click.
 */
public class DropCell extends StackPane {

    private final Direction8 direction;
    private final ImageView thumb = new ImageView();
    private final Label placeholderLabel = new Label();
    private final Label badge = new Label();

    private List<File> frames = new ArrayList<>();
    private boolean disabledSlot = false;

    private Consumer<Direction8> onSelect;
    private BiConsumer<Direction8, List<File>> onFramesDropped;
    private Consumer<Direction8> onOpenEditor;
    private Consumer<Direction8> onClear;

    public DropCell(Direction8 direction, String labelText, int size, ThumbBackground thumbBg) {
        this.direction = direction;
        getStyleClass().add("drop-cell");
        setPrefSize(size, size);
        setMinSize(size, size);
        setMaxSize(size, size);

        thumb.setFitWidth(size - 16);
        thumb.setFitHeight(size - 16);
        thumb.setPreserveRatio(true);
        thumb.setSmooth(true);

        placeholderLabel.setText(labelText);
        placeholderLabel.getStyleClass().add("drop-cell-label");

        Label topLabel = new Label(labelText);
        topLabel.getStyleClass().add("drop-cell-label");
        StackPane.setAlignment(topLabel, Pos.TOP_CENTER);
        StackPane.setMargin(topLabel, new javafx.geometry.Insets(5, 0, 0, 0));

        badge.getStyleClass().add("drop-cell-badge");
        badge.setVisible(false);
        StackPane.setAlignment(badge, Pos.BOTTOM_RIGHT);
        StackPane.setMargin(badge, new javafx.geometry.Insets(0, 5, 5, 0));

        VBox center = new VBox(thumb);
        center.setAlignment(Pos.CENTER);
        center.setPrefSize(size - 4, size - 4);
        center.setMaxSize(size - 4, size - 4);
        if (thumbBg != null) thumbBg.bind(center);

        getChildren().addAll(center, topLabel, badge);

        setOnMouseClicked(this::handleClick);
        setOnDragOver(this::handleDragOver);
        setOnDragEntered(e -> { if (!disabledSlot) getStyleClass().add("drop-cell-dragover"); });
        setOnDragExited(e -> getStyleClass().remove("drop-cell-dragover"));
        setOnDragDropped(this::handleDragDropped);

        setContextMenu(buildContextMenu());
        render();
    }

    private void setContextMenu(ContextMenu menu) {
        setOnContextMenuRequested(e -> menu.show(this, e.getScreenX(), e.getScreenY()));
    }

    private ContextMenu buildContextMenu() {
        MenuItem edit = new MenuItem("Редактировать кадры…");
        edit.setOnAction(e -> { if (onOpenEditor != null) onOpenEditor.accept(direction); });
        MenuItem clear = new MenuItem("Очистить");
        clear.setOnAction(e -> { if (onClear != null) onClear.accept(direction); });
        return new ContextMenu(edit, clear);
    }

    private void handleClick(MouseEvent e) {
        if (disabledSlot) return;
        if (onSelect != null) onSelect.accept(direction);
        if (e.getClickCount() >= 2 && onOpenEditor != null) onOpenEditor.accept(direction);
    }

    private void handleDragOver(DragEvent e) {
        if (!disabledSlot && e.getDragboard().hasFiles()) {
            e.acceptTransferModes(TransferMode.COPY);
        }
        e.consume();
    }

    private void handleDragDropped(DragEvent e) {
        getStyleClass().remove("drop-cell-dragover");
        boolean ok = false;
        if (!disabledSlot && e.getDragboard().hasFiles()) {
            List<File> dropped = e.getDragboard().getFiles().stream()
                    .filter(DropCell::looksLikeImage)
                    .toList();
            if (!dropped.isEmpty()) {
                if (onFramesDropped != null) onFramesDropped.accept(direction, dropped);
                ok = true;
            }
        }
        e.setDropCompleted(ok);
        e.consume();
    }

    private static boolean looksLikeImage(File f) {
        String n = f.getName().toLowerCase();
        return n.endsWith(".png") || n.endsWith(".jpg") || n.endsWith(".jpeg")
                || n.endsWith(".gif") || n.endsWith(".bmp") || n.endsWith(".webp");
    }

    // =========================================================================
    // State
    // =========================================================================

    public Direction8 direction() { return direction; }

    public void setFrames(List<File> frames) {
        this.frames = frames == null ? List.of() : frames;
        render();
    }

    public List<File> frames() { return frames; }

    public void setEnabledSlot(boolean enabled) {
        this.disabledSlot = !enabled;
        getStyleClass().remove("drop-cell-disabled");
        if (!enabled) getStyleClass().add("drop-cell-disabled");
        setMouseTransparent(false); // keep receiving clicks so a tooltip/hint could show later; drop is gated above
    }

    public void setSelected(boolean selected) {
        getStyleClass().remove("drop-cell-selected");
        if (selected) getStyleClass().add("drop-cell-selected");
    }

    private void render() {
        getStyleClass().remove("drop-cell-filled");
        if (frames.isEmpty()) {
            thumb.setImage(null);
            badge.setVisible(false);
            return;
        }
        getStyleClass().add("drop-cell-filled");
        File first = frames.get(0);
        try {
            thumb.setImage(new Image(first.toURI().toString(), 96, 96, true, true, true));
        } catch (Exception ex) {
            thumb.setImage(null);
        }
        if (frames.size() > 1) {
            badge.setText(String.valueOf(frames.size()));
            badge.setVisible(true);
        } else {
            badge.setVisible(false);
        }
    }

    // =========================================================================
    // Callbacks
    // =========================================================================

    public void setOnSelect(Consumer<Direction8> cb) { this.onSelect = cb; }
    public void setOnFramesDropped(BiConsumer<Direction8, List<File>> cb) { this.onFramesDropped = cb; }
    public void setOnOpenEditor(Consumer<Direction8> cb) { this.onOpenEditor = cb; }
    public void setOnClear(Consumer<Direction8> cb) { this.onClear = cb; }
}
