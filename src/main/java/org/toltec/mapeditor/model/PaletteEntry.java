package org.toltec.mapeditor.model;

import javafx.scene.image.Image;
import org.toltec.editor.model.ObjectCategory;
import org.toltec.render.ObjectTextureConfig;
import org.toltec.render.TileTextureConfig;
import org.toltec.unit.Gender;
import org.toltec.unit.UnitAnimationConfig;

/**
 * One placeable item in the map editor's left-hand catalog: a floor type, a
 * unit (gender+weapon), or a decorative/interactive object. Loaded once by
 * {@link org.toltec.mapeditor.io.CatalogLoader} and then reused both for the
 * palette thumbnail and for stamping the actual thing onto the live map —
 * see {@link org.toltec.mapeditor.engine.MapEditorEngine}.
 * <p>
 * {@link #key} is what gets written into saved map files (see
 * {@link org.toltec.mapeditor.io.MapFormat}) and must be stable and unique
 * within its category — {@link org.toltec.mapeditor.io.CatalogLoader}
 * guarantees that by construction.
 */
public final class PaletteEntry {

    public final ObjectCategory category;
    /** Unique within its category — this is what's written into saved .tmap files. */
    public final String key;
    /** What the palette button shows the user — may repeat across entries (e.g. same folder, different weapon). */
    public final String displayName;
    /** May be {@code null} if no frame could be rasterised for a thumbnail. */
    public final Image thumbnail;

    // ── FLOOR ────────────────────────────────────────────────────────────────
    public TileTextureConfig floorConfig;
    public String floorType;

    // ── UNIT ─────────────────────────────────────────────────────────────────
    public UnitAnimationConfig unitConfig;
    public Gender gender;
    public String weapon;

    // ── OBJECT ───────────────────────────────────────────────────────────────
    public ObjectTextureConfig objectConfig;
    public String objectState;

    private PaletteEntry(ObjectCategory category, String key, String displayName, Image thumbnail) {
        this.category = category;
        this.key = key;
        this.displayName = displayName;
        this.thumbnail = thumbnail;
    }

    public static PaletteEntry floor(String key, String displayName, Image thumbnail,
                                      TileTextureConfig cfg, String type) {
        PaletteEntry e = new PaletteEntry(ObjectCategory.FLOOR, key, displayName, thumbnail);
        e.floorConfig = cfg;
        e.floorType = type;
        return e;
    }

    public static PaletteEntry unit(String key, String displayName, Image thumbnail,
                                     UnitAnimationConfig cfg, Gender gender, String weapon) {
        PaletteEntry e = new PaletteEntry(ObjectCategory.UNIT, key, displayName, thumbnail);
        e.unitConfig = cfg;
        e.gender = gender;
        e.weapon = weapon;
        return e;
    }

    public static PaletteEntry object(String key, String displayName, Image thumbnail,
                                       ObjectTextureConfig cfg, String state) {
        PaletteEntry e = new PaletteEntry(ObjectCategory.OBJECT, key, displayName, thumbnail);
        e.objectConfig = cfg;
        e.objectState = state;
        return e;
    }
}
