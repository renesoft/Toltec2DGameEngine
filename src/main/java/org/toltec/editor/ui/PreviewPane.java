package org.toltec.editor.ui;

import javafx.embed.swing.SwingNode;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import org.toltec.engine.EngineOptions;
import org.toltec.engine.GameCanvas;
import org.toltec.editor.preview.PreviewEngine;
import org.toltec.editor.preview.PreviewService;

import javax.swing.SwingUtilities;
import java.awt.Dimension;

/**
 * The center cell of the 3x3 grid: the actual running {@code TileGameEngine},
 * embedded via a lightweight {@code GameCanvas} (a plain {@code JPanel}) inside
 * a {@link SwingNode} — plus a small button row (rotate + debug toggle) below it.
 * <p>
 * The canvas itself is kept perfectly square ({@code pixelSize - BAR_HEIGHT}
 * on each side) with the button row occupying the remaining {@code BAR_HEIGHT}
 * strip underneath, inside a {@link VBox} — a plain {@code BorderPane}'s
 * bottom slot would instead share the canvas's own fixed-size box and shrink
 * it to make room, leaving it shorter than it is wide; since the game's own
 * camera centers on {@code canvasWidth/2, canvasHeight/2}, a non-square
 * canvas reads as the 5x5 field sitting off-center. The VBox avoids that by
 * giving the button row its own dedicated space instead of splitting the
 * canvas's — {@code PreviewPane} itself keeps the same fixed
 * {@code pixelSize × pixelSize} footprint either way, so it's still centered
 * in the 3x3 grid the same way every direction cell is.
 */
public class PreviewPane extends StackPane {

    private static final int BAR_HEIGHT = 34;

    private final PreviewEngine engine;
    private final PreviewService previewService;

    private java.util.function.IntConsumer rotateCallback = i -> {};

    public PreviewPane(int pixelSize) {
        getStyleClass().add("preview-cell");
        setPrefSize(pixelSize, pixelSize);
        setMinSize(pixelSize, pixelSize);
        setMaxSize(pixelSize, pixelSize);

        int canvasSize = Math.max(1, pixelSize - BAR_HEIGHT);

        EngineOptions opts = new EngineOptions();
        opts.mapWidthCells = PreviewEngine.MAP_SIZE;
        opts.mapHeightCells = PreviewEngine.MAP_SIZE;
        opts.cellWidth = 84;
        opts.cellHeight = 42;
        opts.viewType = EngineOptions.ViewType.ISOMETRIC;
        opts.tickIntervalMs = 30;
        opts.renderIntervalMs = 30;
        opts.showFpsCounter = false;
        opts.wheelZoomEnabled = true;
        opts.keyboardZoomEnabled = true;
        opts.edgeScrollWidth = 0; // no panning in the preview — see PreviewEngine's mouseRight overrides

        engine = new PreviewEngine(opts);
        previewService = new PreviewService(engine);

        SwingNode swingNode = new SwingNode();
        StackPane canvasHost = new StackPane(swingNode);
        canvasHost.setPrefSize(canvasSize, canvasSize);
        canvasHost.setMinSize(canvasSize, canvasSize);
        canvasHost.setMaxSize(canvasSize, canvasSize);

        SwingUtilities.invokeLater(() -> {
            GameCanvas canvas = new GameCanvas(engine);
            Dimension exact = new Dimension(canvasSize, canvasSize);
            canvas.setPreferredSize(exact);
            canvas.setMinimumSize(exact);
            canvas.setMaximumSize(exact);
            swingNode.setContent(canvas);
            engine.start(canvas);
        });

        HBox rotateBar = buildRotateBar();

        VBox layout = new VBox(4, canvasHost, rotateBar);
        layout.setAlignment(Pos.CENTER);
        layout.setPrefSize(pixelSize, pixelSize);
        layout.setMaxSize(pixelSize, pixelSize);

        getChildren().add(layout);
    }

    private HBox buildRotateBar() {
        Button left = new Button("◀");
        Button right = new Button("▶");
        left.getStyleClass().add("button-flat");
        right.getStyleClass().add("button-flat");
        Tooltip.install(left, new Tooltip("Повернуть против часовой"));
        Tooltip.install(right, new Tooltip("Повернуть по часовой"));
        left.setOnAction(e -> rotate(-1));
        right.setOnAction(e -> rotate(1));

        ToggleButton debug = new ToggleButton("⊹");
        debug.getStyleClass().add("button-flat");
        Tooltip.install(debug, new Tooltip(
                "Отладочная разметка: рамка картинки, её нижняя точка (красная)"
                        + " и центр клетки (голубая)"));
        debug.selectedProperty().addListener((obs, was, isNow) -> engine.debugAnchors = isNow);

        HBox box = new HBox(10, left, right, debug);
        box.setAlignment(Pos.CENTER);
        box.setPrefHeight(BAR_HEIGHT);
        box.setStyle("-fx-background-color: rgba(20,22,28,0.55); -fx-background-radius: 8; -fx-padding: 4 10 4 10;");
        return box;
    }

    private void rotate(int step) {
        rotateCallback.accept(step);
    }

    /** Called with -1/+1 when a rotate button is pressed — wire this to advance the model's curDirection. */
    public void setOnRotateStep(java.util.function.IntConsumer callback) {
        this.rotateCallback = callback;
    }

    public PreviewEngine engine() { return engine; }
    public PreviewService previewService() { return previewService; }
}
