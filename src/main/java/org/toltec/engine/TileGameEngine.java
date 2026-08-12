package org.toltec.engine;

import org.toltec.render.AssetStorage;
import org.toltec.render.GraphicObject;
import org.toltec.unit.Damageable;
import org.toltec.unit.Unit;

import java.awt.*;
import java.awt.event.KeyEvent;
import java.awt.event.MouseEvent;
import java.awt.geom.AffineTransform;
import java.awt.geom.Ellipse2D;
import java.awt.geom.GeneralPath;
import java.awt.geom.Rectangle2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.awt.image.VolatileImage;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tile-based 2-D game engine supporting top-down and isometric projection.
 */
public abstract class TileGameEngine {

    // =========================================================================
    // Functional listener interfaces
    // =========================================================================

    /** Fired when the user clicks on a map cell that has no {@link Unit} at the click point. */
    @FunctionalInterface
    public interface CellClickListener {
        void onCellClick(int col, int row, int button);
    }

    /**
     * Fired when the user's click actually lands on a rendered {@link Unit}'s
     * sprite — takes priority over {@link CellClickListener} for that click
     * (see {@link #mouseClick}), so game code doesn't have to re-derive "did
     * I click a unit" from column/row itself.
     */
    @FunctionalInterface
    public interface UnitClickListener {
        void onUnitClick(Unit unit, int button);
    }

    /**
     * Fired whenever the {@link Unit} under the mouse cursor changes (including
     * transitions to/from "no unit hovered", in which case {@code unit} is
     * {@code null}). Purely informational — the engine already auto-draws a
     * hover outline (see {@link EngineOptions#hoverOutlineColor}); use this if
     * game code wants to react too (tooltips, sound cues, etc).
     */
    @FunctionalInterface
    public interface UnitHoverListener {
        void onUnitHover(Unit unit);
    }

    /** Fired on keyboard press or release. */
    @FunctionalInterface
    public interface KeyEventListener {
        void onKey(int keyCode);
    }

    /** Fired on mouse-wheel scroll. */
    @FunctionalInterface
    public interface ScrollListener {
        void onScroll(int screenX, int screenY, int delta);
    }

    // =========================================================================
    // Fields
    // =========================================================================

    public final EngineOptions options;
    public final AssetStorage  assets = new AssetStorage();

    /**
     * When {@code true}, every non-floor sprite gets a debug overlay drawn on
     * top of it: a yellow outline around the image's actual on-screen
     * rectangle, a red dot at its bottom-center point, and a cyan dot at the
     * cell's own anchor point (the isometric diamond's center in isometric
     * view, the cell's center in top-down). Meant for visually checking that
     * {@link GraphicObject#yOffset}/{@link GraphicObject#footprintCols} are
     * landing where expected. Off by default — purely a diagnostic aid, no
     * effect on layout/collision/anything else.
     */
    public volatile boolean debugAnchors = false;

    /** The game map, indexed as map[row][col]. Access via {@link #getCell}. */
    protected final MapCell[][] map;

    private volatile boolean running = false;
    private volatile boolean paused  = false;

    private final Object viewLock = new Object();
    private double viewCenterX;
    private double viewCenterY;

    private volatile int canvasW = 800;
    private volatile int canvasH = 600;
    private GameCanvas canvas;

    private volatile int     mouseX          = 0;
    private volatile int     mouseY          = 0;
    private volatile boolean rightMouseDown  = false;
    private          int     lastDragX, lastDragY;

    // Click-vs-drag detection: remember where each button went down so that
    // button-up can decide, based on travelled distance, whether the gesture
    // was a click. See EngineOptions.clickDragTolerancePx /
    // clickToleranceOverrideKeyCode.
    private int               leftPressX, leftPressY;
    private int               rightPressX, rightPressY;
    private volatile boolean  clickToleranceKeyHeld = false;

    // Plain heap-allocated backbuffer, reused across frames and only
    // reallocated on resize (or on a HiDPI scale-factor change). Deliberately
    // NOT a VolatileImage: a volatile image can silently lose its contents
    // (driver/VRAM eviction) and force a partial or blank re-render, which is
    // exactly the kind of stutter/"jitter" that shows up most while scrolling.
    // A plain BufferedImage has no such loss path.
    //
    // Sized in actual DEVICE pixels (canvasW/H × the screen's HiDPI scale
    // factor, read fresh from the Graphics2D Swing hands us each frame — see
    // draw()), not just canvasW×canvasH "logical" pixels — otherwise, on any
    // display with a HiDPI/Retina scale factor above 1x, we'd render at 1x
    // and then have the OS stretch that up with nearest-neighbour/bilinear
    // filtering to fill the physical screen, which is exactly what a sudden
    // "everything looks like the resolution dropped, it's all blocky" report
    // means. bg.scale(bufferScaleX, bufferScaleY) below lets every existing
    // draw call keep working in logical canvasW×canvasH coordinates.
    private VolatileImage buffer;
    private boolean bufferAccelerated = false; // true once buffer was allocated via a real GraphicsConfiguration — see ensureBuffer
    private double bufferScaleX = 1.0, bufferScaleY = 1.0;

    // ── Camera zoom ───────────────────────────────────────────────────────────
    private volatile double zoom = 1.0;

    // ── FPS counter ───────────────────────────────────────────────────────────
    private long   fpsWindowStartNs = System.nanoTime();
    private int    fpsFrameCount    = 0;
    private volatile double fps     = 0;

    // ── Unit hit-testing (click & hover) ─────────────────────────────────────
    // Rebuilt from scratch every draw() call (render thread only) then published
    // via a fresh, unmodified list so mouse-thread reads never race a partial
    // rebuild. Order matches draw order (topmost/most-recently-drawn last),
    // so hit-testing walks it back-to-front.
    private record UnitBounds(Unit unit, String imageName, int x, int y, int w, int h) {
        boolean contains(int px, int py) {
            return px >= x && px < x + w && py >= y && py < y + h;
        }
        long centerDistSq(int px, int py) {
            long dx = (x + w / 2L) - px;
            long dy = (y + h / 2L) - py;
            return dx * dx + dy * dy;
        }
    }
    private List<UnitBounds>         buildingUnitBounds = Collections.emptyList();
    private volatile List<UnitBounds> frameUnitBounds    = Collections.emptyList();
    private volatile Unit             hoveredUnit;

    // Per-sprite-image silhouette outline, cached by asset name (stable per
    // distinct frame) — see drawUnitOutline() / silhouetteOutline().
    private final Map<String, GeneralPath> outlineCache = new ConcurrentHashMap<>();

    /** The unit treated as "yours" for hover-highlight purposes; see {@link EngineOptions#selfHoverOutlineColor}. */
    private Unit selfUnit;
    /** The unit auto-outlined as "selected"; see {@link EngineOptions#selectionOutlineColor}. */
    private Unit selectedUnit;

    private CellClickListener  cellClickListener;
    private UnitClickListener  unitClickListener;
    private UnitHoverListener  unitHoverListener;
    private KeyEventListener   keyPressedListener;
    private KeyEventListener   keyReleasedListener;
    private ScrollListener     scrollListener;

    // =========================================================================
    // Construction
    // =========================================================================

    public TileGameEngine(EngineOptions options) {
        this.options = options;

        map = new MapCell[options.mapHeightCells][options.mapWidthCells];
        for (int r = 0; r < options.mapHeightCells; r++)
            for (int c = 0; c < options.mapWidthCells; c++)
                map[r][c] = new MapCell();

        viewCenterX = (mapPixelMinX() + mapPixelMaxX()) / 2.0;
        viewCenterY = mapPixelHeight() / 2.0;
    }

    // =========================================================================
    // Lifecycle
    // =========================================================================

    public void start(GameCanvas canvas) {
        this.canvas  = canvas;
        this.canvasW = Math.max(canvas.getWidth(),  1);
        this.canvasH = Math.max(canvas.getHeight(), 1);
        running = true;
        onStart();
        startLogicThread();
        startRenderThread();
    }

    public void stop() { running = false; }

    /** Current physical canvas width in pixels, kept in sync on every resize/render pass. */
    public int getCanvasW() { return canvasW; }

    /** Current physical canvas height in pixels, kept in sync on every resize/render pass. */
    public int getCanvasH() { return canvasH; }

    /** Last known mouse X in screen pixels, updated by every {@link #mouseMove}/{@link #mouseDragged} call. */
    public int getMouseX() { return mouseX; }

    /** Last known mouse Y in screen pixels, updated by every {@link #mouseMove}/{@link #mouseDragged} call. */
    public int getMouseY() { return mouseY; }

    public void pause()       { paused = true; }
    public void resume()      { paused = false; }
    public void togglePause() { paused = !paused; }
    public boolean isPaused() { return paused; }

    // =========================================================================
    // Overridable hooks
    // =========================================================================

    protected void onStart() {}
    protected abstract void tick();
    protected void onDraw(Graphics2D gfx) {}

    // =========================================================================
    // Map access
    // =========================================================================

    public MapCell getCell(int col, int row) {
        if (!isCellValid(col, row)) return null;
        return map[row][col];
    }

    public boolean isCellValid(int col, int row) {
        return col >= 0 && col < options.mapWidthCells &&
                row >= 0 && row < options.mapHeightCells;
    }

    // =========================================================================
    // Unit hit-testing / hover / selection
    // =========================================================================

    /**
     * Marks {@code unit} as "yours" — purely a rendering hint used to pick
     * {@link EngineOptions#selfHoverOutlineColor} over
     * {@link EngineOptions#hoverOutlineColor} when it's the one under the
     * cursor. Pass {@code null} to clear.
     */
    public void setSelfUnit(Unit unit) { selfUnit = unit; }
    public Unit getSelfUnit()          { return selfUnit; }

    /**
     * Marks {@code unit} as "selected" — the engine auto-draws a thin outline
     * around it every frame using {@link EngineOptions#selectionOutlineColor}
     * / {@link EngineOptions#unitOutlineThickness}. Pass {@code null} to clear
     * (e.g. on deselect).
     */
    public void setSelectedUnit(Unit unit) { selectedUnit = unit; }
    public Unit getSelectedUnit()          { return selectedUnit; }

    /** The {@link Unit} currently under the mouse cursor, or {@code null}. Updated once per rendered frame. */
    public Unit getHoveredUnit() { return hoveredUnit; }

    /**
     * The topmost rendered {@link Unit} whose on-screen sprite covers
     * ({@code sx},{@code sy}), or {@code null}. Uses each unit's bounds from
     * the most recently rendered frame — see {@link UnitBounds}.
     */
    public Unit unitAt(int sx, int sy) {
        /*List<UnitBounds> bounds = frameUnitBounds;
        for (int i = bounds.size() - 1; i >= 0; i--) {
            UnitBounds b = bounds.get(i);
            if (b.contains(sx, sy)) return b.unit();
        }
        return null;*/
        return bestUnitAt(sx, sy, null);
    }
    private Unit bestUnitAt(int sx, int sy, Unit exclude) {
        List<UnitBounds> bounds = frameUnitBounds;
        UnitBounds best = null;
        long bestDistSq = Long.MAX_VALUE;
        for (UnitBounds b : bounds) {
            if (b.unit() == exclude) continue;
            if (!b.contains(sx, sy)) continue;
            long d = b.centerDistSq(sx, sy);
            if (d < bestDistSq) {
                bestDistSq = d;
                best = b;
            }
        }
        return best == null ? null : best.unit();
    }

    /**
     * Draws an outline around {@code unit}'s most-recently-rendered sprite:
     * traces the actual silhouette of its non-transparent pixels (see
     * {@link #silhouetteOutline}) if it was drawn from an image this frame,
     * or falls back to a padded rounded rectangle (e.g. for the placeholder
     * diamond when no art is configured yet). No-op if the unit wasn't drawn
     * this frame (off-map, or outside the viewport). Exposed for game code
     * that wants extra outlines beyond the automatic hover/selection ones
     * (e.g. highlighting valid attack targets).
     */
    public void drawUnitOutline(Graphics2D gfx, Unit unit, Color color, int thickness) {
        if (unit == null || color == null) return;
        UnitBounds bounds = findUnitBounds(unit);
        if (bounds == null) return;

        Shape outline = outlineShapeFor(bounds);

        Color  oldColor  = gfx.getColor();
        Stroke oldStroke = gfx.getStroke();
        gfx.setColor(color);
        gfx.setStroke(new BasicStroke(Math.max(1, thickness), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        gfx.draw(outline);
        gfx.setStroke(oldStroke);
        gfx.setColor(oldColor);
    }

    private UnitBounds findUnitBounds(Unit unit) {
        List<UnitBounds> list = frameUnitBounds;
        for (int i = list.size() - 1; i >= 0; i--)
            if (list.get(i).unit() == unit) return list.get(i);
        return null;
    }

    /**
     * The shape to stroke for a unit's outline. If the unit was drawn from a
     * real sprite image this frame, this is that image's traced silhouette
     * (ignoring transparent padding), transformed to the frame's actual
     * screen position/size — so it hugs the character, not its bounding box.
     * Falls back to a padded rounded rectangle when there's no source image
     * (the engine's placeholder diamond/box).
     */
    private Shape outlineShapeFor(UnitBounds bounds) {
        BufferedImage img = bounds.imageName() != null ? assets.get(bounds.imageName()) : null;
        if (img == null || img.getWidth() <= 0 || img.getHeight() <= 0) {
            int pad = 3;
            return new RoundRectangle2D.Float(
                    bounds.x() - pad, bounds.y() - pad, bounds.w() + pad * 2, bounds.h() + pad * 2, 10, 10);
        }

        GeneralPath silhouette = silhouetteOutline(bounds.imageName(), img);
        AffineTransform at = new AffineTransform();
        at.translate(bounds.x(), bounds.y());
        at.scale(bounds.w() / (double) img.getWidth(), bounds.h() / (double) img.getHeight());
        return at.createTransformedShape(silhouette);
    }

    /**
     * Traces the boundary between opaque and transparent pixels of
     * {@code img}, in the image's own native pixel coordinates, and caches
     * the result under {@code cacheKey} (the resolved asset name — stable
     * per distinct sprite frame, so this only ever runs once per frame the
     * game actually uses, however many times/units draw it).
     * <p>
     * Walks every opaque pixel and, for each side that touches a transparent
     * pixel (or the image's edge), emits that one-pixel-long edge as its own
     * disjoint segment into a {@link GeneralPath} — the practical, iterative
     * equivalent of a recursive border walk (no stack-depth risk on big
     * sprites, and cost scales with the silhouette's perimeter, not its
     * area, so it's cheap even uncached). Graphics2D happily strokes a path
     * made of many disjoint segments in one draw() call, which together
     * trace exactly the character's outline and nothing in its transparent
     * padding.
     */
    private GeneralPath silhouetteOutline(String cacheKey, BufferedImage img) {
        GeneralPath cached = outlineCache.get(cacheKey);
        if (cached != null) return cached;

        int w = img.getWidth(), h = img.getHeight();
        int threshold = options.outlineAlphaThreshold;
        boolean[][] opaque = new boolean[w][h];
        for (int y = 0; y < h; y++)
            for (int x = 0; x < w; x++)
                opaque[x][y] = (img.getRGB(x, y) >>> 24) >= threshold;

        GeneralPath path = new GeneralPath();
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                if (!opaque[x][y]) continue;
                if (x == 0     || !opaque[x - 1][y]) { path.moveTo(x,     y);     path.lineTo(x,     y + 1); }
                if (x == w - 1 || !opaque[x + 1][y]) { path.moveTo(x + 1, y);     path.lineTo(x + 1, y + 1); }
                if (y == 0     || !opaque[x][y - 1]) { path.moveTo(x,     y);     path.lineTo(x + 1, y);     }
                if (y == h - 1 || !opaque[x][y + 1]) { path.moveTo(x,     y + 1); path.lineTo(x + 1, y + 1); }
            }
        }

        outlineCache.put(cacheKey, path);
        return path;
    }

    /** Draws the automatic selection/hover outlines, in that order (selection under hover). */
    private void drawAutoOutlines(Graphics2D gfx) {
        if (selectedUnit != null)
            drawUnitOutline(gfx, selectedUnit, options.selectionOutlineColor, options.unitOutlineThickness);

        Unit hovered = hoveredUnit;
        if (hovered != null && hovered != selectedUnit) {
            Color c = (hovered == selfUnit) ? options.selfHoverOutlineColor : options.hoverOutlineColor;
            drawUnitOutline(gfx, hovered, c, options.unitOutlineThickness);
        }
    }

    /** Recomputes {@link #hoveredUnit} from the current mouse position and fires {@link #unitHoverListener} on change. */
    private void updateHoveredUnit() {
        Unit newHovered = unitAt(mouseX, mouseY);
        if (newHovered != hoveredUnit) {
            hoveredUnit = newHovered;
            if (unitHoverListener != null) unitHoverListener.onUnitHover(newHovered);
        }
    }

    // =========================================================================
    // Viewport control
    // =========================================================================

    public void scrollBy(double dx, double dy) {
        synchronized (viewLock) {
            viewCenterX = clampViewX(viewCenterX + dx);
            viewCenterY = clampViewY(viewCenterY + dy);
        }
    }

    public void setCenter(double mapPxX, double mapPxY) {
        synchronized (viewLock) {
            viewCenterX = clampViewX(mapPxX);
            viewCenterY = clampViewY(mapPxY);
        }
    }

    public void setCenterToCell(int col, int row) {
        double[] mp = cellToMapPixel(col, row);
        setCenter(mp[0] + options.cellWidth  / 2.0,
                mp[1] + options.cellHeight / 2.0);
    }

    public double getViewCenterX() { synchronized (viewLock) { return viewCenterX; } }
    public double getViewCenterY() { synchronized (viewLock) { return viewCenterY; } }

    private double clampViewX(double x) {
        return Math.max(mapPixelMinX(), Math.min(mapPixelMaxX(), x));
    }
    private double clampViewY(double y) {
        return Math.max(0, Math.min(mapPixelHeight(), y));
    }

    // =========================================================================
    // Camera zoom
    // =========================================================================

    /** Current zoom factor: 1.0 = native size, >1 = zoomed in, <1 = zoomed out. */
    public double getZoom() { return zoom; }

    /** Sets the zoom factor directly, clamped to {@link EngineOptions#zoomMin}/{@link EngineOptions#zoomMax}. */
    public void setZoom(double z) {
        if (Double.isNaN(z) || Double.isInfinite(z)) return;
        zoom = Math.max(options.zoomMin, Math.min(options.zoomMax, z));
    }

    /** Multiplies the current zoom by {@code factor} (e.g. {@code options.zoomStep} to zoom in one notch). */
    public void zoomBy(double factor) { setZoom(zoom * factor); }

    /** Mouse wheel and +/- keys drive this automatically by default — see {@link EngineOptions#wheelZoomEnabled} / {@link EngineOptions#keyboardZoomEnabled}. */
    public void resetZoom() { zoom = 1.0; }

    // =========================================================================
    // Coordinate conversions
    // =========================================================================

    private int[] mapPixelToScreen(double mpx, double mpy) {
        double cx, cy;
        synchronized (viewLock) { cx = viewCenterX; cy = viewCenterY; }
        double z = zoom;
        return new int[]{
                (int) Math.round((mpx - cx) * z) + canvasW / 2,
                (int) Math.round((mpy - cy) * z) + canvasH / 2
        };
    }

    private double[] screenToMapPixel(int sx, int sy) {
        double cx, cy;
        synchronized (viewLock) { cx = viewCenterX; cy = viewCenterY; }
        double z = zoom;
        return new double[]{(sx - canvasW / 2.0) / z + cx, (sy - canvasH / 2.0) / z + cy};
    }

    private double[] cellToMapPixel(int col, int row) {
        if (options.viewType == EngineOptions.ViewType.ISOMETRIC) {
            return new double[]{
                    (col - row) * (options.cellWidth  / 2.0),
                    (col + row) * (options.cellHeight / 2.0)
            };
        }
        return new double[]{col * (double) options.cellWidth,
                row * (double) options.cellHeight};
    }

    /**
     * Inverse of {@link #renderTopDown}/{@link #renderIsometric}'s cell
     * placement — deliberately does NOT go through the continuous
     * {@link #screenToMapPixel} (which divides by the raw {@code zoom}
     * double). The renderers place cells on an integer grid anchored at
     * {@code mapPixelToScreen(0,0)} with a *rounded* per-cell step
     * ({@code screenCw = round(cellWidth * zoom)}) specifically to avoid
     * sub-pixel gaps between neighbouring tiles (see the comment in
     * renderTopDown). If picking used the unrounded continuous math instead,
     * the two would drift apart by a growing number of pixels the further a
     * cell sits from the anchor — harmless-looking off-by-one-cell clicks
     * near the edges of any reasonably large map. Mirroring the exact same
     * rounded-step grid here keeps "what you see" and "what you click"
     * pixel-identical everywhere on screen, at any zoom.
     */
    public int[] screenToCell(int sx, int sy) {
        int screenCw = (int) Math.round(options.cellWidth  * zoom);
        int screenCh = (int) Math.round(options.cellHeight * zoom);
        if (screenCw <= 0) screenCw = 1;
        if (screenCh <= 0) screenCh = 1;
        int[] origin = mapPixelToScreen(0, 0);

        int col, row;
        if (options.viewType == EngineOptions.ViewType.ISOMETRIC) {
            int halfW = Math.max(1, screenCw / 2);
            int halfH = Math.max(1, screenCh / 2);
            double a = (sx - origin[0]) / (double) halfW; // = col - row
            double b = (sy - origin[1]) / (double) halfH; // = col + row
            col = (int) Math.floor((a + b) / 2.0);
            row = (int) Math.floor((b - a) / 2.0);
        } else {
            col = Math.floorDiv(sx - origin[0], screenCw);
            row = Math.floorDiv(sy - origin[1], screenCh);
        }
        if (!isCellValid(col, row)) return new int[]{-1, -1};
        return new int[]{col, row};
    }

    /**
     * Screen-space pixel position of a cell's anchor point (the same point
     * {@link #renderCell} uses as its origin) at the current viewport.
     * Handy for HUD overlays — selection rings, health bars, etc.
     */
    public int[] cellToScreen(int col, int row) {
        double[] mp = cellToMapPixel(col, row);
        return mapPixelToScreen(mp[0], mp[1]);
    }

    /**
     * Screen-space pixel delta between the anchor points of two cells, in
     * the engine's current projection. Independent of viewport position, so
     * it's safe to use for interpolating an object's motion between cells
     * — see {@link Unit}'s path-following.
     */
    public double[] cellScreenDelta(int fromCol, int fromRow, int toCol, int toRow) {
        double[] from = cellToMapPixel(fromCol, fromRow);
        double[] to   = cellToMapPixel(toCol, toRow);
        return new double[]{to[0] - from[0], to[1] - from[1]};
    }

    private double mapPixelWidth() {
        if (options.viewType == EngineOptions.ViewType.ISOMETRIC)
            return (options.mapWidthCells + options.mapHeightCells) * (options.cellWidth / 2.0);
        return options.mapWidthCells * (double) options.cellWidth;
    }

    private double mapPixelHeight() {
        if (options.viewType == EngineOptions.ViewType.ISOMETRIC)
            return (options.mapWidthCells + options.mapHeightCells) * (options.cellHeight / 2.0);
        return options.mapHeightCells * (double) options.cellHeight;
    }

    /**
     * Left/right map-pixel bounds the camera center is allowed to reach.
     * For TOP_DOWN this is simply [0, mapPixelWidth()] — cellToMapPixel's X
     * already starts at 0. For ISOMETRIC, cellToMapPixel's X = (col-row)*cw/2
     * ranges from -(mapHeightCells-1)*cw/2 (col=0, row=max — the map's west
     * corner) to +(mapWidthCells-1)*cw/2 + cellWidth (col=max, row=0 — the
     * east corner, plus one full cell width since that's where the cell's
     * own rendered box ends, not just its anchor point). mapPixelWidth() is
     * only the *span* of that range, not its lower bound — clamping to
     * [0, mapPixelWidth()] as if it were [minX, maxX] silently cut off the
     * entire negative-X half of the diamond, which is why the west/
     * south-west corner was permanently unreachable no matter the zoom
     * level (see clampViewX).
     * <p>
     * Y is unaffected: cellToMapPixel's Y = (col+row)*ch/2 already starts at
     * 0 (col=0,row=0) for both view types, so [0, mapPixelHeight()] was
     * already the correct range — only X needed splitting into a real
     * min/max.
     */
    private double mapPixelMinX() {
        if (options.viewType == EngineOptions.ViewType.ISOMETRIC)
            return -(options.mapHeightCells - 1) * (options.cellWidth / 2.0);
        return 0;
    }

    private double mapPixelMaxX() {
        if (options.viewType == EngineOptions.ViewType.ISOMETRIC)
            return (options.mapWidthCells - 1) * (options.cellWidth / 2.0) + options.cellWidth;
        return options.mapWidthCells * (double) options.cellWidth;
    }

    // =========================================================================
    // Thread management
    // =========================================================================

    private void startLogicThread() {
        Thread t = new Thread(() -> {
            long lastNs = System.nanoTime();
            long tickNs = options.tickIntervalMs * 1_000_000L;
            long acc    = 0;

            while (running) {
                long now = System.nanoTime();
                acc     += now - lastNs;
                lastNs   = now;

                while (acc >= tickNs) {
                    acc -= tickNs;
                    if (!paused) {
                        updateEdgeScroll();
                        tickAllCells();
                        tick();
                    }
                }
                sleep(1);
            }
        }, "GameLogicThread");
        t.setDaemon(true);
        t.start();
    }

    private void startRenderThread() {
        Thread t = new Thread(() -> {
            long renderNs = options.renderIntervalMs * 1_000_000L;
            long lastNs   = System.nanoTime();

            while (running) {
                long now = System.nanoTime();
                if (now - lastNs >= renderNs) {
                    lastNs = now;
                    if (canvas != null) canvas.repaint();
                }
                sleep(Math.max(1L, options.renderIntervalMs / 4));
            }
        }, "GameRenderThread");
        t.setDaemon(true);
        t.start();
    }

    private void tickAllCells() {
        for (int r = 0; r < options.mapHeightCells; r++)
            for (int c = 0; c < options.mapWidthCells; c++)
                map[r][c].tick();
    }

    private static void sleep(long ms) {
        try { Thread.sleep(ms); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }

    // =========================================================================
    // Edge scrolling
    // =========================================================================

    private void updateEdgeScroll() {
        int edge  = options.edgeScrollWidth;
        int speed = options.edgeScrollSpeed;
        int mx = mouseX, my = mouseY;
        int cw = canvasW, ch = canvasH;

        double dx = 0, dy = 0;
        if (mx < edge)        dx = -speed;
        if (mx > cw - edge)   dx =  speed;
        if (my < edge)        dy = -speed;
        if (my > ch - edge)   dy =  speed;

        // Same reasoning as mouseDragged(): scrollBy() works in map-pixel space, so
        // divide by zoom to keep the on-screen edge-scroll speed constant at any zoom level.
        if (dx != 0 || dy != 0) scrollBy(dx / zoom, dy / zoom);
    }

    // =========================================================================
    // Rendering
    // =========================================================================

    public void draw(Graphics2D gfx) {
        if (canvas != null) {
            canvasW = Math.max(canvas.getWidth(),  1);
            canvasH = Math.max(canvas.getHeight(), 1);
        }

        ensureBuffer(gfx);

        Graphics2D bg = buffer.createGraphics();
        try {
            // Render in logical canvasW×canvasH coordinates as always — the
            // buffer itself is allocated at full device-pixel resolution (see
            // ensureBuffer), so this scale is what actually makes everything
            // render crisp instead of blurry/blocky on HiDPI/Retina displays.
            bg.scale(bufferScaleX, bufferScaleY);

            bg.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                    RenderingHints.VALUE_ANTIALIAS_OFF);
            bg.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                    RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);

            bg.setColor(Color.BLACK);
            bg.fillRect(0, 0, canvasW, canvasH);

            buildingUnitBounds = new ArrayList<>();

            // Two full passes over the (culled) visible map: every floor tile
            // first, then everything else on top, painter's-algorithm sorted.
            // Interleaving them per-cell (the old approach) let a floor tile
            // in a cell rendered later in depth order paint over the top of a
            // unit sprite from an earlier cell that visually overhangs into
            // it (tall sprites extend above their own tile) — splitting into
            // two passes guarantees no floor tile can ever end up on top of
            // any unit/prop, regardless of depth order.
            if (options.viewType == EngineOptions.ViewType.ISOMETRIC) {
                renderIsometric(bg, true);
                renderIsometric(bg, false);
            } else {
                renderTopDown(bg, true);
                renderTopDown(bg, false);
            }

            frameUnitBounds = buildingUnitBounds; // publish this frame's hit-test data
            updateHoveredUnit();
            drawAutoOutlines(bg);

            onDraw(bg);

            updateFps();
            if (options.showFpsCounter) drawFpsCounter(bg);

        } finally {
            bg.dispose();
        }

        // Buffer is at device-pixel resolution; draw it back down to logical
        // canvasW×canvasH — the caller's own HiDPI transform re-expands that
        // to fill the physical screen, landing back at ~1 buffer pixel per
        // device pixel with no extra blur/softening.
        Object oldInterp = gfx.getRenderingHint(RenderingHints.KEY_INTERPOLATION);
        gfx.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
        gfx.drawImage(buffer, 0, 0, canvasW, canvasH, null);
        if (oldInterp != null) gfx.setRenderingHint(RenderingHints.KEY_INTERPOLATION, oldInterp);
    }

    /**
     * Sizes (and, on first use or a resize/scale change, reallocates) the
     * backbuffer at actual device-pixel resolution, reading the current
     * HiDPI scale factor straight off the Graphics2D Swing/AWT handed us for
     * this paint — the only place that scale factor is reliably available,
     * and it can differ between monitors on a multi-display setup, so it's
     * re-read every frame rather than cached at startup.
     */
    private void ensureBuffer(Graphics2D screenGfx) {
        AffineTransform tx = screenGfx.getTransform();
        double sx = tx.getScaleX();
        double sy = tx.getScaleY();
        if (!(sx > 0)) sx = 1.0;
        if (!(sy > 0)) sy = 1.0;

        int physW = Math.max(1, (int) Math.ceil(canvasW * sx));
        int physH = Math.max(1, (int) Math.ceil(canvasH * sy));

        GraphicsConfiguration gcNow = canvas != null ? canvas.getGraphicsConfiguration() : null;
        // Reallocate not just on a size change but also the first time the
        // canvas's GraphicsConfiguration becomes available — the very first
        // frame or two can arrive before the canvas is actually showing
        // (gc == null), forcing the software fallback below; once it's
        // showing we want to swap up to the accelerated buffer rather than
        // being stuck on the fallback for the rest of the run.
        if (buffer == null || buffer.getWidth() != physW || buffer.getHeight() != physH
                || (!bufferAccelerated && gcNow != null)) {
            // IMPORTANT for GPU acceleration (see GpuAcceleration): a plain
            // `new BufferedImage(...)` is always an unmanaged/"custom"
            // raster, and Java2D's OpenGL/Direct3D pipelines can only
            // hardware-blit *managed* images tied to a GraphicsConfiguration
            // — an unmanaged buffer is rasterized on the CPU no matter which
            // pipeline is active. Allocating through the canvas's own
            // GraphicsConfiguration (available once it's showing on screen)
            // is what actually lets every drawImage() onto this buffer run
            // on the GPU; every hundreds-of-tiles-per-frame draw in
            // renderTopDown/renderIsometric targets this buffer, so this is
            // the backbuffer half of getting real acceleration — the other
            // half is loading sprites the same way, see
            // AssetStorage#accelerate.
            try {
                buffer = gcNow != null
                        ? gcNow.createCompatibleVolatileImage(physW, physH, new ImageCapabilities(true)) : null;
                //? gcNow.createCompatibleImage(physW, physH, Transparency.OPAQUE)
                //: new BufferedImage(physW, physH, BufferedImage.TYPE_INT_RGB); // canvas not yet showing — fall back, retried next frame
            } catch (AWTException e) {
                throw new RuntimeException(e);
            }
            bufferAccelerated = gcNow != null;
        }
        bufferScaleX = sx;
        bufferScaleY = sy;
    }

    /** Updates the twice-a-second FPS estimate shown by {@link #drawFpsCounter} / returned by {@link #getFps()}. */
    private void updateFps() {
        fpsFrameCount++;
        long now = System.nanoTime();
        long elapsed = now - fpsWindowStartNs;
        if (elapsed >= 500_000_000L) { // refresh twice a second, steadier to read than every frame
            fps = fpsFrameCount * 1_000_000_000.0 / elapsed;
            fpsFrameCount = 0;
            fpsWindowStartNs = now;
        }
    }

    /** Current measured frames-per-second (updated twice a second). */
    public double getFps() { return fps; }

    private void drawFpsCounter(Graphics2D gfx) {
        String text = String.format("FPS: %.0f", fps);

        Font  oldFont  = gfx.getFont();
        Color oldColor = gfx.getColor();

        gfx.setFont(new Font("Monospaced", Font.BOLD, 13));
        FontMetrics fm = gfx.getFontMetrics();
        int textW = fm.stringWidth(text);
        int x = canvasW - textW - 10;
        int y = 8 + fm.getAscent();

        gfx.setColor(new Color(0, 0, 0, 150));
        gfx.fillRect(x - 5, 4, textW + 10, fm.getHeight() + 6);
        gfx.setColor(new Color(80, 230, 100));
        gfx.drawString(text, x, y);

        gfx.setFont(oldFont);
        gfx.setColor(oldColor);
    }

    // ── Top-down pass ─────────────────────────────────────────────────────────

    private void renderTopDown(Graphics2D gfx, boolean floorPass) {
        int nativeCw = options.cellWidth, nativeCh = options.cellHeight;
        int screenCw = (int) Math.round(nativeCw * zoom);
        int screenCh = (int) Math.round(nativeCh * zoom);

        double[] tl = screenToMapPixel(0, 0);
        double[] br = screenToMapPixel(canvasW, canvasH);

        int colMin = Math.max(0, (int) Math.floor(tl[0] / nativeCw) - 1);
        int colMax = Math.min(options.mapWidthCells  - 1, (int) Math.ceil(br[0] / nativeCw));
        int rowMin = Math.max(0, (int) Math.floor(tl[1] / nativeCh) - 1);
        int rowMax = Math.min(options.mapHeightCells - 1, (int) Math.ceil(br[1] / nativeCh));

        // Anchor: screen position of map-pixel (0,0), rounded exactly once for
        // the whole frame. Every cell's screen position below is then reached
        // by pure integer addition from this single anchor (colMin*screenCw,
        // rowMin*screenCh, ...) instead of independently re-rounding
        // (col*nativeCw - viewCenterX) * zoom for every single cell.
        //
        // The old per-cell rounding is why gaps appeared: two neighbouring
        // cells' positions could each round to the *nearest* pixel in
        // opposite directions (e.g. col rounds down, col+1 rounds up),
        // leaving a 1px sliver between them that the fixed-width screenCw
        // draw call doesn't cover. That sliver's position depends on the
        // fractional part of viewCenterX/Y, which drifts continuously while
        // scrolling (hence gaps only during motion) and also shifts whenever
        // zoom changes the fractional alignment (hence gaps when zooming).
        // Building every column/row from one shared anchor with a constant
        // per-step increment guarantees adjacent tiles always share an exact
        // pixel edge, at rest or in motion, at any zoom.
        int[] origin = mapPixelToScreen(0, 0);
        int originX = origin[0] + colMin * screenCw;

        for (int row = rowMin; row <= rowMax; row++) {
            int sy = origin[1] + row * screenCh;
            int sx = originX;
            for (int col = colMin; col <= colMax; col++) {
                renderCell(gfx, map[row][col], sx, sy, screenCw, screenCh, col, row, floorPass);
                sx += screenCw;
            }
        }
    }

    // ── Isometric pass (painter's algorithm) ─────────────────────────────────

    private void renderIsometric(Graphics2D gfx, boolean floorPass) {
        int mw = options.mapWidthCells, mh = options.mapHeightCells;
        int screenCw = (int) Math.round(options.cellWidth  * zoom);
        int screenCh = (int) Math.round(options.cellHeight * zoom);

        // Same anchor-plus-integer-step approach as renderTopDown (see the
        // comment there) — halfW/halfH are the exact per-column/per-row screen
        // deltas used for every cell this frame, so (col,row) and its
        // neighbours can never disagree on where their shared diamond edge
        // sits, regardless of how viewCenterX/Y's fractional part drifts.
        int halfW = screenCw / 2;
        int halfH = screenCh / 2;
        int[] origin = mapPixelToScreen(0, 0);

        for (int depth = 0; depth < mw + mh - 1; depth++) {
            int colStart = Math.max(0, depth - mh + 1);
            int colEnd   = Math.min(mw - 1, depth);

            for (int col = colStart; col <= colEnd; col++) {
                int row = depth - col;
                int sx = origin[0] + (col - row) * halfW;
                int sy = origin[1] + (col + row) * halfH;

                if (sx + screenCw < 0 || sx > canvasW + screenCw) continue;
                if (sy + screenCh < 0 || sy > canvasH + screenCh) continue;

                renderCell(gfx, map[row][col], sx, sy, screenCw, screenCh, col, row, floorPass);
            }
        }
    }

    // ── Single cell ───────────────────────────────────────────────────────────

    /**
     * Extra vertical drop for a multi-cell "footprint" object — see {@link
     * GraphicObject#footprintCols}/{@link GraphicObject#footprintRows}.
     * Returns {@code 0} (no change to rendering at all) for the vast
     * majority of objects that never set either field, which is exactly
     * what keeps this backward compatible with every existing caller.
     * {@code ch} is already the on-screen, zoom-scaled cell height, so the
     * drop scales with zoom the same way the sprite itself does.
     */
    private static int footprintDrop(GraphicObject obj, int ch) {
        double factor = footprintFactor(obj);
        return factor > 0 ? (int) Math.round(factor * (ch / 2.0)) : 0;
    }

    /**
     * Width multiplier for a multi-cell footprint, for use when {@link
     * GraphicObject#fitToCell} is on: a footprint's isometric ground diamond
     * is {@code (footprintCols + footprintRows) / 2} times as wide as a
     * single 1×1 cell's, so scaling {@code fitToCell}'s "fill one cell
     * width" target by that same factor makes the sprite visually cover
     * its whole footprint instead of being squeezed down to one cell
     * regardless of how big the object actually is. Returns {@code 1.0}
     * (no change) when no footprint is set — see {@link #footprintDrop}.
     */
    private static double footprintWidthFactor(GraphicObject obj) {
        double factor = footprintFactor(obj);
        return factor > 0 ? factor : 1.0;
    }

    /** {@code (footprintCols + footprintRows) / 2.0}, or {@code 0} if neither is set (see {@link #footprintDrop}). */
    private static double footprintFactor(GraphicObject obj) {
        if (obj.footprintCols <= 0 && obj.footprintRows <= 0) return 0;
        int cols = Math.max(1, obj.footprintCols);
        int rows = Math.max(1, obj.footprintRows);
        return (cols + rows) / 2.0;
    }

    private static final Color DEBUG_RECT_COLOR      = new Color(255, 220, 0, 230);
    private static final Color DEBUG_IMG_BOTTOM_COLOR = new Color(255, 60, 60, 240);
    private static final Color DEBUG_CELL_MID_COLOR   = new Color(0, 220, 255, 240);
    private static final double DEBUG_DOT_R = 3.5;

    /**
     * Debug overlay (see {@link #debugAnchors}): outlines the sprite's actual
     * drawn rectangle in yellow and marks its bottom-center point in red.
     * Draws in whatever coordinate system {@code gfx} currently has — for the
     * rotated-isometric branch that's called from inside the tile's own
     * rotate/skew transform, so the outline follows the same shear as the
     * sprite itself instead of being drawn axis-aligned over a rotated image.
     */
    private void drawDebugImageBounds(Graphics2D gfx, double x, double y, double w, double h) {
        Color oldColor = gfx.getColor();
        Stroke oldStroke = gfx.getStroke();
        gfx.setStroke(new BasicStroke(1f));
        gfx.setColor(DEBUG_RECT_COLOR);
        gfx.draw(new Rectangle2D.Double(x, y, w, h));
        double bx = x + w / 2.0, by = y + h;
        gfx.setColor(DEBUG_IMG_BOTTOM_COLOR);
        gfx.fill(new Ellipse2D.Double(bx - DEBUG_DOT_R, by - DEBUG_DOT_R, DEBUG_DOT_R * 2, DEBUG_DOT_R * 2));
        gfx.setColor(oldColor);
        gfx.setStroke(oldStroke);
    }

    /**
     * Debug overlay (see {@link #debugAnchors}): marks the cell's own anchor
     * point in cyan — the isometric diamond's center in isometric view, the
     * cell's center in top-down — always in plain screen coordinates
     * (never under a rotation transform), since that point is fixed by the
     * grid, not by whatever sprite happens to be standing on it.
     */
    private void drawDebugCellAnchor(Graphics2D gfx, double x, double y) {
        Color oldColor = gfx.getColor();
        gfx.setColor(DEBUG_CELL_MID_COLOR);
        gfx.fill(new Ellipse2D.Double(x - DEBUG_DOT_R, y - DEBUG_DOT_R, DEBUG_DOT_R * 2, DEBUG_DOT_R * 2));
        gfx.setColor(oldColor);
    }

    private void renderCell(Graphics2D gfx, MapCell cell, int sx, int sy,
                            int cw, int ch, int col, int row, boolean floorPass) {
        for (GraphicObject obj : cell.getObjects()) {
            if (obj.isFloor != floorPass) continue; // floor pass draws only floor tiles, foreground pass draws everything else

            if (obj instanceof Unit u && u.isMoving() && u.showTrajectory)
                drawUnitTrajectory(gfx, u);

            Image img = assets.get(obj.imageName);

            // ── Placeholder ──────────────────────────────────────────────────
            if (img == null) {
                if (options.viewType == EngineOptions.ViewType.ISOMETRIC) {
                    int[] xPts = {sx, sx + cw / 2, sx, sx - cw / 2};
                    int[] yPts = {sy, sy + ch / 2, sy + ch, sy + ch / 2};
                    gfx.setColor(obj.collision ? new Color(120, 30, 30) : new Color(40, 40, 80));
                    gfx.fillPolygon(xPts, yPts, 4);
                    gfx.setColor(Color.GRAY);
                    gfx.drawPolygon(xPts, yPts, 4);
                    if (obj instanceof Unit u)
                        recordUnitBounds(u, null, sx - cw / 2, sy, cw, ch);
                } else {
                    gfx.setColor(obj.collision ? new Color(120, 30, 30) : new Color(40, 40, 80));
                    gfx.fillRect(sx, sy, cw, ch);
                    gfx.setColor(Color.GRAY);
                    gfx.drawRect(sx, sy, cw - 1, ch - 1);
                    if (obj instanceof Unit u)
                        recordUnitBounds(u, null, sx, sy, cw, ch);
                }
                continue;
            }

            int imgW = img.getWidth(null);
            int imgH = img.getHeight(null);
            if (imgW <= 0 || imgH <= 0) continue;

            // ── Floor tile ───────────────────────────────────────────────────
            // Always fills its cell exactly (the diamond in isometric view, the
            // plain rect in top-down) regardless of the image's own native size
            // or obj.fitToCell/drawWidth/drawHeight, so neighbouring floor tiles
            // always line up seamlessly — the trimmed art (see
            // AssetStorage#loadImageResourceTrimAlpha) is just stretched to fit.
            if (obj.isFloor) {
                if (options.viewType == EngineOptions.ViewType.ISOMETRIC) {
                    gfx.drawImage(img, sx - cw / 2, sy, cw, ch, null);
                } else {
                    gfx.drawImage(img, sx, sy, cw, ch, null);
                }
                continue;
            }

            int dw, dh;
            if (obj.fitToCell) {
                double targetW = cw * obj.fitScale * footprintWidthFactor(obj);
                dw = (int) Math.round(targetW);
                dh = (int) Math.round(targetW * imgH / (double) imgW);
            } else {
                // Fixed pixel-size assets (obj.drawWidth/Height) still need to grow/shrink
                // with the camera zoom like everything else, or they'd visibly stay the same
                // size on screen while tiles and fitToCell sprites around them scale.
                double baseW = obj.drawWidth  > 0 ? obj.drawWidth  : imgW;
                double baseH = obj.drawHeight > 0 ? obj.drawHeight : imgH;
                dw = (int) Math.round(baseW * zoom);
                dh = (int) Math.round(baseH * zoom);
            }
            if (dw <= 0 || dh <= 0) continue;

            // ── Спрайт ───────────────────────────────────────────────────────
            int footprintDrop = footprintDrop(obj, ch);
            // obj.xOffset/yOffset (static anchor tweak + Unit's cell-crossing
            // slide, see Unit#applyMotionOffset) are authored/computed in
            // native, unscaled pixels — same space as cellWidth/cellHeight —
            // while sx/sy here are already zoom-scaled screen pixels. Scale
            // the offset by zoom too, or it drifts out of sync with the grid
            // (unit slides the wrong distance relative to its cell) at any
            // zoom level other than 1.0.
            int scaledXOffset = (int) Math.round(obj.xOffset * zoom);
            int scaledYOffset = (int) Math.round(obj.yOffset * zoom);
            if (options.viewType == EngineOptions.ViewType.ISOMETRIC) {
                if (obj.isIsometric()) {
                    int dx = sx - dw / 2 + scaledXOffset;
                    int dy = sy + ch / 2 - dh + scaledYOffset + footprintDrop;
                    gfx.drawImage(img, dx, dy, dw, dh, null);

                    if (debugAnchors) {
                        drawDebugImageBounds(gfx, dx, dy, dw, dh);
                        drawDebugCellAnchor(gfx, sx, sy + ch / 2.0);
                    }

                    if (obj instanceof Unit u)
                        recordUnitBounds(u, obj.imageName, dx, dy, dw, dh);

                    if (obj instanceof Damageable dmg && dmg.isAlive())
                        drawHealthBar(gfx, dx + dw / 2, dy, dw, dmg);

                } else {
                    double cx = sx + scaledXOffset;
                    double cy = sy + ch / 2.0 + scaledYOffset + footprintDrop;

                    AffineTransform old = gfx.getTransform();
                    AffineTransform at  = new AffineTransform();
                    at.translate(cx, cy);
                    at.rotate(Math.toRadians(45));
                    at.scale(1.0, 0.5);
                    at.translate(-dw / 2.0, -dh / 2.0);

                    gfx.setTransform(at);
                    gfx.drawImage(img, 0, 0, dw, dh, null);
                    if (debugAnchors) drawDebugImageBounds(gfx, 0, 0, dw, dh);
                    gfx.setTransform(old);

                    if (debugAnchors) drawDebugCellAnchor(gfx, sx, sy + ch / 2.0);

                    if (obj instanceof Unit u)
                        recordUnitBounds(u, obj.imageName, (int) (cx - dw / 4.0), (int) (cy - dh / 4.0), (int) (dw / 2.0), (int) (dh / 2.0));
                }
            } else {
                int dx = sx + (cw - dw) / 2 + scaledXOffset;
                int dy = sy + ch - dh + scaledYOffset + footprintDrop;
                gfx.drawImage(img, dx, dy, dw, dh, null);

                if (debugAnchors) {
                    drawDebugImageBounds(gfx, dx, dy, dw, dh);
                    drawDebugCellAnchor(gfx, sx + cw / 2.0, sy + ch / 2.0);
                }

                if (obj instanceof Unit u)
                    recordUnitBounds(u, obj.imageName, dx, dy, dw, dh);

                if (obj instanceof Damageable dmg && dmg.isAlive())
                    drawHealthBar(gfx, dx + dw / 2, dy, dw, dmg);
            }
        }
    }

    /**
     * Draws a translucent preview of {@code obj} exactly where it would land
     * if actually placed at (col,row) right now — same integer anchor-plus-
     * step placement math {@link #renderTopDown}/{@link #renderIsometric}
     * use (and {@link #screenToCell} inverts), so the ghost is pixel-
     * identical to the real tile the moment it's committed. Doesn't touch
     * the map or document, and — unlike {@link #renderCell} — doesn't record
     * unit hit-bounds, draw health bars, or draw movement trajectories: it's
     * a preview, not a placed object.
     */
    public void renderPreview(Graphics2D gfx, GraphicObject obj, int col, int row, float alpha) {
        if (obj == null || !isCellValid(col, row)) return;

        int screenCw = (int) Math.round(options.cellWidth  * zoom);
        int screenCh = (int) Math.round(options.cellHeight * zoom);
        if (screenCw <= 0) screenCw = 1;
        if (screenCh <= 0) screenCh = 1;

        int[] origin = mapPixelToScreen(0, 0);
        int sx, sy;
        if (options.viewType == EngineOptions.ViewType.ISOMETRIC) {
            sx = origin[0] + (col - row) * (screenCw / 2);
            sy = origin[1] + (col + row) * (screenCh / 2);
        } else {
            sx = origin[0] + col * screenCw;
            sy = origin[1] + row * screenCh;
        }

        Image img = assets.get(obj.imageName);
        if (img == null) return; // no placeholder box for a ghost — just skip silently

        int imgW = img.getWidth(null), imgH = img.getHeight(null);
        if (imgW <= 0 || imgH <= 0) return;

        int dw, dh;
        if (obj.fitToCell) {
            double targetW = screenCw * obj.fitScale * footprintWidthFactor(obj);
            dw = (int) Math.round(targetW);
            dh = (int) Math.round(targetW * imgH / (double) imgW);
        } else {
            double baseW = obj.drawWidth  > 0 ? obj.drawWidth  : imgW;
            double baseH = obj.drawHeight > 0 ? obj.drawHeight : imgH;
            dw = (int) Math.round(baseW * zoom);
            dh = (int) Math.round(baseH * zoom);
        }
        if (dw <= 0 || dh <= 0) return;

        int footprintDrop = footprintDrop(obj, screenCh);
        int scaledXOffset = (int) Math.round(obj.xOffset * zoom);
        int scaledYOffset = (int) Math.round(obj.yOffset * zoom);

        Composite oldComposite = gfx.getComposite();
        gfx.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, Math.max(0f, Math.min(1f, alpha))));
        try {
            if (obj.isFloor) {
                if (options.viewType == EngineOptions.ViewType.ISOMETRIC) {
                    gfx.drawImage(img, sx - screenCw / 2, sy, screenCw, screenCh, null);
                } else {
                    gfx.drawImage(img, sx, sy, screenCw, screenCh, null);
                }
            } else if (options.viewType == EngineOptions.ViewType.ISOMETRIC && obj.isIsometric()) {
                int dx = sx - dw / 2 + scaledXOffset;
                int dy = sy + screenCh / 2 - dh + scaledYOffset + footprintDrop;
                gfx.drawImage(img, dx, dy, dw, dh, null);
            } else if (options.viewType == EngineOptions.ViewType.ISOMETRIC) {
                double cx = sx + scaledXOffset;
                double cy = sy + screenCh / 2.0 + scaledYOffset + footprintDrop;
                AffineTransform old = gfx.getTransform();
                AffineTransform at = new AffineTransform();
                at.translate(cx, cy);
                at.rotate(Math.toRadians(45));
                at.scale(1.0, 0.5);
                at.translate(-dw / 2.0, -dh / 2.0);
                gfx.setTransform(at);
                gfx.drawImage(img, 0, 0, dw, dh, null);
                gfx.setTransform(old);
            } else {
                int dx = sx + (screenCw - dw) / 2 + scaledXOffset;
                int dy = sy + screenCh - dh + scaledYOffset + footprintDrop;
                gfx.drawImage(img, dx, dy, dw, dh, null);
            }
        } finally {
            gfx.setComposite(oldComposite);
        }
    }

    /** Records this frame's on-screen bounds (and source image, for silhouette outlines) for a rendered unit — see {@link #unitAt}. */
    private void recordUnitBounds(Unit u, String imageName, int x, int y, int w, int h) {
        buildingUnitBounds.add(new UnitBounds(u, imageName, x, y, Math.max(1, w), Math.max(1, h)));
    }

    /**
     * Draws a small health bar centred above a unit — {@code topY} is the screen-space
     * y of the sprite's top edge, {@code centerX} its horizontal centre, {@code width}
     * used as a lower bound for the bar's width so it stays legible for tiny sprites.
     */
    private void drawHealthBar(Graphics2D gfx, int centerX, int topY, int width, Damageable dmg) {
        int max = dmg.getMaxHealth();
        if (max <= 0) return;

        int barW = Math.max(24, Math.min(width, 60));
        int barH = 5;
        int x = centerX - barW / 2;
        int y = topY - barH - 6;

        double pct = Math.max(0.0, Math.min(1.0, dmg.getHealth() / (double) max));

        Color old = gfx.getColor();

        gfx.setColor(new Color(20, 20, 20, 200));
        gfx.fillRect(x - 1, y - 1, barW + 2, barH + 2);

        gfx.setColor(new Color(70, 20, 20));
        gfx.fillRect(x, y, barW, barH);

        Color fill = pct > 0.5 ? new Color(60, 200, 60)
                : pct > 0.25 ? new Color(230, 180, 40)
                  : new Color(210, 50, 50);
        gfx.setColor(fill);
        gfx.fillRect(x, y, (int) Math.round(barW * pct), barH);

        gfx.setColor(Color.BLACK);
        gfx.drawRect(x, y, barW, barH);

        gfx.setColor(old);
    }

    /** Draws a simple dotted line + waypoint dots for a moving Unit's remaining path. */
    private void drawUnitTrajectory(Graphics2D gfx, Unit u) {
        java.util.List<int[]> remaining = u.getRemainingPath();
        if (remaining.isEmpty()) return;

        Color oldColor = gfx.getColor();
        gfx.setColor(new Color(255, 230, 60, 200));

        double[] fromMp = cellToMapPixel(u.getCol(), u.getRow());
        int[] fromSp = mapPixelToScreen(fromMp[0] + u.getMotionDX(), fromMp[1] + u.getMotionDY());

        for (int[] step : remaining) {
            double[] mp = cellToMapPixel(step[0], step[1]);
            int[] sp = mapPixelToScreen(mp[0], mp[1]);
            gfx.drawLine(fromSp[0], fromSp[1], sp[0], sp[1]);
            gfx.fillOval(sp[0] - 3, sp[1] - 3, 6, 6);
            fromSp = sp;
        }

        gfx.setColor(oldColor);
    }

    // =========================================================================
    // Mouse / keyboard event handlers
    // =========================================================================

    public void mouseMove(int x, int y) {
        mouseX = x;
        mouseY = y;
    }

    public void mouseDragged(int x, int y) {
        mouseX = x;
        mouseY = y;
        if (rightMouseDown) {
            // scrollBy() moves the view in map-pixel space; divide by zoom so a
            // screen-pixel of drag always pans the same screen distance, at any zoom level.
            double z = zoom;
            scrollBy((lastDragX - x) / z, (lastDragY - y) / z);
        }
        lastDragX = x;
        lastDragY = y;
    }

    public void mouseLeftDown(int x, int y) {
        leftPressX = x;
        leftPressY = y;
        onMouseLeftDown(x, y);
    }

    public void mouseLeftUp(int x, int y) {
        onMouseLeftUp(x, y);
        // Left button has no drag gesture of its own in this engine (only right-drag
        // pans the camera) — so button-down + button-up always counts as a click,
        // however far the pointer moved in between, and it fires at the press-down
        // point (where the person actually aimed), not wherever the release ended up.
        mouseClick(leftPressX, leftPressY, MouseEvent.BUTTON1);
    }

    public void mouseRightDown(int x, int y) {
        rightMouseDown = true;
        rightPressX = x;
        rightPressY = y;
        lastDragX = x;
        lastDragY = y;
        onMouseRightDown(x, y);
    }

    public void mouseRightUp(int x, int y) {
        rightMouseDown = false;
        onMouseRightUp(x, y);
        // Right button doubles as camera-pan (right-drag), so unlike left it still
        // needs to tell "a click" apart from "a pan that ended here" — see isClickGesture().
        if (isClickGesture(rightPressX, rightPressY, x, y))
            mouseClick(rightPressX, rightPressY, MouseEvent.BUTTON3);
    }

    /**
     * Decides whether a right-button-down at ({@code px},{@code py}) followed
     * by a button-up at ({@code x},{@code y}) counts as a click rather than the
     * end of a camera-pan drag. Controlled by
     * {@link EngineOptions#clickDragTolerancePx} and
     * {@link EngineOptions#clickToleranceOverrideKeyCode}. Not used for the
     * left button, which always registers as a click — see {@link #mouseLeftUp}.
     */
    private boolean isClickGesture(int px, int py, int x, int y) {
        if (clickToleranceKeyHeld) return true;
        int tol = Math.max(0, options.clickDragTolerancePx);
        long dx = x - px, dy = y - py;
        return dx * dx + dy * dy <= (long) tol * tol;
    }

    public void mouseClick(int x, int y, int button) {
        Unit u = unitAt(x, y);
        if (u != null) {
            if (unitClickListener != null) unitClickListener.onUnitClick(u, button);
            return; // a click that lands on a unit's sprite doesn't also fire the cell listener
        }
        int[] cell = screenToCell(x, y);
        if (cell[0] >= 0 && cellClickListener != null)
            cellClickListener.onCellClick(cell[0], cell[1], button);
    }

    public void mouseWheel(int x, int y, int delta) {
        if (options.wheelZoomEnabled) {
            // Convention: wheel-up (negative delta) zooms in, wheel-down zooms out.
            zoomBy(delta < 0 ? options.zoomStep : 1.0 / options.zoomStep);
        }
        if (scrollListener != null)
            scrollListener.onScroll(x, y, delta);
        onMouseWheel(x, y, delta);
    }

    public void keyPressed(int keyCode) {
        if (options.clickToleranceOverrideKeyCode != -1
                && keyCode == options.clickToleranceOverrideKeyCode)
            clickToleranceKeyHeld = true;
        if (options.keyboardZoomEnabled) {
            if (keyCode == KeyEvent.VK_PLUS || keyCode == KeyEvent.VK_ADD || keyCode == KeyEvent.VK_EQUALS)
                zoomBy(options.zoomStep);
            else if (keyCode == KeyEvent.VK_MINUS || keyCode == KeyEvent.VK_SUBTRACT)
                zoomBy(1.0 / options.zoomStep);
        }
        if (keyPressedListener != null) keyPressedListener.onKey(keyCode);
        onKeyPressed(keyCode);
    }

    public void keyReleased(int keyCode) {
        if (options.clickToleranceOverrideKeyCode != -1
                && keyCode == options.clickToleranceOverrideKeyCode)
            clickToleranceKeyHeld = false;
        if (keyReleasedListener != null) keyReleasedListener.onKey(keyCode);
        onKeyReleased(keyCode);
    }

    /**
     * Force-enable or disable "loose click" mode at runtime (equivalent to
     * holding {@link EngineOptions#clickToleranceOverrideKeyCode}). Useful if
     * you want to toggle it from game logic instead of a fixed key.
     */
    public void setClickToleranceOverride(boolean enabled) { clickToleranceKeyHeld = enabled; }

    protected void onMouseLeftDown(int x, int y)  {}
    protected void onMouseLeftUp(int x, int y)    {}
    protected void onMouseRightDown(int x, int y) {}
    protected void onMouseRightUp(int x, int y)   {}
    protected void onMouseWheel(int screenX, int screenY, int delta) {}
    protected void onKeyPressed(int keyCode)  {}
    protected void onKeyReleased(int keyCode) {}

    // =========================================================================
    // Listener setters
    // =========================================================================

    public void setCellClickListener(CellClickListener l)  { cellClickListener   = l; }
    public void setUnitClickListener(UnitClickListener l)  { unitClickListener   = l; }
    public void setUnitHoverListener(UnitHoverListener l)  { unitHoverListener   = l; }
    public void setKeyPressedListener(KeyEventListener l)  { keyPressedListener  = l; }
    public void setKeyReleasedListener(KeyEventListener l) { keyReleasedListener = l; }
    public void setScrollListener(ScrollListener l)        { scrollListener      = l; }

    // =========================================================================
    // Canvas resize notification
    // =========================================================================

    void notifyResized(int w, int h) {
        canvasW = Math.max(w, 1);
        canvasH = Math.max(h, 1);
        buffer  = null;
    }
}