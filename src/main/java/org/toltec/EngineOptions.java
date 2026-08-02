package org.toltec;

/**
 * Configuration for TileGameEngine.
 * Create and populate before passing to the engine constructor.
 */
public class EngineOptions {

    /** Rendering projection mode. */
    public enum ViewType {
        TOP_DOWN,
        ISOMETRIC
    }

    // ── Map ───────────────────────────────────────────────────────────────────

    /** Map width in cells. */
    public int mapWidthCells  = 20;

    /** Map height in cells. */
    public int mapHeightCells = 20;

    /**
     * Width of one cell in pixels.
     * For isometric this is the full horizontal span of the diamond tile.
     */
    public int cellWidth  = 64;

    /**
     * Height of one cell in pixels.
     * For isometric this is the full vertical span of the diamond tile (typically cellWidth / 2).
     */
    public int cellHeight = 32;

    /** Projection mode. */
    public ViewType viewType = ViewType.TOP_DOWN;

    // ── Timing ────────────────────────────────────────────────────────────────

    /** Milliseconds between game-logic ticks. */
    public long tickIntervalMs   = 100;

    /** Milliseconds between screen redraws (e.g. 16 ≈ 60 FPS). */
    public long renderIntervalMs = 16;

    // ── Scrolling ─────────────────────────────────────────────────────────────

    /** Pixel width of the edge zone that triggers automatic scrolling. */
    public int edgeScrollWidth = 20;

    /** Pixels the viewport moves per logic tick during edge scrolling. */
    public int edgeScrollSpeed = 5;
}
