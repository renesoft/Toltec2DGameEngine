package org.toltec.editor.ui;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.util.StringConverter;
import org.toltec.unit.Gender;
import org.toltec.unit.UnitStatus;
import org.toltec.editor.model.EditableObject;
import org.toltec.editor.model.ObjectCategory;

import java.util.List;

public class TopBar extends VBox {

    private final TextField nameField = new TextField();
    private final Label categoryBadge = new Label();
    private final Label dirtyDot = new Label();
    private final Button saveButton = new Button("💾  Сохранить");

    private final ComboBox<Gender> genderCombo = new ComboBox<>();
    private final ComboBox<String> weaponCombo = new ComboBox<>();
    private final ComboBox<UnitStatus> statusCombo = new ComboBox<>();
    private final HBox unitRow;

    private final ComboBox<String> stateCombo = new ComboBox<>();
    private final CheckBox omniCheck = new CheckBox("Всенаправленный (без направлений)");
    private final HBox objectRow;

    private boolean binding = false;
    private EditableObject current;

    private Runnable onNameChanged;
    private Runnable onComboChanged;
    private Runnable onSaveRequested;

    public TopBar() {
        getStyleClass().add("panel");
        setPadding(new Insets(14, 18, 12, 18));
        setSpacing(10);

        categoryBadge.getStyleClass().add("category-badge");
        nameField.setPromptText("Название объекта");
        nameField.setPrefWidth(320);
        nameField.textProperty().addListener((o, a, b) -> {
            if (binding || current == null) return;
            current.name = b;
            current.dirty = true;
            if (onNameChanged != null) onNameChanged.run();
        });

        dirtyDot.setText("●");
        dirtyDot.setVisible(false);

        saveButton.getStyleClass().add("button-primary");
        saveButton.setOnAction(e -> { if (onSaveRequested != null) onSaveRequested.run(); });

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        HBox nameRow = new HBox(10, categoryBadge, nameField, dirtyDot, spacer, saveButton);
        nameRow.setAlignment(Pos.CENTER_LEFT);

        // ── Unit controls ────────────────────────────────────────────────
        genderCombo.setItems(javafx.collections.FXCollections.observableArrayList(Gender.values()));
        genderCombo.setConverter(new StringConverter<>() {
            public String toString(Gender g) { return g == null ? "" : genderLabel(g); }
            public Gender fromString(String s) { return null; }
        });
        weaponCombo.setEditable(true);
        weaponCombo.setPromptText("оружие (unarmed / sword / bow / …)");
        statusCombo.setItems(javafx.collections.FXCollections.observableArrayList(UnitStatus.values()));
        statusCombo.setConverter(new StringConverter<>() {
            public String toString(UnitStatus s) { return s == null ? "" : statusLabel(s); }
            public UnitStatus fromString(String s) { return null; }
        });

        genderCombo.setOnAction(e -> applyUnitCombo(() -> current.curGender = genderCombo.getValue()));
        weaponCombo.valueProperty().addListener((o, a, b) -> applyUnitCombo(() -> current.curWeapon = normWeapon(b)));
        weaponCombo.setOnAction(e -> applyUnitCombo(() -> current.curWeapon = normWeapon(weaponCombo.getValue())));
        statusCombo.setOnAction(e -> applyUnitCombo(() -> current.curStatus = statusCombo.getValue()));
        installCoverageMarkers();

        unitRow = new HBox(14,
                labeled("Пол персонажа", genderCombo),
                labeled("Оружие", weaponCombo),
                labeled("Состояние", statusCombo));
        unitRow.setAlignment(Pos.CENTER_LEFT);

        // ── Object controls ──────────────────────────────────────────────
        stateCombo.setEditable(true);
        stateCombo.setPromptText("состояние (default / open / broken / …)");
        stateCombo.valueProperty().addListener((o, a, b) -> applyObjectCombo(() -> current.curState = EditableObject.normState(b)));
        stateCombo.setOnAction(e -> applyObjectCombo(() -> current.curState = EditableObject.normState(stateCombo.getValue())));
        omniCheck.setOnAction(e -> applyObjectCombo(() -> current.omnidirectional = omniCheck.isSelected()));

        objectRow = new HBox(14, labeled("Состояние", stateCombo), omniCheck);
        objectRow.setAlignment(Pos.CENTER_LEFT);

        getChildren().addAll(nameRow, unitRow, objectRow);
    }

    private void applyUnitCombo(Runnable apply) {
        if (binding || current == null) return;
        apply.run();
        current.dirty = true;
        if (onComboChanged != null) onComboChanged.run();
    }

    private void applyObjectCombo(Runnable apply) {
        if (binding || current == null) return;
        apply.run();
        current.dirty = true;
        if (onComboChanged != null) onComboChanged.run();
    }

    private static String normWeapon(String s) {
        return s == null || s.isBlank() ? "unarmed" : s.trim();
    }

    private void installCoverageMarkers() {
        statusCombo.setCellFactory(lv -> new ListCell<>() {
            @Override protected void updateItem(UnitStatus item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) { setText(null); setGraphic(null); return; }
                setText(statusLabel(item));
                setGraphic(marker(current != null ? current.filledDirectionCount(item) : 0));
            }
        });
        statusCombo.setButtonCell(new ListCell<>() {
            @Override protected void updateItem(UnitStatus item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) { setText(null); setGraphic(null); return; }
                setText(statusLabel(item));
                setGraphic(marker(current != null ? current.filledDirectionCount(item) : 0));
            }
        });

        weaponCombo.setCellFactory(lv -> new ListCell<>() {
            @Override protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) { setText(null); setGraphic(null); return; }
                setText(item);
                setGraphic(marker(current != null ? current.filledDirectionCountForWeapon(item) : 0));
            }
        });

        stateCombo.setCellFactory(lv -> new ListCell<>() {
            @Override protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) { setText(null); setGraphic(null); return; }
                setText(item);
                setGraphic(marker(current != null ? current.filledDirectionCount(item) : 0));
            }
        });
    }

    /** {@code null} when there's nothing yet — a yellow dot once something's filled in, a green check once well covered (≥4 directions). */
    private static Node marker(int filledDirections) {
        if (filledDirections <= 0) return null;
        boolean wellCovered = filledDirections >= 4;
        Label l = new Label(wellCovered ? "✔" : "●");
        l.getStyleClass().add(wellCovered ? "marker-good" : "marker-partial");
        Tooltip.install(l, new Tooltip(wellCovered
                ? "Заполнено направлений: " + filledDirections + "/8"
                : "Есть кадры, но заполнено только " + filledDirections + "/8 направлений"));
        return l;
    }

    private VBox labeled(String label, Control control) {
        Label l = new Label(label);
        l.getStyleClass().add("hint-label");
        VBox box = new VBox(3, l, control);
        return box;
    }

    // =========================================================================
    // Binding
    // =========================================================================

    public void bind(EditableObject obj) {
        binding = true;
        try {
            current = obj;
            setDisable(obj == null);
            if (obj == null) {
                nameField.clear();
                categoryBadge.setText("");
                unitRow.setVisible(false); unitRow.setManaged(false);
                objectRow.setVisible(false); objectRow.setManaged(false);
                return;
            }

            nameField.setText(obj.name);
            categoryBadge.setText(obj.category.singularRu());
            categoryBadge.getStyleClass().removeAll("category-badge-floor", "category-badge-unit", "category-badge-object");
            categoryBadge.getStyleClass().add(switch (obj.category) {
                case FLOOR -> "category-badge-floor";
                case UNIT -> "category-badge-unit";
                case OBJECT -> "category-badge-object";
            });

            boolean isUnit = obj.category == ObjectCategory.UNIT;
            boolean isObject = obj.category == ObjectCategory.OBJECT;
            unitRow.setVisible(isUnit); unitRow.setManaged(isUnit);
            objectRow.setVisible(isObject); objectRow.setManaged(isObject);

            if (isUnit) {
                genderCombo.setValue(obj.curGender);
                weaponCombo.setItems(javafx.collections.FXCollections.observableArrayList(obj.usedWeapons()));
                weaponCombo.setValue(obj.curWeapon);
                statusCombo.setValue(obj.curStatus);
            } else if (isObject) {
                stateCombo.setItems(javafx.collections.FXCollections.observableArrayList(obj.usedStates()));
                stateCombo.setValue(obj.curState);
                omniCheck.setSelected(obj.omnidirectional);
            }
        } finally {
            binding = false;
        }
    }

    /** Re-reads the weapon/state suggestion lists and re-renders their coverage markers (call after any frame change). */
    public void refreshSuggestions() {
        if (current == null) return;
        binding = true;
        try {
            if (current.category == ObjectCategory.UNIT) {
                weaponCombo.setItems(javafx.collections.FXCollections.observableArrayList(current.usedWeapons()));
                weaponCombo.setValue(current.curWeapon);
                statusCombo.setItems(javafx.collections.FXCollections.observableArrayList(UnitStatus.values()));
                statusCombo.setValue(current.curStatus);
            } else if (current.category == ObjectCategory.OBJECT) {
                stateCombo.setItems(javafx.collections.FXCollections.observableArrayList(current.usedStates()));
                stateCombo.setValue(current.curState);
            }
        } finally {
            binding = false;
        }
    }

    public void setDirty(boolean dirty) {
        dirtyDot.setVisible(dirty);
        dirtyDot.getStyleClass().removeAll("dirty-dot", "saved-dot");
        dirtyDot.getStyleClass().add(dirty ? "dirty-dot" : "saved-dot");
    }

    public static String genderLabel(Gender g) {
        return switch (g) {
            case MALE -> "Мужчина";
            case FEMALE -> "Женщина";
            case GOBLIN -> "Гоблин";
        };
    }

    public static String statusLabel(UnitStatus s) {
        return switch (s) {
            case IDLE -> "Стоит";
            case WALK -> "Идёт";
            case RUN -> "Бежит";
            case ATTACK -> "Атакует";
            case HIT -> "Получает удар";
            case FALLING -> "Падает";
            case DYING -> "Умирает";
            case LYING -> "Лежит";
            case BERSERK -> "Ярость";
        };
    }

    public void setOnNameChanged(Runnable r) { this.onNameChanged = r; }
    public void setOnComboChanged(Runnable r) { this.onComboChanged = r; }
    public void setOnSaveRequested(Runnable r) { this.onSaveRequested = r; }
}
