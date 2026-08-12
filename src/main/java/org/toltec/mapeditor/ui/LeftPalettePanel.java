package org.toltec.mapeditor.ui;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.Tooltip;
import javafx.scene.image.ImageView;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import org.toltec.editor.model.ObjectCategory;
import org.toltec.mapeditor.model.PaletteEntry;

import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Three stacked sections — floors / units / objects — each a scrollable flow
 * of thumbnail toggle cards. Selection is per-category and multi-select (a
 * plain {@link Set}, not a {@link javafx.scene.control.ToggleGroup} — a
 * {@code ToggleGroup} only ever allows one selected toggle, but the brush
 * needs "paint a random pick among everything currently selected").
 */
public class LeftPalettePanel extends VBox {

    private static final int THUMB = 56;

    private final Map<ObjectCategory, FlowPane> flows = new EnumMap<>(ObjectCategory.class);
    private final Map<ObjectCategory, Set<PaletteEntry>> selections = new EnumMap<>(ObjectCategory.class);
    private final Map<ObjectCategory, Label> emptyHints = new EnumMap<>(ObjectCategory.class);

    private Runnable onSelectionChanged = () -> {};

    public LeftPalettePanel() {
        setSpacing(0);
        setFillWidth(true);

        for (ObjectCategory cat : ObjectCategory.values()) {
            selections.put(cat, new LinkedHashSet<>());
            getChildren().add(buildSection(cat));
        }
    }

    private Region buildSection(ObjectCategory cat) {
        Label title = new Label(titleFor(cat));
        title.getStyleClass().add("section-title");

        FlowPane flow = new FlowPane(6, 6);
        flow.setPadding(new Insets(4, 8, 8, 8));
        flows.put(cat, flow);

        Label empty = new Label("Пусто — создайте объекты в редакторе объектов");
        empty.getStyleClass().add("hint-label");
        empty.setWrapText(true);
        empty.setPadding(new Insets(0, 8, 8, 8));
        empty.setVisible(false);
        empty.setManaged(false);
        emptyHints.put(cat, empty);

        ScrollPane scroll = new ScrollPane(flow);
        scroll.setFitToWidth(true);
        scroll.setPrefHeight(180);
        scroll.getStyleClass().add("edge-to-edge");

        VBox section = new VBox(title, empty, scroll);
        VBox.setVgrow(scroll, javafx.scene.layout.Priority.ALWAYS);
        return section;
    }

    private static String titleFor(ObjectCategory cat) {
        return switch (cat) {
            case FLOOR -> "ПОЛ";
            case UNIT -> "ЮНИТЫ";
            case OBJECT -> "ОБЪЕКТЫ";
        };
    }

    /** Replaces the catalog shown for {@code category} and clears its selection. */
    public void setEntries(ObjectCategory category, List<PaletteEntry> entries) {
        FlowPane flow = flows.get(category);
        flow.getChildren().clear();
        selections.get(category).clear();

        for (PaletteEntry entry : entries) {
            ToggleButton card = buildCard(entry);
            flow.getChildren().add(card);
        }

        Label hint = emptyHints.get(category);
        hint.setVisible(entries.isEmpty());
        hint.setManaged(entries.isEmpty());
    }

    private ToggleButton buildCard(PaletteEntry entry) {
        ToggleButton btn = new ToggleButton();
        btn.getStyleClass().add("asset-thumb");
        btn.setUserData(entry);

        VBox content = new VBox(2);
        content.setAlignment(Pos.CENTER);
        if (entry.thumbnail != null) {
            ImageView iv = new ImageView(entry.thumbnail);
            iv.setFitWidth(THUMB);
            iv.setFitHeight(THUMB);
            iv.setPreserveRatio(true);
            content.getChildren().add(iv);
        } else {
            Label ph = new Label("?");
            ph.setPrefSize(THUMB, THUMB);
            ph.setAlignment(Pos.CENTER);
            content.getChildren().add(ph);
        }
        Label name = new Label(truncate(entry.displayName, 12));
        name.getStyleClass().add("asset-thumb-name");
        content.getChildren().add(name);
        btn.setGraphic(content);
        Tooltip.install(btn, new Tooltip(entry.displayName));

        btn.selectedProperty().addListener((obs, was, isNow) -> {
            Set<PaletteEntry> sel = selections.get(entry.category);
            if (isNow) sel.add(entry); else sel.remove(entry);
            onSelectionChanged.run();
        });

        return btn;
    }

    private static String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }

    /** Currently selected entries for {@code category} — may be empty, never {@code null}. */
    public Set<PaletteEntry> selectedFor(ObjectCategory category) {
        return selections.get(category);
    }

    public void setOnSelectionChanged(Runnable callback) {
        this.onSelectionChanged = callback == null ? () -> {} : callback;
    }
}
