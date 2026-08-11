package org.toltec.editor.ui;

import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.scene.control.*;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import org.toltec.editor.model.ObjectCategory;

import java.util.EnumMap;
import java.util.Map;
import java.util.Optional;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

public class LeftPanel extends VBox {

    private final TabPane tabPane = new TabPane();
    private final Map<ObjectCategory, ListView<String>> lists = new EnumMap<>(ObjectCategory.class);

    private BiConsumer<ObjectCategory, String> onSelect;
    private Consumer<ObjectCategory> onNewRequested;
    private Runnable onCloneRequested;
    private Runnable onDeleteRequested;

    private boolean binding = false;

    public LeftPanel() {
        getStyleClass().add("panel");
        setPrefWidth(240);
        setMinWidth(200);
        setSpacing(0);

        for (ObjectCategory cat : ObjectCategory.values()) {
            ListView<String> list = new ListView<>(FXCollections.observableArrayList());
            list.getSelectionModel().selectedItemProperty().addListener((o, a, b) -> {
                if (binding || b == null) return;
                if (onSelect != null) onSelect.accept(cat, b);
            });
            lists.put(cat, list);

            Tab tab = new Tab(cat.pluralRu(), list);
            tab.setClosable(false);
            tabPane.getTabs().add(tab);
        }

        Button newBtn = new Button("+ Новый");
        Button cloneBtn = new Button("Клонировать");
        Button deleteBtn = new Button("Удалить");
        cloneBtn.getStyleClass().add("button-flat");
        deleteBtn.getStyleClass().addAll("button-flat", "button-danger");
        newBtn.getStyleClass().add("button-primary");

        newBtn.setOnAction(e -> { if (onNewRequested != null) onNewRequested.accept(activeCategory()); });
        cloneBtn.setOnAction(e -> { if (onCloneRequested != null) onCloneRequested.run(); });
        deleteBtn.setOnAction(e -> { if (onDeleteRequested != null) onDeleteRequested.run(); });

        HBox toolbar = new HBox(6, newBtn, cloneBtn, deleteBtn);
        toolbar.setPadding(new Insets(10));
        HBox.setHgrow(newBtn, Priority.ALWAYS);
        newBtn.setMaxWidth(Double.MAX_VALUE);

        VBox.setVgrow(tabPane, Priority.ALWAYS);
        getChildren().addAll(tabPane, toolbar);
    }

    public ObjectCategory activeCategory() {
        return ObjectCategory.values()[tabPane.getSelectionModel().getSelectedIndex()];
    }

    public void setActiveCategory(ObjectCategory cat) {
        tabPane.getSelectionModel().select(cat.ordinal());
    }

    public void setNames(ObjectCategory cat, java.util.List<String> names) {
        binding = true;
        try {
            lists.get(cat).setItems(FXCollections.observableArrayList(names));
        } finally {
            binding = false;
        }
    }

    public void selectName(ObjectCategory cat, String name) {
        binding = true;
        try {
            setActiveCategory(cat);
            lists.get(cat).getSelectionModel().select(name);
        } finally {
            binding = false;
        }
    }

    public void clearSelection() {
        binding = true;
        try {
            for (ListView<String> l : lists.values()) l.getSelectionModel().clearSelection();
        } finally {
            binding = false;
        }
    }

    public static Optional<String> promptName(String title, String header, String initial) {
        TextInputDialog dialog = new TextInputDialog(initial == null ? "" : initial);
        dialog.setTitle(title);
        dialog.setHeaderText(header);
        dialog.setContentText("Название:");
        return dialog.showAndWait();
    }

    public static boolean confirm(String title, String content) {
        Alert alert = new Alert(Alert.AlertType.CONFIRMATION, content, ButtonType.YES, ButtonType.NO);
        alert.setTitle(title);
        alert.setHeaderText(null);
        return alert.showAndWait().filter(bt -> bt == ButtonType.YES).isPresent();
    }

    public void setOnSelect(BiConsumer<ObjectCategory, String> cb) { this.onSelect = cb; }
    public void setOnNewRequested(Consumer<ObjectCategory> cb) { this.onNewRequested = cb; }
    public void setOnCloneRequested(Runnable cb) { this.onCloneRequested = cb; }
    public void setOnDeleteRequested(Runnable cb) { this.onDeleteRequested = cb; }
}
