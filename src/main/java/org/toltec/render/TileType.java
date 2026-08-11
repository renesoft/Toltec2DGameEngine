package org.toltec.render;

import org.toltec.engine.MapCell;
import org.toltec.unit.Damageable;
import org.toltec.unit.Unit;

import java.util.List;

/**
 * One floor/tile texture type loaded from a {@link TileTextureConfig} —
 * e.g. "dirt", "planksBroken", "stoneInset". Carries the gameplay
 * parameters ({@link #walkable}, {@link #damagePerSecond},
 * {@link #speedMultiplier}) plus every orientation variant image asset key
 * configured for it.
 */
public final class TileType {

    public final String name;

    /** Asset keys for this type's orientation variants (however many the config gave it — 1 to 4). */
    public final List<String> variantImages;

    /** Whether a {@link Unit} can walk onto this tile (see {@link MapCell#hasCollision()}). */
    public final boolean walkable;

    /** Damage/sec applied to any {@link Damageable} unit standing on this tile. 0 = none. */
    public final double damagePerSecond;

    /** Multiplies a unit's movement speed while standing on this tile. 1.0 = normal. */
    public final double speedMultiplier;

    TileType(String name, List<String> variantImages, boolean walkable,
             double damagePerSecond, double speedMultiplier) {
        this.name            = name;
        this.variantImages   = variantImages;
        this.walkable        = walkable;
        this.damagePerSecond = damagePerSecond;
        this.speedMultiplier = speedMultiplier;
    }

    /**
     * Deterministically picks one of this type's variant images for the
     * given cell — so a large area tiled with the same type doesn't look
     * like the exact same image stamped over and over. Stable across calls
     * (same cell always picks the same variant), not random-per-frame.
     */
    public String pickVariant(int col, int row) {
        if (variantImages.isEmpty()) return null;
        int idx = Math.floorMod(col * 92821 + row * 68917, variantImages.size());
        return variantImages.get(idx);
    }
}
