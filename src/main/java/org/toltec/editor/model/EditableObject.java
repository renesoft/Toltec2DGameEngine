package org.toltec.editor.model;

import org.toltec.unit.Direction8;
import org.toltec.unit.Gender;
import org.toltec.unit.UnitStatus;

import java.io.File;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * Everything the editor knows about one object: its identity, its category-specific
 * data (floor variants / unit clips / object clips), and — for units and objects —
 * which combo is currently "open" in the 3x3 grid (session-only, not persisted).
 */
public class EditableObject {

    public String name;
    public final ObjectCategory category;

    /** Once loaded from or saved to disk, the folder this object lives in. {@code null} for a brand-new, unsaved object. */
    public File folder;

    public boolean dirty = false;

    // ───────────────────────── Floor ─────────────────────────
    /** Keys: "N", "E", "S", "W". */
    public final Map<String, File> floorImages = new LinkedHashMap<>();
    public boolean walkable = true;
    public double speedMultiplier = 1.0;
    public double damagePerSecond = 0.0;

    // ───────────────────────── Unit ──────────────────────────
    public final Map<UnitClipKey, ClipGroup> unitClips = new LinkedHashMap<>();
    public Gender curGender = Gender.FEMALE;
    public String curWeapon = "unarmed";
    public UnitStatus curStatus = UnitStatus.IDLE;

    public double hitChance = 1.0;
    public double dodgeChance = 0.0;
    public double blockChance = 0.0;
    public double attackInterruptChance = 1.0;

    // ──────────────────────── Object ─────────────────────────
    public final Map<String, ClipGroup> objectClips = new LinkedHashMap<>();
    public String curState = "default";
    public boolean omnidirectional = false;
    public boolean objCollision = false;
    public int objLayer = 0;
    public boolean isometric = true;
    public int drawWidth = -1;
    public int drawHeight = -1;
    public int xOffset = 0;
    public int yOffset = 0;
    public boolean fitToCell = true;
    public double fitScale = 1.0;
    /** Footprint size in map cells along the col/row axes (e.g. 2x3) — see GraphicObject#footprintCols/footprintRows. Used by OBJECT category. */
    public int sizeCols = 1;
    public int sizeRows = 1;
    /** Footprint side length in map cells (e.g. 2 for "2x2", square only) — see GraphicObject#footprintCols/footprintRows. Used by UNIT category. */
    public int sizeCells = 1;

    // ─────────────── shared editing-session state ────────────
    /** Which of the 8 direction cells is currently highlighted / drives the live preview's facing. */
    public Direction8 curDirection = Direction8.S;

    public EditableObject(String name, ObjectCategory category) {
        this.name = name;
        this.category = category;
    }

    // ─────────────────────── convenience ──────────────────────

    public ClipGroup currentUnitClipGroup() {
        return unitClips.computeIfAbsent(new UnitClipKey(curGender, curWeapon, curStatus), k -> new ClipGroup());
    }

    public ClipGroup currentObjectClipGroup() {
        return objectClips.computeIfAbsent(normState(curState), k -> new ClipGroup());
    }

    /** The clip group backing whatever the 3x3 grid is currently showing — {@code null} for FLOOR. */
    public ClipGroup activeClipGroup() {
        return switch (category) {
            case UNIT -> currentUnitClipGroup();
            case OBJECT -> currentObjectClipGroup();
            case FLOOR -> null;
        };
    }

    public static String normState(String s) {
        s = s == null ? "" : s.trim();
        return s.isEmpty() ? "default" : s;
    }

    /** Weapons already used anywhere in this unit, for combo-box suggestions, in first-used order. */
    public Set<String> usedWeapons() {
        Set<String> set = new LinkedHashSet<>();
        set.add("unarmed");
        set.add("sword");
        set.add("bow");
        for (UnitClipKey k : unitClips.keySet()) set.add(k.weapon());
        return set;
    }

    /** States already used anywhere in this object, for combo-box suggestions, in first-used order. */
    public Set<String> usedStates() {
        Set<String> set = new LinkedHashSet<>();
        set.add("default");
        set.addAll(objectClips.keySet());
        return set;
    }

    public boolean isNew() { return folder == null; }

    // ─────────────────── coverage (for combo-box markers) ──────────────

    /** How many of the 8 directions have at least one frame, for {@code status} under the currently selected gender/weapon. */
    public int filledDirectionCount(UnitStatus status) {
        ClipGroup g = unitClips.get(new UnitClipKey(curGender, curWeapon, status));
        return g == null ? 0 : countFilled(g);
    }

    /** The best (most complete) direction coverage {@code weapon} has under the currently selected gender, across any status. */
    public int filledDirectionCountForWeapon(String weapon) {
        int best = 0;
        for (UnitStatus s : UnitStatus.values()) {
            ClipGroup g = unitClips.get(new UnitClipKey(curGender, weapon, s));
            if (g != null) best = Math.max(best, countFilled(g));
        }
        return best;
    }

    /** How many of the 8 directions have at least one frame for object {@code state} — omnidirectional objects are just filled/not. */
    public int filledDirectionCount(String state) {
        ClipGroup g = objectClips.get(normState(state));
        if (g == null) return 0;
        if (omnidirectional) return g.has(Direction8.S) ? Direction8.values().length : 0;
        return countFilled(g);
    }

    private static int countFilled(ClipGroup g) {
        int n = 0;
        for (Direction8 d : Direction8.values()) if (g.has(d)) n++;
        return n;
    }
}
