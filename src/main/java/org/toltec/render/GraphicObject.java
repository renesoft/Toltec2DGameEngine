package org.toltec.render;

import org.toltec.demo.ExampleGame;
import org.toltec.demo.UnitDemoGame;
import org.toltec.engine.EngineOptions;
import org.toltec.engine.MapCell;
import org.toltec.engine.PathFinder;
import org.toltec.engine.TileGameEngine;
import org.toltec.unit.Damageable;
import org.toltec.unit.Unit;

/**
 * A drawable object that lives inside a {@link MapCell}.
 */
public class GraphicObject {

    // ── Core fields ───────────────────────────────────────────────────────────

    /** Asset key used to look up the current image (auto-updated when animating). */
    public String  imageName;

    /** Render order within the cell — lower values are drawn first (beneath others). */
    public int     layer     = 0;

    /** Whether this object blocks movement through its cell. */
    public boolean collision = false;

    /**
     * Marks this object as ground/floor. Floor objects are drawn in their own
     * pass across the whole visible map before anything else, so a floor tile
     * can never end up painted on top of a unit or prop that visually
     * overhangs into its cell — see {@link TileGameEngine#draw}.
     */
    public boolean isFloor = false;

    /** Draw width in pixels; -1 means "use asset size". */
    public int     drawWidth  = -1;

    /** Draw height in pixels; -1 means "use asset's natural height". */
    public int     drawHeight = -1;

    /** Vertical shift in pixels (positive = down, negative = up). */
    public int     yOffset    = 0;

    /**
     * Horizontal shift in pixels (positive = right, negative = left).
     * Used e.g. by {@link Unit} to slide smoothly between cells while
     * following a {@link PathFinder} path.
     */
    public int     xOffset    = 0;

    /**
     * If {@code true}, the image is proportionally scaled so that its width
     * exactly matches the cell width. {@link #fitScale} is applied afterwards.
     */
    public boolean fitToCell  = false;

    /**
     * Extra multiplier applied after {@link #fitToCell} sizing.
     * {@code 1.0} = exact cell width, {@code 2.0} = double size, etc.
     */
    public double  fitScale   = 1.0;

    /**
     * Multiplies a {@link Unit}'s movement speed while it's standing on this
     * object's cell — only meaningful for {@link #isFloor} objects (see
     * {@link MapCell#getFloorObject()} / {@link Unit#tick()}). {@code 1.0} =
     * normal speed, {@code 0.5} = half speed (e.g. mud/dirt), etc. Set from
     * {@link TileTextureConfig} for floor tiles built via
     * {@link TileTextureConfig#createFloorObject}.
     */
    public double speedMultiplier = 1.0;

    /**
     * Damage per second applied to any {@link Damageable} {@link Unit}
     * standing on this object's cell — only meaningful for {@link #isFloor}
     * objects. {@code 0} = no environmental damage. Set from
     * {@link TileTextureConfig} for floor tiles built via
     * {@link TileTextureConfig#createFloorObject}.
     */
    public double damagePerSecond = 0.0;

    /**
     * Footprint size, in map cells, along the col (east/right) and row
     * (south/down) axes — e.g. {@code footprintCols=2, footprintRows=3} for
     * an object that covers a 2-wide × 3-deep rectangle on the ground,
     * anchored at (and extending down-and-right from) the cell it's placed
     * in. {@code 0} on either (the default for both) means "unspecified",
     * which leaves rendering exactly as before: nothing about existing
     * callers (units, hand-built {@code GraphicObject}s in {@code
     * ExampleGame}/{@code UnitDemoGame}, floor tiles) changes.
     * <p>
     * A value {@code >= 1} on either opts the object into the automatic
     * isometric "drop" and (when {@link #fitToCell}) width scaling computed
     * in {@link TileGameEngine#renderCell}: bigger objects sit
     * proportionally lower and draw proportionally wider, instead of the
     * same fixed offset/size regardless of footprint — see {@code sizeCols}/
     * {@code sizeRows} on {@link org.toltec.render.ObjectTextureConfig#applyTo}.
     * <p>
     * This only affects the vertical drop and (optionally) the drawn size —
     * it does NOT make the object occupy/collide across the extra cells on
     * the map; it still lives in exactly one {@link MapCell}, the one it was
     * placed in. Multi-cell occupancy/collision isn't implemented.
     */
    public int footprintCols = 0;
    public int footprintRows = 0;

    // ── Animation state ───────────────────────────────────────────────────────

    private boolean animated           = false;
    private String  animBaseName       = "";
    private int     frameCount         = 0;
    private int     currentFrame       = 0;
    private int     frameIntervalTicks = 5;
    private int     ticksSinceChange   = 0;
    private int     frameIntervalMs    = 0;   // > 0 => fixed wall-clock frame speed, overrides ticks
    private long    lastFrameTimeNs    = -1;
    private boolean isometric          = false;

    // =========================================================================
    // Constructors
    // =========================================================================

    public GraphicObject(String imageName) {
        this.imageName = imageName;
    }

    public GraphicObject(String imageName, int layer) {
        this.imageName = imageName;
        this.layer     = layer;
    }

    public GraphicObject(String imageName, int layer, boolean collision) {
        this.imageName = imageName;
        this.layer     = layer;
        this.collision = collision;
    }

    public void setIsometricType() {
        isometric = true;
    }

    public void setIsometricType(boolean b) {
        isometric = b;
    }

    public boolean isIsometric() {
        return isometric;
    }

    // =========================================================================
    // Animation
    // =========================================================================

    public void setupAnimation(String baseName, int frameCount, int frameIntervalTicks) {
        if (frameCount <= 0) throw new IllegalArgumentException("frameCount must be > 0");
        this.animated           = true;
        this.animBaseName       = baseName;
        this.frameCount         = frameCount;
        this.frameIntervalTicks = Math.max(1, frameIntervalTicks);
        this.frameIntervalMs    = 0;
        resetAnimation();
    }

    /**
     * Same as {@link #setupAnimation(String, int, int)} but frames advance on a fixed
     * wall-clock schedule ({@code frameIntervalMs} ms per frame) instead of every N
     * logic ticks — so playback speed is independent of {@code EngineOptions#tickIntervalMs}.
     */
    public void setupAnimationMs(String baseName, int frameCount, int frameIntervalMs) {
        if (frameCount <= 0) throw new IllegalArgumentException("frameCount must be > 0");
        this.animated           = true;
        this.animBaseName       = baseName;
        this.frameCount         = frameCount;
        this.frameIntervalMs    = Math.max(1, frameIntervalMs);
        resetAnimation();
    }

    public void resetAnimation() {
        currentFrame      = 0;
        ticksSinceChange  = 0;
        lastFrameTimeNs   = -1;
        if (animated) imageName = animBaseName + "[0]";
    }

    public void tick() {
        if (!animated || frameCount <= 1) return;

        if (frameIntervalMs > 0) {
            long now = System.nanoTime();
            if (lastFrameTimeNs < 0) lastFrameTimeNs = now;
            long elapsedMs = (now - lastFrameTimeNs) / 1_000_000L;
            if (elapsedMs < frameIntervalMs) return;
            lastFrameTimeNs = now;
            currentFrame = (currentFrame + 1) % frameCount;
            imageName    = animBaseName + "[" + currentFrame + "]";
            return;
        }

        if (++ticksSinceChange >= frameIntervalTicks) {
            ticksSinceChange = 0;
            currentFrame     = (currentFrame + 1) % frameCount;
            imageName        = animBaseName + "[" + currentFrame + "]";
        }
    }

    // =========================================================================
    // Getters
    // =========================================================================

    public boolean isAnimated()     { return animated; }
    public int     getFrameCount()  { return frameCount; }
    public int     getCurrentFrame(){ return currentFrame; }
    public String  getAnimBaseName(){ return animBaseName; }
}