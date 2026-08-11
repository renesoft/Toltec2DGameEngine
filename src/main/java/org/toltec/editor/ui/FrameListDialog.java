package org.toltec.editor.ui;

import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import javafx.stage.Window;

import java.io.File;
import java.util.Collections;
import java.util.List;

/**
 * Lets you add, remove and reorder the frames dropped onto one direction
 * cell — a direction with more than one frame plays as an animation, in
 * list order.
 */
public class FrameListDialog {

    public static List<File> show(Window owner, String title, List<File> initialFrames) {
        Dialog<List<File>> dialog = new Dialog<>();
        dialog.initOwner(owner);
        dialog.setTitle("Кадры — " + title);
        dialog.getDialogPane().getStylesheets().addAll(owner.getScene().getStylesheets());
        dialog.getDialogPane().getStyleClass().add("panel");

        ObservableList<File> items = FXCollections.observableArrayList(initialFrames);
        ListView<File> listView = new ListView<>(items);
        listView.setPrefSize(420, 260);
        listView.setCellFactory(lv -> new ListCell<>() {
            private final ImageView iv = new ImageView();
            {
                iv.setFitWidth(40);
                iv.setFitHeight(40);
                iv.setPreserveRatio(true);
            }
            @Override
            protected void updateItem(File f, boolean empty) {
                super.updateItem(f, empty);
                if (empty || f == null) {
                    setText(null);
                    setGraphic(null);
                    return;
                }
                try {
                    iv.setImage(new Image(f.toURI().toString(), 40, 40, true, true, true));
                } catch (Exception ignored) {
                    iv.setImage(null);
                }
                setText("  " + f.getName());
                setGraphic(iv);
            }
        });

        Button add = new Button("+ Добавить…");
        Button remove = new Button("Удалить");
        Button up = new Button("▲ Вверх");
        Button down = new Button("▼ Вниз");
        for (Button b : List.of(add, remove, up, down)) b.setMaxWidth(Double.MAX_VALUE);

        add.setOnAction(e -> {
            FileChooser fc = new FileChooser();
            fc.setTitle("Выбрать изображения кадров");
            fc.getExtensionFilters().add(new FileChooser.ExtensionFilter(
                    "Изображения", "*.png", "*.jpg", "*.jpeg", "*.gif", "*.bmp", "*.webp"));
            List<File> chosen = fc.showOpenMultipleDialog(owner);
            if (chosen != null) items.addAll(chosen);
        });
        remove.setOnAction(e -> {
            int i = listView.getSelectionModel().getSelectedIndex();
            if (i >= 0) items.remove(i);
        });
        up.setOnAction(e -> {
            int i = listView.getSelectionModel().getSelectedIndex();
            if (i > 0) {
                Collections.swap(items, i, i - 1);
                listView.getSelectionModel().select(i - 1);
            }
        });
        down.setOnAction(e -> {
            int i = listView.getSelectionModel().getSelectedIndex();
            if (i >= 0 && i < items.size() - 1) {
                Collections.swap(items, i, i + 1);
                listView.getSelectionModel().select(i + 1);
            }
        });

        VBox buttons = new VBox(8, add, remove, up, down);
        buttons.setPadding(new Insets(0, 0, 0, 12));

        Label hint = new Label("Кадры проигрываются в этом порядке. Один кадр — статичная поза, несколько — анимация.");
        hint.getStyleClass().add("hint-label");
        hint.setWrapText(true);
        hint.setMaxWidth(420);

        BorderPane content = new BorderPane();
        content.setCenter(listView);
        content.setRight(buttons);
        VBox root = new VBox(10, content, hint);
        root.setPadding(new Insets(14));

        dialog.getDialogPane().setContent(root);
        dialog.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
        dialog.setResultConverter(bt -> bt == ButtonType.OK ? List.copyOf(items) : null);

        return dialog.showAndWait().orElse(null);
    }
}
