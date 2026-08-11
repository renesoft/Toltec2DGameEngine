package org.toltec.editor.io;

import org.toltec.unit.Direction8;
import org.toltec.editor.model.ClipGroup;
import org.toltec.editor.model.EditableObject;
import org.toltec.editor.model.ObjectCategory;
import org.toltec.editor.model.UnitClipKey;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.UnaryOperator;
import java.util.stream.Collectors;

/**
 * All filesystem operations on editable objects: enumerating the catalog,
 * loading one back into memory, saving (which copies any newly-dropped source
 * images into the object's own folder and (re)writes its .ini), deleting, and
 * cloning.
 */
public class ObjectRepository {

    // =========================================================================
    // Listing
    // =========================================================================

    public List<String> listNames(ObjectCategory category) {
        Path dir = EditorPaths.resourcesRoot().resolve(category.folderName());
        if (!Files.isDirectory(dir)) return List.of();
        try (var stream = Files.list(dir)) {
            return stream.filter(Files::isDirectory)
                    .map(p -> p.getFileName().toString())
                    .sorted(String.CASE_INSENSITIVE_ORDER)
                    .collect(Collectors.toList());
        } catch (IOException e) {
            return List.of();
        }
    }

    // =========================================================================
    // Loading
    // =========================================================================

    public EditableObject load(ObjectCategory category, String name) throws IOException {
        Path dir = EditorPaths.resourcesRoot().resolve(category.folderName()).resolve(name);
        Path ini = dir.resolve(name + ".ini");
        if (!Files.exists(ini)) {
            try (var s = Files.list(dir)) {
                ini = s.filter(p -> p.toString().endsWith(".ini")).findFirst().orElse(ini);
            }
        }
        if (!Files.exists(ini)) {
            throw new IOException("В папке \"" + dir + "\" не найден .ini файл");
        }

        List<String> lines = Files.readAllLines(ini, StandardCharsets.UTF_8);
        EditableObject obj = new EditableObject(name, category);
        obj.folder = dir.toFile();

        switch (category) {
            case FLOOR -> IniFormat.parseFloor(obj, lines);
            case UNIT -> IniFormat.parseUnit(obj, lines);
            case OBJECT -> IniFormat.parseObject(obj, lines);
        }

        // Prime the "current" combo pickers with whatever the object actually has, so
        // opening an existing unit/object doesn't land on an empty (unauthored) combo.
        if (category == ObjectCategory.UNIT && !obj.unitClips.isEmpty()) {
            UnitClipKey first = obj.unitClips.keySet().iterator().next();
            obj.curGender = first.gender();
            obj.curWeapon = first.weapon();
            obj.curStatus = first.status();
        } else if (category == ObjectCategory.OBJECT && !obj.objectClips.isEmpty()) {
            obj.curState = obj.objectClips.keySet().iterator().next();
        }
        return obj;
    }

    // =========================================================================
    // Saving
    // =========================================================================

    /** Creates/updates the object's folder, copies any new source images into it, and (re)writes its .ini. */
    public void save(EditableObject obj) throws IOException {
        String safeName = sanitizeName(obj.name);
        if (safeName.isEmpty()) throw new IOException("Имя объекта не может быть пустым");
        obj.name = safeName;

        Path categoryDir = EditorPaths.resourcesRoot().resolve(obj.category.folderName());
        Files.createDirectories(categoryDir);
        Path targetDir = categoryDir.resolve(safeName);

        if (obj.folder != null && !obj.folder.toPath().toAbsolutePath().normalize().equals(targetDir.toAbsolutePath().normalize())) {
            if (Files.exists(targetDir)) {
                throw new IOException("Объект \"" + safeName + "\" уже существует в категории " + obj.category.pluralRu());
            }
            File oldFolder = obj.folder;
            if (oldFolder.exists()) {
                Files.move(oldFolder.toPath(), targetDir);
                remapFolder(obj, oldFolder, targetDir.toFile());
            }
        }
        Files.createDirectories(targetDir);
        File targetFile = targetDir.toFile();

        switch (obj.category) {
            case FLOOR -> copyFloorFiles(obj, targetFile);
            case UNIT -> copyUnitFiles(obj, targetFile);
            case OBJECT -> copyObjectFiles(obj, targetFile);
        }

        String ini = IniFormat.write(obj);
        Files.writeString(targetDir.resolve(safeName + ".ini"), ini, StandardCharsets.UTF_8);

        obj.folder = targetFile;
        obj.dirty = false;
    }

    private void remapFolder(EditableObject obj, File oldFolder, File newFolder) {
        UnaryOperator<File> mapper = f -> (f != null && oldFolder.equals(f.getParentFile()))
                ? new File(newFolder, f.getName()) : f;
        obj.floorImages.replaceAll((k, v) -> mapper.apply(v));
        obj.unitClips.values().forEach(g -> g.mapFiles(mapper));
        obj.objectClips.values().forEach(g -> g.mapFiles(mapper));
    }

    private void copyFloorFiles(EditableObject obj, File targetDir) throws IOException {
        String namePrefix = sanitizeToken(obj.name);
        for (String dir : List.of("N", "E", "S", "W")) {
            File f = obj.floorImages.get(dir);
            if (f != null) obj.floorImages.put(dir, copyIntoIfNeeded(f, targetDir, namePrefix + "_" + dir));
        }
    }

    private void copyUnitFiles(EditableObject obj, File targetDir) throws IOException {
        String namePrefix = sanitizeToken(obj.name);
        for (Map.Entry<UnitClipKey, ClipGroup> e : obj.unitClips.entrySet()) {
            UnitClipKey key = e.getKey();
            ClipGroup group = e.getValue();
            String base = namePrefix + "_" + sanitizeToken(key.status().key()) + "_"
                    + sanitizeToken(key.weapon()) + "_" + sanitizeToken(key.gender().key());
            for (Direction8 d : Direction8.values()) {
                relocateFrames(group, d, targetDir, base + "_" + d.key());
            }
        }
    }

    private void copyObjectFiles(EditableObject obj, File targetDir) throws IOException {
        String namePrefix = sanitizeToken(obj.name);
        for (Map.Entry<String, ClipGroup> e : obj.objectClips.entrySet()) {
            ClipGroup group = e.getValue();
            String base = namePrefix + "_" + sanitizeToken(e.getKey());
            if (obj.omnidirectional) {
                relocateFrames(group, Direction8.S, targetDir, base);
            } else {
                for (Direction8 d : Direction8.values()) {
                    relocateFrames(group, d, targetDir, base + "_" + d.key());
                }
            }
        }
    }

    private void relocateFrames(ClipGroup group, Direction8 dir, File targetDir, String baseLabel) throws IOException {
        List<File> frames = group.peek(dir);
        if (frames.isEmpty()) return;
        List<File> relocated = new ArrayList<>(frames.size());
        for (int i = 0; i < frames.size(); i++) {
            String label = frames.size() > 1 ? baseLabel + "_" + i : baseLabel;
            relocated.add(copyIntoIfNeeded(frames.get(i), targetDir, label));
        }
        group.setFrames(dir, relocated);
    }

    private File copyIntoIfNeeded(File src, File targetDir, String desiredBaseName) throws IOException {
        if (targetDir.equals(src.getParentFile())) return src; // already saved here — leave it alone
        String ext = extensionOf(src.getName());
        File dest = new File(targetDir, desiredBaseName + ext);
        int counter = 2;
        while (dest.exists()) {
            dest = new File(targetDir, desiredBaseName + "_" + counter + ext);
            counter++;
        }
        Files.copy(src.toPath(), dest.toPath(), StandardCopyOption.COPY_ATTRIBUTES);
        return dest;
    }

    private static String extensionOf(String name) {
        int i = name.lastIndexOf('.');
        return i >= 0 ? name.substring(i) : "";
    }

    // =========================================================================
    // Delete / clone
    // =========================================================================

    public void delete(EditableObject obj) throws IOException {
        if (obj.folder == null || !obj.folder.exists()) return;
        try (var walk = Files.walk(obj.folder.toPath())) {
            for (Path p : walk.sorted(Comparator.reverseOrder()).toList()) {
                Files.delete(p);
            }
        }
    }

    public EditableObject cloneObject(EditableObject src, String newName) throws IOException {
        EditableObject copy = deepCopy(src, newName);
        save(copy);
        return copy;
    }

    private EditableObject deepCopy(EditableObject src, String newName) {
        EditableObject c = new EditableObject(newName, src.category);
        c.floorImages.putAll(src.floorImages);
        c.walkable = src.walkable;
        c.speedMultiplier = src.speedMultiplier;
        c.damagePerSecond = src.damagePerSecond;

        for (var e : src.unitClips.entrySet()) c.unitClips.put(e.getKey(), e.getValue().copy());
        c.curGender = src.curGender;
        c.curWeapon = src.curWeapon;
        c.curStatus = src.curStatus;
        c.hitChance = src.hitChance;
        c.dodgeChance = src.dodgeChance;
        c.blockChance = src.blockChance;
        c.attackInterruptChance = src.attackInterruptChance;

        for (var e : src.objectClips.entrySet()) c.objectClips.put(e.getKey(), e.getValue().copy());
        c.curState = src.curState;
        c.omnidirectional = src.omnidirectional;
        c.objCollision = src.objCollision;
        c.objLayer = src.objLayer;
        c.isometric = src.isometric;
        c.drawWidth = src.drawWidth;
        c.drawHeight = src.drawHeight;
        c.xOffset = src.xOffset;
        c.yOffset = src.yOffset;
        c.fitToCell = src.fitToCell;
        c.fitScale = src.fitScale;
        c.sizeCells = src.sizeCells;
        c.sizeCols = src.sizeCols;
        c.sizeRows = src.sizeRows;

        c.curDirection = src.curDirection;
        c.folder = null;
        return c;
    }

    // =========================================================================
    // Naming
    // =========================================================================

    private static final String ILLEGAL_CHARS = "[\\\\/:*?\"<>|]+";

    public static String sanitizeName(String raw) {
        String s = raw == null ? "" : raw.trim();
        s = s.replaceAll(ILLEGAL_CHARS, "_");
        while (s.endsWith(".") || s.endsWith(" ")) s = s.substring(0, s.length() - 1);
        return s;
    }

    public static String sanitizeToken(String raw) {
        String s = sanitizeName(raw).toLowerCase(Locale.ROOT).replaceAll("\\s+", "_");
        return s.isEmpty() ? "x" : s;
    }
}
