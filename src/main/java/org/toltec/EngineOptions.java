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

    // ── Click / drag detection ───────────────────────────────────────────────

    /**
     * Max distance in screen pixels the pointer may travel between button-down
     * and button-up for the gesture to still register as a click, for the
     * <b>right</b> button only (it doubles as camera-pan drag — see
     * {@code TileGameEngine#mouseRightUp}). Small values are the usual "some
     * jitter is OK" tolerance; a large value effectively lets you drag-then-
     * release anywhere and still get a click. The left button has no drag
     * gesture of its own, so it always registers as a click regardless of
     * this setting — see {@code TileGameEngine#mouseLeftUp}.
     */
    public int clickDragTolerancePx = 6;

    /**
     * Optional key code (e.g. {@code KeyEvent.VK_SHIFT}). While this key is
     * held down, right-button drag distance is ignored entirely and any
     * button-down + button-up counts as a click, no matter how far the
     * pointer moved in between. Set to -1 (default) to disable this override.
     * Has no effect on the left button (see {@link #clickDragTolerancePx}).
     */
    public int clickToleranceOverrideKeyCode = -1;

    // ── Unit outlines ─────────────────────────────────────────────────────────
    // Auto-drawn every frame by TileGameEngine around whichever units are
    // hovered/selected — see TileGameEngine#setSelfUnit / #setSelectedUnit.

    /** Outline colour for the unit currently under the mouse cursor (not your own — see {@link #selfHoverOutlineColor}). */
    public java.awt.Color hoverOutlineColor = new java.awt.Color(220, 40, 40); // red

    /** Outline colour used instead of {@link #hoverOutlineColor} when hovering the unit set via {@code TileGameEngine#setSelfUnit}. */
    public java.awt.Color selfHoverOutlineColor = new java.awt.Color(70, 160, 255); // blue

    /** Outline colour for the unit set via {@code TileGameEngine#setSelectedUnit} — a thin contour, not a marker underneath it. */
    public java.awt.Color selectionOutlineColor = new java.awt.Color(60, 220, 90); // green

    /** Stroke width, in pixels, used for all of the above outlines. */
    public int unitOutlineThickness = 2;

    /**
     * Alpha (0-255) a pixel must meet or exceed to count as "part of the
     * sprite" when tracing the silhouette outline (see
     * {@code TileGameEngine#drawUnitOutline}). Higher values ignore faint
     * anti-aliased edge pixels; lower values hug the art more tightly.
     */
    public int outlineAlphaThreshold = 32;

    // ── Camera zoom ───────────────────────────────────────────────────────────

    /** Smallest allowed {@code TileGameEngine#getZoom()}. */
    public double zoomMin = 0.25;

    /** Largest allowed {@code TileGameEngine#getZoom()}. */
    public double zoomMax = 4.0;

    /** Multiplier applied to the zoom level per wheel notch / +/- key press. */
    public double zoomStep = 1.1;

    /** Whether the mouse wheel zooms the camera in/out by default. */
    public boolean wheelZoomEnabled = true;

    /** Whether the +/- keys zoom the camera in/out by default. */
    public boolean keyboardZoomEnabled = true;

    // ── HUD ───────────────────────────────────────────────────────────────────

    /** Whether the engine auto-draws an FPS counter in the corner every frame. */
    public boolean showFpsCounter = true;
}
