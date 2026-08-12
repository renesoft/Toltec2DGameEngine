package org.toltec.mapeditor.ui;

import javafx.beans.property.BooleanProperty;
import javafx.beans.property.DoubleProperty;
import javafx.beans.property.IntegerProperty;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleDoubleProperty;
import javafx.beans.property.SimpleIntegerProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ChoiceBox;
import javafx.scene.control.Label;
import javafx.scene.control.Separator;
import javafx.scene.control.Slider;
import javafx.scene.control.Spinner;
import javafx.scene.control.SpinnerValueFactory;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import org.toltec.mapeditor.model.EraseMode;
import org.toltec.mapeditor.model.Tool;
import org.toltec.unit.Direction8;

/** Top toolbar: map file actions, tool selection, brush/probability controls, and the current stamp direction. */
public class TopToolbar extends HBox {

    private final ObjectProperty<Tool> tool = new SimpleObjectProperty<>(Tool.FLOOR_BRUSH);
    private final ObjectProperty<EraseMode> eraseMode = new SimpleObjectProperty<>(EraseMode.UNITS_AND_OBJECTS);
    private final IntegerProperty brushSize = new SimpleIntegerProperty(1);
    /** 0.0–1.0. */
    private final DoubleProperty probability = new SimpleDoubleProperty(1.0);
    private final BooleanProperty showGrid = new SimpleBooleanProperty(true);

    private final Label directionLabel = new Label("S");
    private final Label statusLabel = new Label();

    private Runnable onNewMap = () -> {};
    private Runnable onSave = () -> {};
    private Runnable onLoad = () -> {};
    private Runnable onRotate = () -> {};

    public TopToolbar() {
        setSpacing(10);
        setAlignment(Pos.CENTER_LEFT);
        setPadding(new Insets(6, 10, 6, 10));

        Button newBtn = new Button("Новая карта");
        newBtn.getStyleClass().add("button-primary");
        newBtn.setOnAction(e -> onNewMap.run());

        Button saveBtn = new Button("Сохранить");
        saveBtn.setOnAction(e -> onSave.run());

        Button loadBtn = new Button("Загрузить");
        loadBtn.setOnAction(e -> onLoad.run());

        // ── Tools ────────────────────────────────────────────────────────────
        ToggleGroup toolGroup = new ToggleGroup();
        ToggleButton floorBtn = toolButton("Пол", Tool.FLOOR_BRUSH, toolGroup);
        ToggleButton unitBtn = toolButton("Юниты", Tool.UNIT_BRUSH, toolGroup);
        ToggleButton objectBtn = toolButton("Объекты", Tool.OBJECT_BRUSH, toolGroup);
        ToggleButton eraserBtn = toolButton("Ластик", Tool.ERASER, toolGroup);
        floorBtn.setSelected(true);
        Tooltip.install(floorBtn, new Tooltip("Кисть пола — выберите один или несколько типов пола слева"));
        Tooltip.install(unitBtn, new Tooltip("Кисть юнитов — выберите один или несколько юнитов слева"));
        Tooltip.install(objectBtn, new Tooltip("Кисть объектов — выберите один или несколько объектов слева"));
        Tooltip.install(eraserBtn, new Tooltip("Ластик — режим стирания выбирается справа"));

        // ── Eraser mode ──────────────────────────────────────────────────────
        ChoiceBox<EraseMode> eraseModeBox = new ChoiceBox<>();
        eraseModeBox.getItems().addAll(EraseMode.values());
        eraseModeBox.setValue(eraseMode.get());
        eraseModeBox.setConverter(new javafx.util.StringConverter<>() {
            @Override public String toString(EraseMode m) { return m == null ? "" : m.label(); }
            @Override public EraseMode fromString(String s) { return null; }
        });
        eraseModeBox.valueProperty().bindBidirectional(eraseMode);
        eraseModeBox.disableProperty().bind(tool.isNotEqualTo(Tool.ERASER));

        // ── Brush size ───────────────────────────────────────────────────────
        Label brushLabel = new Label("Кисть:");
        Spinner<Integer> brushSpinner = new Spinner<>(1, 20, 1);
        brushSpinner.setEditable(true);
        brushSpinner.setPrefWidth(70);
        brushSpinner.getValueFactory().valueProperty().addListener((o, was, now) -> brushSize.set(now));

        // ── Probability (units/objects only) ────────────────────────────────
        Label probLabel = new Label("Вероятность:");
        Slider probSlider = new Slider(0, 100, 100);
        probSlider.setPrefWidth(110);
        Label probValue = new Label("100%");
        probSlider.valueProperty().addListener((o, was, now) -> {
            probability.set(now.doubleValue() / 100.0);
            probValue.setText(Math.round(now.doubleValue()) + "%");
        });
        HBox probBox = new HBox(6, probLabel, probSlider, probValue);
        probBox.setAlignment(Pos.CENTER_LEFT);
        probBox.disableProperty().bind(tool.isNotEqualTo(Tool.UNIT_BRUSH).and(tool.isNotEqualTo(Tool.OBJECT_BRUSH)));

        // ── Direction ────────────────────────────────────────────────────────
        Button rotateBtn = new Button("⟳ R");
        rotateBtn.getStyleClass().add("button-flat");
        Tooltip.install(rotateBtn, new Tooltip("Повернуть направление размещения (клавиша R)"));
        rotateBtn.setOnAction(e -> onRotate.run());
        directionLabel.setMinWidth(28);
        directionLabel.setAlignment(Pos.CENTER);
        HBox dirBox = new HBox(4, new Label("Направление:"), directionLabel, rotateBtn);
        dirBox.setAlignment(Pos.CENTER_LEFT);
        dirBox.disableProperty().bind(tool.isNotEqualTo(Tool.UNIT_BRUSH).and(tool.isNotEqualTo(Tool.OBJECT_BRUSH)));

        statusLabel.getStyleClass().add("hint-label");

        CheckBox gridCheck = new CheckBox("Сетка");
        gridCheck.selectedProperty().bindBidirectional(showGrid);
        Tooltip.install(gridCheck, new Tooltip("Показывать лёгкую сетку поверх пустых клеток"));

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        getChildren().addAll(
                newBtn, saveBtn, loadBtn, new Separator(javafx.geometry.Orientation.VERTICAL),
                floorBtn, unitBtn, objectBtn, eraserBtn, eraseModeBox,
                new Separator(javafx.geometry.Orientation.VERTICAL),
                brushLabel, brushSpinner, probBox, dirBox, gridCheck,
                spacer, statusLabel
        );

        toolGroup.selectedToggleProperty().addListener((obs, was, now) -> {
            if (now != null) tool.set((Tool) now.getUserData());
        });
    }

    private ToggleButton toolButton(String text, Tool value, ToggleGroup group) {
        ToggleButton b = new ToggleButton(text);
        b.getStyleClass().add("toggle-button");
        b.setUserData(value);
        b.setToggleGroup(group);
        return b;
    }

    // =========================================================================
    // Public state
    // =========================================================================

    public ObjectProperty<Tool> toolProperty() { return tool; }
    public ObjectProperty<EraseMode> eraseModeProperty() { return eraseMode; }
    public IntegerProperty brushSizeProperty() { return brushSize; }
    /** 0.0–1.0 chance a unit/object actually gets stamped into each brushed cell. */
    public DoubleProperty probabilityProperty() { return probability; }
    public BooleanProperty showGridProperty() { return showGrid; }

    public void setDirection(Direction8 d) { directionLabel.setText(d.name()); }

    public void setStatus(String text) { statusLabel.setText(text == null ? "" : text); }

    public void setOnNewMap(Runnable r) { this.onNewMap = r; }
    public void setOnSave(Runnable r) { this.onSave = r; }
    public void setOnLoad(Runnable r) { this.onLoad = r; }
    public void setOnRotate(Runnable r) { this.onRotate = r; }
}
