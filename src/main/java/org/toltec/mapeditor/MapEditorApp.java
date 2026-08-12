package org.toltec.mapeditor;

import javafx.application.Application;
import javafx.geometry.Orientation;
import javafx.scene.Scene;
import javafx.scene.control.SplitPane;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;
import org.toltec.mapeditor.ui.LeftPalettePanel;
import org.toltec.mapeditor.ui.MapEditorController;
import org.toltec.mapeditor.ui.TopToolbar;

import java.util.Objects;

/**
 * Map editor for the Toltec tile engine: paint floor with a resizable brush,
 * stamp units/objects (with multi-select-random and placement probability),
 * erase in four modes, rotate the current stamp direction, and save/load
 * levels as plain-text {@code .tmap} files — see
 * {@link org.toltec.mapeditor.io.MapFormat}.
 */
public class MapEditorApp extends Application {

    @Override
    public void start(Stage stage) {
        LeftPalettePanel palette = new LeftPalettePanel();
        TopToolbar toolbar = new TopToolbar();
        StackPane canvasHost = new StackPane();
        canvasHost.getStyleClass().add("preview-cell");

        palette.getStyleClass().add("panel-border-right");
        toolbar.getStyleClass().add("panel-border-bottom");

        SplitPane split = new SplitPane(palette, canvasHost);
        split.setOrientation(Orientation.HORIZONTAL);
        split.setDividerPositions(0.22);

        BorderPane root = new BorderPane();
        root.setTop(toolbar);
        root.setCenter(split);
        root.getStyleClass().add("root");

        Scene scene = new Scene(root, 1400, 900);
        scene.getStylesheets().add(Objects.requireNonNull(
                getClass().getResource("/org/toltec/editor/editor.css")).toExternalForm());
        scene.getStylesheets().add(Objects.requireNonNull(
                getClass().getResource("/org/toltec/mapeditor/mapeditor.css")).toExternalForm());

        new MapEditorController(stage, palette, toolbar, canvasHost);

        stage.setTitle("Редактор карт — Toltec");
        stage.setScene(scene);
        stage.setMinWidth(1024);
        stage.setMinHeight(680);
        stage.show();
    }
}
