package org.toltec.mapeditor.ui;

import javafx.geometry.Insets;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ChoiceBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.Spinner;
import javafx.scene.layout.GridPane;
import org.toltec.engine.EngineOptions;

import java.util.List;
import java.util.Optional;

/** Modal dialog collecting the parameters for a brand-new map. */
public final class NewMapDialog {

    /** Result of a confirmed dialog — plain data, handed straight to {@code EngineOptions}. */
    public record Spec(int widthCells, int heightCells, int cellWidth, int cellHeight,
                        EngineOptions.ViewType viewType) {}

    private NewMapDialog() {}

    public static Optional<Spec> show() {
        Dialog<Spec> dialog = new Dialog<>();
        dialog.setTitle("Новая карта");
        dialog.setHeaderText("Параметры новой карты");

        Spinner<Integer> width = new Spinner<>(1, 500, 30);
        Spinner<Integer> height = new Spinner<>(1, 500, 30);
        Spinner<Integer> cellW = new Spinner<>(8, 512, 64);
        Spinner<Integer> cellH = new Spinner<>(8, 512, 32);
        List<Spinner<Integer>> spinners = List.of(width, height, cellW, cellH);
        for (Spinner<Integer> s : spinners) {
            s.setEditable(true);
            s.setPrefWidth(90);
        }
        ChoiceBox<EngineOptions.ViewType> view = new ChoiceBox<>();
        view.getItems().addAll(EngineOptions.ViewType.values());
        view.setValue(EngineOptions.ViewType.ISOMETRIC);
        view.setConverter(new javafx.util.StringConverter<>() {
            @Override public String toString(EngineOptions.ViewType v) {
                return v == null ? "" : (v == EngineOptions.ViewType.ISOMETRIC ? "Изометрия" : "Сверху (2D)");
            }
            @Override public EngineOptions.ViewType fromString(String s) { return null; }
        });

        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(10);
        grid.setPadding(new Insets(16));
        grid.addRow(0, new Label("Ширина карты (клеток):"), width);
        grid.addRow(1, new Label("Высота карты (клеток):"), height);
        grid.addRow(2, new Label("Ширина клетки (px):"), cellW);
        grid.addRow(3, new Label("Высота клетки (px):"), cellH);
        grid.addRow(4, new Label("Проекция:"), view);
        dialog.getDialogPane().setContent(grid);
        dialog.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);

        dialog.setResultConverter(bt -> bt == ButtonType.OK
                ? new Spec(width.getValue(), height.getValue(), cellW.getValue(), cellH.getValue(), view.getValue())
                : null);

        return dialog.showAndWait();
    }
}
