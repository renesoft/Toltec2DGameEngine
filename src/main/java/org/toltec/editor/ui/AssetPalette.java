package org.toltec.editor.ui;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Slider;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.Tooltip;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.input.ClipboardContent;
import javafx.scene.input.TransferMode;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.DirectoryChooser;
import javafx.stage.FileChooser;
import javafx.stage.Window;
import org.toltec.editor.util.FilenameParser;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.prefs.Preferences;
import java.util.stream.Collectors;

/**
 * A session library of image files the user can drag onto any of the 8
 * direction cells — populated by "Импортировать файлы…" / "Открыть папку…"
 * (optionally recursive), and automatically whenever an existing object is
 * opened (its own art is added so it's easy to reuse/rearrange). Also hosts
 * search/keyword filtering, thumbnail size and background controls, and the
 * two auto-import buttons.
 */
public class AssetPalette extends VBox {

    private static final Set<String> IMAGE_EXT = Set.of("png", "jpg", "jpeg", "gif", "bmp", "webp");
    private static final Preferences PREFS = Preferences.userNodeForPackage(AssetPalette.class);
    private static final String PREF_LAST_DIR = "lastImageDir";

    private final FlowPane thumbFlow = new FlowPane(8, 8);
    private final FlowPane keywordFlow = new FlowPane(5, 5);
    private final Set<File> files = new LinkedHashSet<>();
    private final Set<String> selectedKeywords = new LinkedHashSet<>();
    private final Window owner;
    private final ThumbBackground thumbBg;
    private final CheckBox recursiveCheck = new CheckBox("искать в подпапках");

    private double thumbSize = 88;
    private String searchText = "";

    private Consumer<List<File>> onAutoImportAngles;
    private Consumer<List<File>> onImportStates;

    public AssetPalette(Window owner, ThumbBackground thumbBg) {
        this.owner = owner;
        this.thumbBg = thumbBg;
        getStyleClass().add("panel");
        setPrefWidth(280);
        setMinWidth(220);
        setPadding(new Insets(12));
        setSpacing(10);

        Label title = new Label("АРТ");
        title.getStyleClass().add("section-title");

        Button importBtn = new Button("+ Импортировать файлы…");
        importBtn.getStyleClass().add("button-primary");
        importBtn.setMaxWidth(Double.MAX_VALUE);
        importBtn.setOnAction(e -> importFiles());

        Button folderBtn = new Button("Открыть папку…");
        folderBtn.setMaxWidth(Double.MAX_VALUE);
        folderBtn.setOnAction(e -> importFolder());
        recursiveCheck.setSelected(true);

        Button clearBtn = new Button("Очистить список");
        clearBtn.getStyleClass().add("button-flat");
        clearBtn.setMaxWidth(Double.MAX_VALUE);
        clearBtn.setOnAction(e -> { files.clear(); selectedKeywords.clear(); rebuild(); });

        // ── Search ──────────────────────────────────────────────────────
        TextField search = new TextField();
        search.setPromptText("Поиск по названию…");
        search.textProperty().addListener((o, a, b) -> { searchText = b == null ? "" : b.trim(); rebuild(); });

        keywordFlow.setPadding(new Insets(0));

        // ── Auto-import ─────────────────────────────────────────────────
        Button autoAngles = new Button("↻ Автоимпорт углов");
        autoAngles.setMaxWidth(Double.MAX_VALUE);
        Tooltip.install(autoAngles, new Tooltip(
                "Ищет в названиях видимых картинок направление (000, 045… или N/NE/E…/С/СВ…) "
                        + "и раскладывает их по клеткам текущей комбинации."));
        autoAngles.setOnAction(e -> { if (onAutoImportAngles != null) onAutoImportAngles.accept(visibleFiles()); });

        Button importStates = new Button("↻ Импорт состояний");
        importStates.setMaxWidth(Double.MAX_VALUE);
        Tooltip.install(importStates, new Tooltip(
                "Для юнитов: по названиям видимых картинок (например Attack_Bow_000) определяет "
                        + "состояние, оружие и угол и раскладывает всё по нужным комбинациям сразу."));
        importStates.setOnAction(e -> { if (onImportStates != null) onImportStates.accept(visibleFiles()); });

        VBox autoImportBox = new VBox(6, autoAngles, importStates);

        // ── Thumbnail size + background ────────────────────────────────
        Slider sizeSlider = new Slider(48, 160, thumbSize);
        sizeSlider.valueProperty().addListener((o, a, b) -> { thumbSize = b.doubleValue(); rebuild(); });
        HBox sizeRow = new HBox(8, new Label("Размер"), sizeSlider);
        sizeRow.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(sizeSlider, Priority.ALWAYS);

        HBox bgRow = buildBackgroundPicker();

        // ── Thumbnails ──────────────────────────────────────────────────
        thumbFlow.setPadding(new Insets(4, 0, 4, 0));
        ScrollPane scroll = new ScrollPane(thumbFlow);
        scroll.setFitToWidth(true);
        VBox.setVgrow(scroll, Priority.ALWAYS);

        Label hint = new Label("Перетащите картинку на клетку направления слева, чтобы назначить кадр. "
                + "Можно бросать несколько раз подряд — кадры добавятся друг за другом.");
        hint.getStyleClass().add("hint-label");
        hint.setWrapText(true);

        getChildren().addAll(title, importBtn,
                new HBox(8, folderBtn, recursiveCheck), search, keywordFlow,
                autoImportBox, sizeRow, bgRow, hint, scroll, clearBtn);
    }

    private HBox buildBackgroundPicker() {
        ToggleButton dark = new ToggleButton("Тёмный");
        ToggleButton light = new ToggleButton("Светлый");
        ToggleButton checker = new ToggleButton("Клетка");
        javafx.scene.control.ToggleGroup group = new javafx.scene.control.ToggleGroup();
        dark.setToggleGroup(group);
        light.setToggleGroup(group);
        checker.setToggleGroup(group);
        switch (thumbBg.getMode()) {
            case DARK -> dark.setSelected(true);
            case LIGHT -> light.setSelected(true);
            case CHECKER -> checker.setSelected(true);
        }
        dark.setOnAction(e -> thumbBg.setMode(ThumbBackground.Mode.DARK));
        light.setOnAction(e -> thumbBg.setMode(ThumbBackground.Mode.LIGHT));
        checker.setOnAction(e -> thumbBg.setMode(ThumbBackground.Mode.CHECKER));
        for (ToggleButton b : List.of(dark, light, checker)) {
            b.setMaxWidth(Double.MAX_VALUE);
            HBox.setHgrow(b, Priority.ALWAYS);
        }
        HBox row = new HBox(4, dark, light, checker);
        row.setAlignment(Pos.CENTER_LEFT);
        return row;
    }

    // =========================================================================
    // Import
    // =========================================================================

    private void importFiles() {
        FileChooser fc = new FileChooser();
        fc.setTitle("Импортировать изображения");
        fc.getExtensionFilters().add(new FileChooser.ExtensionFilter(
                "Изображения", "*.png", "*.jpg", "*.jpeg", "*.gif", "*.bmp", "*.webp"));
        setInitialDir(fc);
        var chosen = fc.showOpenMultipleDialog(owner);
        if (chosen != null && !chosen.isEmpty()) {
            rememberDir(chosen.get(0).getParentFile());
            addFiles(chosen);
        }
    }

    private void importFolder() {
        DirectoryChooser dc = new DirectoryChooser();
        dc.setTitle("Открыть папку с картинками");
        setInitialDir(dc);
        File dir = dc.showDialog(owner);
        if (dir == null) return;
        rememberDir(dir);

        if (recursiveCheck.isSelected()) {
            try (var walk = Files.walk(dir.toPath())) {
                List<File> found = walk.filter(Files::isRegularFile)
                        .map(Path::toFile)
                        .filter(AssetPalette::isImage)
                        .sorted()
                        .collect(Collectors.toList());
                addFiles(found);
            } catch (IOException e) {
                System.err.println("Не удалось обойти папку рекурсивно: " + e.getMessage());
            }
        } else {
            File[] children = dir.listFiles();
            if (children != null) {
                java.util.Arrays.sort(children);
                addFiles(java.util.Arrays.asList(children));
            }
        }
    }

    private void setInitialDir(FileChooser fc) {
        File last = lastDir();
        if (last != null && last.isDirectory()) fc.setInitialDirectory(last);
    }

    private void setInitialDir(DirectoryChooser dc) {
        File last = lastDir();
        if (last != null && last.isDirectory()) dc.setInitialDirectory(last);
    }

    private static File lastDir() {
        String p = PREFS.get(PREF_LAST_DIR, null);
        return p == null ? null : new File(p);
    }

    private static void rememberDir(File dir) {
        if (dir != null && dir.isDirectory()) PREFS.put(PREF_LAST_DIR, dir.getAbsolutePath());
    }

    /** Adds files programmatically (e.g. auto-populating from a just-opened object's own folder). */
    public void addFiles(Iterable<File> newFiles) {
        boolean changed = false;
        for (File f : newFiles) {
            if (f.isFile() && isImage(f) && files.add(f)) changed = true;
        }
        if (changed) rebuild();
    }

    private static boolean isImage(File f) {
        String n = f.getName().toLowerCase();
        int dot = n.lastIndexOf('.');
        return dot >= 0 && IMAGE_EXT.contains(n.substring(dot + 1));
    }

    // =========================================================================
    // Filtering / rendering
    // =========================================================================

    private List<File> visibleFiles() {
        return files.stream().filter(this::matchesFilter).collect(Collectors.toList());
    }

    private boolean matchesFilter(File f) {
        String name = f.getName().toLowerCase();
        if (!searchText.isBlank() && !name.contains(searchText.toLowerCase())) return false;
        if (!selectedKeywords.isEmpty()) {
            List<String> kws = FilenameParser.keywordTokens(FilenameParser.stripExtension(f.getName()));
            for (String sel : selectedKeywords) if (!kws.contains(sel)) return false;
        }
        return true;
    }

    private void rebuild() {
        rebuildKeywordChips();
        thumbFlow.getChildren().clear();
        for (File f : files) {
            if (matchesFilter(f)) thumbFlow.getChildren().add(buildThumb(f));
        }
    }

    private void rebuildKeywordChips() {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (File f : files) {
            for (String kw : FilenameParser.keywordTokens(FilenameParser.stripExtension(f.getName()))) {
                counts.merge(kw, 1, Integer::sum);
            }
        }
        keywordFlow.getChildren().clear();
        counts.entrySet().stream()
                .sorted((a, b) -> b.getValue() - a.getValue())
                .limit(40)
                .forEach(e -> {
                    ToggleButton chip = new ToggleButton(e.getKey() + " (" + e.getValue() + ")");
                    chip.getStyleClass().add("toggle-button");
                    chip.setSelected(selectedKeywords.contains(e.getKey()));
                    chip.setOnAction(ev -> {
                        if (chip.isSelected()) selectedKeywords.add(e.getKey());
                        else selectedKeywords.remove(e.getKey());
                        rebuild();
                    });
                    keywordFlow.getChildren().add(chip);
                });
    }

    private javafx.scene.Node buildThumb(File file) {
        VBox box = new VBox(4);
        box.getStyleClass().add("asset-thumb");
        box.setAlignment(Pos.CENTER);
        box.setPrefWidth(thumbSize + 24);

        VBox imgHolder = new VBox();
        imgHolder.setAlignment(Pos.CENTER);
        imgHolder.setPrefSize(thumbSize, thumbSize);
        imgHolder.setMinSize(thumbSize, thumbSize);
        imgHolder.setMaxSize(thumbSize, thumbSize);
        thumbBg.bind(imgHolder);

        ImageView iv = new ImageView();
        try {
            iv.setImage(new Image(file.toURI().toString(), thumbSize - 12, thumbSize - 12, true, true, true));
        } catch (Exception ignored) {
            // leave blank — still draggable
        }
        iv.setFitWidth(thumbSize - 12);
        iv.setFitHeight(thumbSize - 12);
        iv.setPreserveRatio(true);
        imgHolder.getChildren().add(iv);

        Label name = new Label(file.getName());
        name.getStyleClass().add("asset-thumb-name");
        name.setMaxWidth(thumbSize + 16);
        name.setStyle("-fx-text-overflow: ellipsis;");
        Tooltip.install(box, new Tooltip(file.getName()));

        box.getChildren().addAll(imgHolder, name);

        box.setOnDragDetected(e -> {
            var db = box.startDragAndDrop(TransferMode.COPY);
            ClipboardContent content = new ClipboardContent();
            content.putFiles(List.of(file));
            if (iv.getImage() != null) db.setDragView(iv.getImage(), 20, 20);
            db.setContent(content);
            e.consume();
        });

        return box;
    }

    public void setOnAutoImportAngles(Consumer<List<File>> cb) { this.onAutoImportAngles = cb; }
    public void setOnImportStates(Consumer<List<File>> cb) { this.onImportStates = cb; }
}
