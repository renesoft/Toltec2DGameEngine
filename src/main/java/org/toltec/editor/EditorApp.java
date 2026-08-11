package org.toltec.editor;

import javafx.application.Application;
import javafx.geometry.Orientation;
import javafx.scene.Scene;
import javafx.scene.control.SplitPane;
import javafx.scene.input.KeyCombination;
import javafx.scene.layout.BorderPane;
import javafx.stage.Stage;
import org.toltec.editor.ui.AssetPalette;
import org.toltec.editor.ui.CenterGrid;
import org.toltec.editor.ui.EditorController;
import org.toltec.editor.ui.LeftPanel;
import org.toltec.editor.ui.ParamsPanel;
import org.toltec.editor.ui.ThumbBackground;
import org.toltec.editor.ui.TopBar;

import java.util.Objects;

/**
 * Object editor for the Toltec tile engine: a left-hand catalog (floors /
 * units / objects), a name+combo top bar, a 3x3 grid with a live game-engine
 * preview in the middle and 8 direction drop-cells around it, a parameters
 * panel at the bottom, and an image palette on the right to drag frames from.
 * <p>
 * Every panel sits behind a {@link SplitPane} divider (both the left/center/
 * right split and the center/bottom split), so every panel's size is just a
 * drag away rather than fixed.
 */
public class EditorApp extends Application {

    @Override
    public void start(Stage stage) {
        LeftPanel leftPanel = new LeftPanel();
        TopBar topBar = new TopBar();
        ThumbBackground thumbBg = new ThumbBackground();
        CenterGrid centerGrid = new CenterGrid(thumbBg);
        ParamsPanel paramsPanel = new ParamsPanel();
        AssetPalette assetPalette = new AssetPalette(stage, thumbBg);

        leftPanel.getStyleClass().add("panel-border-right");
        assetPalette.getStyleClass().add("panel-border-left");
        topBar.getStyleClass().add("panel-border-bottom");
        paramsPanel.getStyleClass().add("panel-border-top");

        SplitPane verticalSplit = new SplitPane(centerGrid, paramsPanel);
        verticalSplit.setOrientation(Orientation.VERTICAL);
        verticalSplit.setDividerPositions(0.8);

        SplitPane horizontalSplit = new SplitPane(leftPanel, verticalSplit, assetPalette);
        horizontalSplit.setOrientation(Orientation.HORIZONTAL);
        horizontalSplit.setDividerPositions(0.15, 0.80);

        BorderPane root = new BorderPane();
        root.setTop(topBar);
        root.setCenter(horizontalSplit);
        root.getStyleClass().add("root");

        Scene scene = new Scene(root, 1500, 940);
        scene.getStylesheets().add(Objects.requireNonNull(
                getClass().getResource("/org/toltec/editor/editor.css")).toExternalForm());

        EditorController controller = new EditorController(stage, leftPanel, topBar, centerGrid, paramsPanel, assetPalette);

        scene.getAccelerators().put(
                KeyCombination.keyCombination("Shortcut+S"),
                controller::requestSave
        );

        stage.setTitle("Редактор объектов — Toltec");
        stage.setScene(scene);
        stage.setMinWidth(1180);
        stage.setMinHeight(760);
        stage.show();
    }
}

