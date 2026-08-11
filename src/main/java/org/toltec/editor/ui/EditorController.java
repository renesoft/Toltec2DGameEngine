package org.toltec.editor.ui;

import javafx.animation.PauseTransition;
import javafx.scene.control.Alert;
import javafx.stage.Stage;
import javafx.util.Duration;
import org.toltec.unit.Direction8;
import org.toltec.unit.UnitStatus;
import org.toltec.editor.io.ObjectRepository;
import org.toltec.editor.model.ClipGroup;
import org.toltec.editor.model.EditableObject;
import org.toltec.editor.model.ObjectCategory;
import org.toltec.editor.model.UnitClipKey;
import org.toltec.editor.util.FilenameParser;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public class EditorController {

    private final Stage stage;
    private final ObjectRepository repo = new ObjectRepository();

    private final LeftPanel leftPanel;
    private final TopBar topBar;
    private final CenterGrid centerGrid;
    private final ParamsPanel paramsPanel;
    private final AssetPalette assetPalette;

    private final PauseTransition previewDebounce = new PauseTransition(Duration.millis(180));

    private EditableObject current;

    public EditorController(Stage stage, LeftPanel leftPanel, TopBar topBar, CenterGrid centerGrid,
                             ParamsPanel paramsPanel, AssetPalette assetPalette) {
        this.stage = stage;
        this.leftPanel = leftPanel;
        this.topBar = topBar;
        this.centerGrid = centerGrid;
        this.paramsPanel = paramsPanel;
        this.assetPalette = assetPalette;

        previewDebounce.setOnFinished(e -> {
            if (current != null) centerGrid.previewPane().previewService().refreshAsync(current);
        });

        wireCallbacks();
        refreshLists();
        bindNone();
    }

    private void wireCallbacks() {
        leftPanel.setOnSelect(this::onListSelect);
        leftPanel.setOnNewRequested(this::newObject);
        leftPanel.setOnCloneRequested(this::cloneCurrent);
        leftPanel.setOnDeleteRequested(this::deleteCurrent);

        topBar.setOnNameChanged(() -> { topBar.setDirty(true); updateTitle(); });
        topBar.setOnComboChanged(() -> {
            centerGrid.bind(current);
            paramsPanel.bind(current);
            topBar.setDirty(true);
            updateTitle();
            scheduleDebouncedPreview();
        });
        topBar.setOnSaveRequested(this::doSave);

        centerGrid.setOnDirectionSelected(d -> {
            if (current == null) return;
            current.curDirection = d;
            centerGrid.setSelectedDirection(d);
            scheduleDebouncedPreview();
        });
        centerGrid.setOnFramesDropped(this::onCellDrop);
        centerGrid.setOnClear(d -> applyFramesReplace(d, List.of()));
        centerGrid.setOnOpenEditor(dir -> {
            if (current == null) return;
            List<File> result = FrameListDialog.show(stage.getScene().getWindow(), directionLabel(dir), currentFrames(dir));
            if (result != null) applyFramesReplace(dir, result);
        });
        centerGrid.previewPane().setOnRotateStep(step -> {
            if (current == null) return;
            Direction8[] vals = Direction8.values();
            int idx = ((current.curDirection.ordinal() + step) % vals.length + vals.length) % vals.length;
            current.curDirection = vals[idx];
            centerGrid.setSelectedDirection(current.curDirection);
            scheduleDebouncedPreview();
        });

        assetPalette.setOnAutoImportAngles(this::autoImportAngles);
        assetPalette.setOnImportStates(this::autoImportStates);

        paramsPanel.setOnChanged(() -> { topBar.setDirty(true); updateTitle(); scheduleDebouncedPreview(); });
    }

    // =========================================================================
    // Selection / lifecycle
    // =========================================================================

    private void refreshLists() {
        for (ObjectCategory cat : ObjectCategory.values()) {
            leftPanel.setNames(cat, repo.listNames(cat));
        }
    }

    private void onListSelect(ObjectCategory cat, String name) {
        if (!confirmDiscardIfDirty()) {
            revertSelection();
            return;
        }
        try {
            EditableObject obj = repo.load(cat, name);
            setCurrent(obj);
            autoPopulatePalette(obj);
        } catch (IOException e) {
            showError("Не удалось открыть объект", e.getMessage());
        }
    }

    private void revertSelection() {
        if (current != null && current.folder != null) {
            leftPanel.selectName(current.category, current.name);
        } else {
            leftPanel.clearSelection();
        }
    }

    private void newObject(ObjectCategory cat) {
        if (!confirmDiscardIfDirty()) return;
        Optional<String> name = LeftPanel.promptName("Новый " + cat.singularRu().toLowerCase(),
                "Название нового объекта — " + cat.singularRu(), "");
        name.ifPresent(raw -> {
            String safe = ObjectRepository.sanitizeName(raw);
            if (safe.isEmpty()) return;
            boolean exists = repo.listNames(cat).stream().anyMatch(n -> n.equalsIgnoreCase(safe));
            if (exists) {
                showError("Такой объект уже есть", "Объект с именем \"" + safe + "\" уже существует в категории "
                        + cat.pluralRu().toLowerCase() + ".");
                return;
            }
            EditableObject obj = new EditableObject(safe, cat);
            obj.dirty = true;
            leftPanel.setActiveCategory(cat);
            leftPanel.clearSelection();
            setCurrent(obj);
        });
    }

    private void cloneCurrent() {
        if (current == null) return;
        if (current.dirty) {
            boolean saveFirst = LeftPanel.confirm("Сначала сохранить?",
                    "Чтобы клонировать, сначала нужно сохранить текущие изменения в \"" + current.name + "\". Сохранить сейчас?");
            if (!saveFirst || !doSave()) return;
        }
        Optional<String> name = LeftPanel.promptName("Клонировать объект", "Название копии", current.name + "_copy");
        name.ifPresent(raw -> {
            String safe = ObjectRepository.sanitizeName(raw);
            if (safe.isEmpty()) return;
            try {
                EditableObject copy = repo.cloneObject(current, safe);
                refreshLists();
                leftPanel.selectName(copy.category, copy.name);
                setCurrent(copy);
                autoPopulatePalette(copy);
            } catch (IOException e) {
                showError("Не удалось клонировать", e.getMessage());
            }
        });
    }

    private void deleteCurrent() {
        if (current == null) return;
        boolean ok = LeftPanel.confirm("Удалить объект?",
                "Удалить «" + current.name + "» без возможности восстановления?");
        if (!ok) return;
        try {
            if (current.folder != null) repo.delete(current);
            refreshLists();
            leftPanel.clearSelection();
            bindNone();
        } catch (IOException e) {
            showError("Не удалось удалить", e.getMessage());
        }
    }

    // =========================================================================
    // Public actions (menu / keyboard shortcuts)
    // =========================================================================

    /** Triggers a save exactly like clicking the Save button — for the Ctrl+S accelerator. */
    public void requestSave() {
        doSave();
    }

    private boolean doSave() {
        if (current == null) return false;
        try {
            repo.save(current);
            refreshLists();
            leftPanel.selectName(current.category, current.name);
            topBar.setDirty(false);
            topBar.refreshSuggestions();
            updateTitle();
            return true;
        } catch (IOException e) {
            showError("Не удалось сохранить", e.getMessage());
            return false;
        }
    }

    private boolean confirmDiscardIfDirty() {
        if (current == null || !current.dirty) return true;
        return LeftPanel.confirm("Несохранённые изменения",
                "В «" + current.name + "» есть несохранённые изменения. Продолжить и отменить их?");
    }

    private void setCurrent(EditableObject obj) {
        current = obj;
        topBar.bind(obj);
        centerGrid.bind(obj);
        paramsPanel.bind(obj);
        topBar.setDirty(obj != null && obj.dirty);
        updateTitle();
        if (obj != null) scheduleDebouncedPreview();
    }

    private void bindNone() {
        current = null;
        topBar.bind(null);
        centerGrid.bind(null);
        paramsPanel.bind(null);
        updateTitle();
    }

    // =========================================================================
    // Auto-import (filename-based)
    // =========================================================================

    /** "Автоимпорт углов" — assigns each file to the direction its filename seems to name, for the current combo. */
    private void autoImportAngles(List<File> files) {
        if (current == null || files.isEmpty()) return;

        Map<Direction8, List<File>> byAngle = new EnumMap<>(Direction8.class);
        int unmatched = 0;
        for (File f : files) {
            Optional<Direction8> d = FilenameParser.detectAngle(FilenameParser.stripExtension(f.getName()));
            if (d.isPresent()) byAngle.computeIfAbsent(d.get(), k -> new ArrayList<>()).add(f);
            else unmatched++;
        }
        if (byAngle.isEmpty()) {
            showInfo("Автоимпорт углов", "Не удалось распознать направление ни в одном из " + files.size() + " названий файлов.");
            return;
        }

        if (current.category == ObjectCategory.FLOOR) {
            for (var e : byAngle.entrySet()) {
                if (isCardinal(e.getKey())) current.floorImages.put(e.getKey().name(), e.getValue().get(0));
            }
        } else {
            ClipGroup group = current.activeClipGroup();
            if (group != null) {
                boolean omni = current.category == ObjectCategory.OBJECT && current.omnidirectional;
                for (var e : byAngle.entrySet()) {
                    group.setFrames(omni ? Direction8.S : e.getKey(), e.getValue());
                }
            }
        }

        afterBulkImport();
        showInfo("Автоимпорт углов", "Распределено по направлениям: " + byAngle.size()
                + (unmatched > 0 ? ("\nНе распознано: " + unmatched + " файл(ов)") : ""));
    }

    /** "Импорт состояний" — for units: detects weapon+status+angle per filename and fills in every matching combo at once. */
    private void autoImportStates(List<File> files) {
        if (current == null || current.category != ObjectCategory.UNIT || files.isEmpty()) return;

        Map<UnitClipKey, Map<Direction8, List<File>>> grouped = new LinkedHashMap<>();
        int unmatched = 0;
        for (File f : files) {
            String base = FilenameParser.stripExtension(f.getName());
            Optional<UnitStatus> status = FilenameParser.detectStatus(base);
            Optional<Direction8> angle = FilenameParser.detectAngle(base);
            if (status.isEmpty() || angle.isEmpty()) { unmatched++; continue; }
            String weapon = FilenameParser.detectWeapon(base).orElse(current.curWeapon);
            UnitClipKey key = new UnitClipKey(current.curGender, weapon, status.get());
            grouped.computeIfAbsent(key, k -> new EnumMap<>(Direction8.class))
                    .computeIfAbsent(angle.get(), k -> new ArrayList<>())
                    .add(f);
        }

        if (grouped.isEmpty()) {
            showInfo("Импорт состояний", "Не удалось распознать состояние и направление ни в одном из "
                    + files.size() + " названий файлов.");
            return;
        }

        for (var entry : grouped.entrySet()) {
            ClipGroup group = current.unitClips.computeIfAbsent(entry.getKey(), k -> new ClipGroup());
            for (var byDir : entry.getValue().entrySet()) group.setFrames(byDir.getKey(), byDir.getValue());
        }

        afterBulkImport();
        showInfo("Импорт состояний", "Заполнено комбинаций (пол/оружие/состояние): " + grouped.size()
                + (unmatched > 0 ? ("\nНе распознано: " + unmatched + " файл(ов)") : ""));
    }

    private void afterBulkImport() {
        current.dirty = true;
        centerGrid.bind(current);
        topBar.setDirty(true);
        topBar.refreshSuggestions();
        updateTitle();
        scheduleDebouncedPreview();
    }

    private void showInfo(String title, String message) {
        Alert alert = new Alert(Alert.AlertType.INFORMATION, message);
        alert.setTitle(title);
        alert.setHeaderText(title);
        alert.showAndWait();
    }

    // =========================================================================
    // Frames
    // =========================================================================

    /**
     * A drop onto a direction cell <em>adds</em> to whatever's already
     * there (so dropping frame after frame builds up an animation one file
     * at a time) — except for FLOOR, which is always exactly one static
     * image per direction, so a new drop there replaces it outright.
     * Duplicate files (the same one dropped twice) are ignored either way.
     */
    private void onCellDrop(Direction8 dir, List<File> dropped) {
        if (current == null || dropped.isEmpty()) return;

        if (current.category == ObjectCategory.FLOOR) {
            applyFramesReplace(dir, List.of(dropped.get(dropped.size() - 1)));
            return;
        }

        List<File> merged = new ArrayList<>(currentFrames(dir));
        for (File f : dropped) {
            if (!containsSamePath(merged, f)) merged.add(f);
        }
        applyFramesReplace(dir, merged);
    }

    private static boolean containsSamePath(List<File> list, File f) {
        for (File existing : list) if (existing.getAbsolutePath().equals(f.getAbsolutePath())) return true;
        return false;
    }

    private void applyFramesReplace(Direction8 dir, List<File> files) {
        if (current == null) return;

        if (current.category == ObjectCategory.FLOOR) {
            if (isCardinal(dir)) {
                if (files.isEmpty()) current.floorImages.remove(dir.name());
                else current.floorImages.put(dir.name(), files.get(0));
            }
        } else {
            ClipGroup group = current.activeClipGroup();
            Direction8 target = (current.category == ObjectCategory.OBJECT && current.omnidirectional) ? Direction8.S : dir;
            if (group != null) group.setFrames(target, files);
        }

        current.dirty = true;
        centerGrid.bind(current);
        topBar.setDirty(true);
        topBar.refreshSuggestions();
        updateTitle();
        scheduleDebouncedPreview();
    }

    private List<File> currentFrames(Direction8 dir) {
        if (current == null) return List.of();
        if (current.category == ObjectCategory.FLOOR) {
            if (!isCardinal(dir)) return List.of();
            File f = current.floorImages.get(dir.name());
            return f != null ? List.of(f) : List.of();
        }
        ClipGroup group = current.activeClipGroup();
        if (group == null) return List.of();
        Direction8 target = (current.category == ObjectCategory.OBJECT && current.omnidirectional) ? Direction8.S : dir;
        return group.peek(target);
    }

    private static boolean isCardinal(Direction8 d) {
        return d == Direction8.N || d == Direction8.E || d == Direction8.S || d == Direction8.W;
    }

    private static String directionLabel(Direction8 d) {
        return switch (d) {
            case N -> "Север"; case NE -> "Северо-восток"; case E -> "Восток"; case SE -> "Юго-восток";
            case S -> "Юг"; case SW -> "Юго-запад"; case W -> "Запад"; case NW -> "Северо-запад";
        };
    }

    // =========================================================================
    // Misc
    // =========================================================================

    private void autoPopulatePalette(EditableObject obj) {
        if (obj.folder == null) return;
        File[] children = obj.folder.listFiles();
        if (children != null) assetPalette.addFiles(Arrays.asList(children));
    }

    private void scheduleDebouncedPreview() {
        previewDebounce.playFromStart();
    }

    private void updateTitle() {
        String base = "Редактор объектов — Toltec";
        if (current != null) {
            base += "  —  " + (current.name.isBlank() ? "(без имени)" : current.name) + (current.dirty ? " *" : "");
        }
        stage.setTitle(base);
    }

    private void showError(String title, String message) {
        Alert alert = new Alert(Alert.AlertType.ERROR, message == null ? "" : message);
        alert.setTitle(title);
        alert.setHeaderText(title);
        alert.showAndWait();
    }
}
