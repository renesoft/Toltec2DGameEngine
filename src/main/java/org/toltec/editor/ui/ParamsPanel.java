package org.toltec.editor.ui;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import org.toltec.editor.model.ClipGroup;
import org.toltec.editor.model.ClipParams;
import org.toltec.editor.model.EditableObject;

import java.util.function.Consumer;
import java.util.function.DoubleConsumer;
import java.util.function.IntConsumer;

public class ParamsPanel extends VBox {

    private final HBox content = new HBox(28);
    private EditableObject current;
    private Runnable onChanged;

    public ParamsPanel() {
        getStyleClass().add("panel");
        setPadding(new Insets(4, 0, 4, 0));

        content.setPadding(new Insets(14, 18, 14, 18));
        content.setAlignment(Pos.TOP_LEFT);

        ScrollPane scroll = new ScrollPane(content);
        scroll.setFitToHeight(true);
        scroll.setPrefHeight(128);
        scroll.setMinHeight(128);
        VBox.setVgrow(scroll, Priority.ALWAYS);

        getChildren().add(scroll);
    }

    public void setOnChanged(Runnable r) { this.onChanged = r; }

    private void changed() {
        if (current != null) current.dirty = true;
        if (onChanged != null) onChanged.run();
    }

    // =========================================================================
    // Binding
    // =========================================================================

    public void bind(EditableObject obj) {
        this.current = null; // suppress change events while (re)building
        content.getChildren().clear();
        if (obj == null) {
            setDisable(true);
            return;
        }
        setDisable(false);

        switch (obj.category) {
            case FLOOR -> buildFloor(obj);
            case UNIT -> buildUnit(obj);
            case OBJECT -> buildObjectCat(obj);
        }
        this.current = obj;
    }

    // =========================================================================
    // Floor
    // =========================================================================

    private void buildFloor(EditableObject obj) {
        TextField speed = numberField(obj.speedMultiplier, v -> obj.speedMultiplier = v);
        TextField dmg = numberField(obj.damagePerSecond, v -> obj.damagePerSecond = v);
        CheckBox walkable = new CheckBox("Можно пройти");
        walkable.setSelected(obj.walkable);
        walkable.setOnAction(e -> { obj.walkable = walkable.isSelected(); changed(); });

        content.getChildren().add(section("СВОЙСТВА ПОЛА",
                field("Проходимость", walkable),
                field("Множитель скорости движения", speed, "1.0 — обычная скорость, 0.5 — в 2 раза медленнее"),
                field("Урон в секунду", dmg, "0 — без урона (например, лава > 0)")));
    }

    // =========================================================================
    // Unit
    // =========================================================================

    private void buildUnit(EditableObject obj) {
        ClipGroup group = obj.currentUnitClipGroup();
        content.getChildren().add(clipParamsSection(group.params));

        TextField hit = numberField(obj.hitChance, v -> obj.hitChance = v);
        TextField dodge = numberField(obj.dodgeChance, v -> obj.dodgeChance = v);
        TextField block = numberField(obj.blockChance, v -> obj.blockChance = v);
        TextField interrupt = numberField(obj.attackInterruptChance, v -> obj.attackInterruptChance = v);

        content.getChildren().add(section("БОЕВЫЕ ХАРАКТЕРИСТИКИ (для всего юнита)",
                field("Шанс попадания", hit, "0..1"),
                field("Шанс уклонения", dodge, "0..1"),
                field("Шанс блока", block, "0..1"),
                field("Шанс прервать атаку", interrupt, "0..1, при получении урона во время замаха")));

        CheckBox fitToCell = new CheckBox("Подгонять под клетку");
        fitToCell.setSelected(obj.fitToCell);
        fitToCell.setOnAction(e -> { obj.fitToCell = fitToCell.isSelected(); changed(); });

        TextField fitScale = numberField(obj.fitScale, v -> obj.fitScale = v);
        TextField sizeCells = intField(obj.sizeCells, v -> obj.sizeCells = Math.max(1, v));

        content.getChildren().add(section("РАЗМЕР НА КЛЕТКЕ (для всего юнита)",
                field("Подгонять под клетку", fitToCell,
                        "выкл — картинка рисуется в натуральную величину (обычно слишком крупно)"),
                field("Множитель размера", fitScale),
                field("Размер, клеток (напр. 2 = 2×2)", sizeCells,
                        "картинка не обрезается и автоматически опускается на sizeCells×cellHeight/2")));
    }

    // =========================================================================
    // Object
    // =========================================================================

    private void buildObjectCat(EditableObject obj) {
        ClipGroup group = obj.currentObjectClipGroup();
        content.getChildren().add(clipParamsSection(group.params));

        CheckBox collision = new CheckBox("Блокирует проход");
        collision.setSelected(obj.objCollision);
        collision.setOnAction(e -> { obj.objCollision = collision.isSelected(); changed(); });

        CheckBox isometric = new CheckBox("Изометрический спрайт");
        isometric.setSelected(obj.isometric);
        isometric.setOnAction(e -> { obj.isometric = isometric.isSelected(); changed(); });

        CheckBox fitToCell = new CheckBox("Подгонять под клетку");
        fitToCell.setSelected(obj.fitToCell);
        fitToCell.setOnAction(e -> { obj.fitToCell = fitToCell.isSelected(); changed(); });

        TextField layer = intField(obj.objLayer, v -> obj.objLayer = v);
        TextField fitScale = numberField(obj.fitScale, v -> obj.fitScale = v);
        TextField drawW = intField(obj.drawWidth, v -> obj.drawWidth = v);
        TextField drawH = intField(obj.drawHeight, v -> obj.drawHeight = v);
        TextField xOff = intField(obj.xOffset, v -> obj.xOffset = v);
        TextField yOff = intField(obj.yOffset, v -> obj.yOffset = v);
        TextField sizeCols = intField(obj.sizeCols, v -> obj.sizeCols = Math.max(1, v));
        TextField sizeRows = intField(obj.sizeRows, v -> obj.sizeRows = Math.max(1, v));

        content.getChildren().add(section("СВОЙСТВА ОБЪЕКТА",
                field("Слой отрисовки", layer, "порядок относительно других объектов в клетке"),
                field("Блокирует проход", collision),
                field("Изометрический спрайт", isometric, "выкл — картинка «ложится» на плитку плоско"),
                field("Ширина, px (-1 = авто)", drawW),
                field("Высота, px (-1 = авто)", drawH),
                field("Подгонять под клетку", fitToCell),
                field("Множитель размера", fitScale),
                field("Сдвиг X, px", xOff),
                field("Сдвиг Y, px", yOff),
                field("Размер по X, клеток", sizeCols,
                        "вправо от исходной клетки; влияет на отступ и (при «Подгонять под клетку») на ширину"),
                field("Размер по Y, клеток", sizeRows,
                        "вниз от исходной клетки; влияет на отступ и (при «Подгонять под клетку») на ширину")));
    }

    // =========================================================================
    // Shared "clip" params (duration/speed/loop/order/scale)
    // =========================================================================

    private VBox clipParamsSection(ClipParams p) {
        TextField duration = nullableIntField(p.durationMs, v -> p.durationMs = v);
        TextField ticks = nullableIntField(p.ticksPerFrame, v -> p.ticksPerFrame = v);
        ComboBox<String> loop = new ComboBox<>();
        loop.getItems().addAll("Авто", "Да", "Нет");
        loop.setValue(p.loop == null ? "Авто" : (p.loop ? "Да" : "Нет"));
        loop.setOnAction(e -> {
            p.loop = switch (loop.getValue()) { case "Да" -> true; case "Нет" -> false; default -> null; };
            changed();
        });
        CheckBox reverse = new CheckBox("Задом наперёд");
        reverse.setSelected(p.reverse);
        reverse.setOnAction(e -> { p.reverse = reverse.isSelected(); changed(); });
        TextField scale = nullableDoubleField(p.scale, v -> p.scale = v);

        return section("ПАРАМЕТРЫ АНИМАЦИИ (для текущей комбинации)",
                field("Длительность, мс", duration, "также скорость движения для Walk/Run"),
                field("Кадров в тике (если без мс)", ticks),
                field("Зациклено", loop),
                field("Обратный порядок", reverse),
                field("Масштаб", scale, "пусто = 1.0"));
    }

    // =========================================================================
    // Small builders
    // =========================================================================

    private VBox section(String title, javafx.scene.Node... fields) {
        Label t = new Label(title);
        t.getStyleClass().add("section-title");
        HBox row = new HBox(18);
        row.getChildren().addAll(fields);
        VBox box = new VBox(8, t, row);
        return box;
    }

    private VBox field(String label, javafx.scene.Node control) {
        return field(label, control, null);
    }

    private VBox field(String label, javafx.scene.Node control, String hint) {
        Label l = new Label(label);
        l.getStyleClass().add("hint-label");
        if (control instanceof TextField tf) tf.setPrefWidth(110);
        if (control instanceof ComboBox<?> cb) cb.setPrefWidth(110);
        VBox box = new VBox(3, l, control);
        if (hint != null) Tooltip.install(box, new Tooltip(hint));
        return box;
    }

    private TextField numberField(double initial, DoubleConsumer setter) {
        TextField tf = new TextField(fmt(initial));
        tf.textProperty().addListener((o, a, b) -> {
            try {
                setter.accept(Double.parseDouble(b.trim().replace(',', '.')));
                changed();
            } catch (NumberFormatException ignored) { /* keep typing */ }
        });
        return tf;
    }

    private TextField intField(int initial, IntConsumer setter) {
        TextField tf = new TextField(String.valueOf(initial));
        tf.textProperty().addListener((o, a, b) -> {
            try {
                setter.accept(Integer.parseInt(b.trim()));
                changed();
            } catch (NumberFormatException ignored) { /* keep typing */ }
        });
        return tf;
    }

    private TextField nullableIntField(Integer initial, Consumer<Integer> setter) {
        TextField tf = new TextField(initial == null ? "" : String.valueOf(initial));
        tf.setPromptText("—");
        tf.textProperty().addListener((o, a, b) -> {
            String t = b.trim();
            if (t.isEmpty()) { setter.accept(null); changed(); return; }
            try {
                setter.accept(Integer.parseInt(t));
                changed();
            } catch (NumberFormatException ignored) { /* keep typing */ }
        });
        return tf;
    }

    private TextField nullableDoubleField(Double initial, Consumer<Double> setter) {
        TextField tf = new TextField(initial == null ? "" : fmt(initial));
        tf.setPromptText("—");
        tf.textProperty().addListener((o, a, b) -> {
            String t = b.trim();
            if (t.isEmpty()) { setter.accept(null); changed(); return; }
            try {
                setter.accept(Double.parseDouble(t.replace(',', '.')));
                changed();
            } catch (NumberFormatException ignored) { /* keep typing */ }
        });
        return tf;
    }

    private static String fmt(double d) {
        if (d == Math.rint(d)) return String.valueOf((long) d);
        return String.valueOf(d);
    }
}
