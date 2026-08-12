package org.toltec.mapeditor.io;

import javafx.embed.swing.SwingFXUtils;
import javafx.scene.image.Image;
import org.toltec.editor.io.EditorPaths;
import org.toltec.editor.io.ObjectRepository;
import org.toltec.editor.model.ObjectCategory;
import org.toltec.mapeditor.model.PaletteEntry;
import org.toltec.render.AnimationClip;
import org.toltec.render.AssetStorage;
import org.toltec.render.ObjectTextureConfig;
import org.toltec.render.TileTextureConfig;
import org.toltec.unit.Direction8;
import org.toltec.unit.Gender;
import org.toltec.unit.UnitAnimationConfig;
import org.toltec.unit.UnitStatus;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;

/**
 * Builds the map editor's floor/unit/object catalog from whatever's actually
 * on disk, from two sources:
 * <ol>
 *   <li>the legacy flat {@code tiles.ini} / {@code units.ini} classpath
 *       resources — these are what the demo games already load, so
 *       whatever's in them shows up in the editor immediately, with no
 *       extra authoring step;</li>
 *   <li>the per-folder catalog under {@code resources/floors|units|objects/}
 *       — one folder per named asset, written by the object editor's
 *       {@link ObjectRepository}. Each folder's own {@code .ini} is loaded
 *       into a fresh, private config instance (never shared with anything
 *       else), so there's no risk of two differently-authored assets
 *       colliding just because they happen to reuse the same type/weapon/
 *       state token inside their own file.</li>
 * </ol>
 * Every returned {@link PaletteEntry} keeps a live reference to whichever
 * config instance actually resolved its art, so
 * {@link org.toltec.mapeditor.engine.MapEditorEngine} can re-resolve a fresh
 * clip/tile at paint time (e.g. for a different facing direction) without
 * this loader needing to be involved again.
 */
public class CatalogLoader {

    private final AssetStorage assets;
    private final ObjectRepository repo = new ObjectRepository();

    public CatalogLoader(AssetStorage assets) {
        this.assets = assets;
    }

    // =========================================================================
    // Floor
    // =========================================================================

    public List<PaletteEntry> loadFloors() {
        List<PaletteEntry> out = new ArrayList<>();

        // Legacy flat resource, if present.
        try {
            TileTextureConfig cfg = new TileTextureConfig(assets);
            cfg.load("tiles.ini");
            for (String type : new TreeSet<>(cfg.typeNames())) {
                out.add(PaletteEntry.floor(type, type, floorThumbnail(cfg, type), cfg, type));
            }
        } catch (IOException ignored) {
            // tiles.ini not present/readable — nothing to add from this source.
        }

        // Per-folder catalog.
        for (String name : repo.listNames(ObjectCategory.FLOOR)) {
            try {
                Path ini = resolveIni(ObjectCategory.FLOOR, name);
                TileTextureConfig cfg = new TileTextureConfig(assets);
                cfg.load(ini);
                List<String> types = new ArrayList<>(new TreeSet<>(cfg.typeNames()));
                for (String type : types) {
                    boolean disambiguate = types.size() > 1;
                    String key = disambiguate ? "floors/" + name + "#" + type : "floors/" + name;
                    String label = disambiguate ? name + " (" + type + ")" : name;
                    out.add(PaletteEntry.floor(key, label, floorThumbnail(cfg, type), cfg, type));
                }
            } catch (IOException e) {
                System.err.println("CatalogLoader: не удалось загрузить пол \"" + name + "\": " + e.getMessage());
            }
        }
        return out;
    }

    private Image floorThumbnail(TileTextureConfig cfg, String type) {
        var t = cfg.get(type);
        if (t == null) return null;
        return imageFor(t.pickVariant(0, 0));
    }

    // =========================================================================
    // Units
    // =========================================================================

    public List<PaletteEntry> loadUnits() {
        List<PaletteEntry> out = new ArrayList<>();

        // Legacy flat resource, if present.
        try {
            UnitAnimationConfig cfg = new UnitAnimationConfig(assets);
            cfg.load("units.ini");
            addUnitEntries(out, cfg, "", "");
        } catch (IOException ignored) {
            // units.ini not present/readable — nothing to add from this source.
        }

        // Per-folder catalog.
        for (String name : repo.listNames(ObjectCategory.UNIT)) {
            try {
                Path ini = resolveIni(ObjectCategory.UNIT, name);
                UnitAnimationConfig cfg = new UnitAnimationConfig(assets);
                cfg.load(ini);
                addUnitEntries(out, cfg, "units/" + name, name);
            } catch (IOException e) {
                System.err.println("CatalogLoader: не удалось загрузить юнита \"" + name + "\": " + e.getMessage());
            }
        }
        return out;
    }

    private void addUnitEntries(List<PaletteEntry> out, UnitAnimationConfig cfg,
                                 String keyPrefix, String displayBase) {
        var pairs = new TreeSet<>(java.util.Comparator.<List<String>, String>comparing(p -> p.get(0) + "/" + p.get(1)));
        pairs.addAll(cfg.genderWeaponPairs());
        boolean disambiguate = pairs.size() > 1;

        for (List<String> pair : pairs) {
            Gender gender = genderFromKey(pair.get(0));
            if (gender == null) continue;
            String weapon = pair.get(1);

            String key = keyPrefix.isEmpty()
                    ? "legacy/" + pair.get(0) + "/" + weapon
                    : (disambiguate ? keyPrefix + "#" + pair.get(0) + "/" + weapon : keyPrefix);
            String label = keyPrefix.isEmpty()
                    ? genderRu(gender) + " / " + weapon
                    : (disambiguate ? displayBase + " (" + genderRu(gender) + "/" + weapon + ")" : displayBase);

            AnimationClip clip = cfg.resolve(gender, weapon, UnitStatus.IDLE, Direction8.S);
            if (clip == null) clip = cfg.resolve(gender, weapon, UnitStatus.WALK, Direction8.S);
            Image thumb = clip != null ? imageFor(clip.frame(0)) : null;

            out.add(PaletteEntry.unit(key, label, thumb, cfg, gender, weapon));
        }
    }

    private static Gender genderFromKey(String key) {
        for (Gender g : Gender.values()) if (g.key().equals(key)) return g;
        return null;
    }

    private static String genderRu(Gender g) {
        return switch (g) {
            case MALE -> "мужчина";
            case FEMALE -> "женщина";
            case GOBLIN -> "гоблин";
        };
    }

    // =========================================================================
    // Objects
    // =========================================================================

    public List<PaletteEntry> loadObjects() {
        List<PaletteEntry> out = new ArrayList<>();

        for (String name : repo.listNames(ObjectCategory.OBJECT)) {
            try {
                Path ini = resolveIni(ObjectCategory.OBJECT, name);
                ObjectTextureConfig cfg = new ObjectTextureConfig(assets);
                cfg.load(ini);
                var states = new TreeSet<>(cfg.stateNames());
                if (states.isEmpty()) {
                    System.err.println("CatalogLoader: у объекта \"" + name + "\" нет ни одного состояния с картинкой — пропущен");
                    continue;
                }
                String state = states.first();
                AnimationClip clip = cfg.resolve(state, Direction8.S);
                Image thumb = clip != null ? imageFor(clip.frame(0)) : null;
                out.add(PaletteEntry.object("objects/" + name, name, thumb, cfg, state));
            } catch (IOException e) {
                System.err.println("CatalogLoader: не удалось загрузить объект \"" + name + "\": " + e.getMessage());
            }
        }
        return out;
    }

    // =========================================================================
    // Shared helpers
    // =========================================================================

    /** Mirrors {@link ObjectRepository#load}'s ini-finding rule: {@code <dir>/<name>.ini}, else the first .ini found. */
    private static Path resolveIni(ObjectCategory category, String name) throws IOException {
        Path dir = EditorPaths.resourcesRoot().resolve(category.folderName()).resolve(name);
        Path ini = dir.resolve(name + ".ini");
        if (Files.exists(ini)) return ini;
        try (var s = Files.list(dir)) {
            return s.filter(p -> p.toString().endsWith(".ini")).findFirst()
                    .orElseThrow(() -> new IOException("В папке \"" + dir + "\" не найден .ini файл"));
        }
    }

    private Image imageFor(String assetKey) {
        if (assetKey == null) return null;
        BufferedImage bi = assets.get(assetKey);
        if (bi == null) return null;
        return SwingFXUtils.toFXImage(bi, null);
    }
}
