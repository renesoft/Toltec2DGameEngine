package org.toltec;

import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.GraphicsConfiguration;
import java.awt.GraphicsDevice;
import java.awt.GraphicsEnvironment;
import java.awt.Image;
import java.awt.RenderingHints;
import java.awt.geom.AffineTransform;
import java.awt.image.VolatileImage;

/**
 * Tile-based 2-D game engine supporting top-down and isometric projection.
 */
public abstract class TileGameEngine {

    // =========================================================================
    // Functional listener interfaces
    // =========================================================================

    /** Fired when the user clicks on a map cell. */
    @FunctionalInterface
    public interface CellClickListener {
        void onCellClick(int col, int row, int button);
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

    private final GraphicsEnvironment  ge = GraphicsEnvironment.getLocalGraphicsEnvironment();
    private final GraphicsDevice       gd = ge.getDefaultScreenDevice();
    private final GraphicsConfiguration gc = gd.getDefaultConfiguration();
    private volatile VolatileImage buffer;

    private CellClickListener  cellClickListener;
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

        viewCenterX = mapPixelWidth()  / 2.0;
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
        return Math.max(0, Math.min(mapPixelWidth(),  x));
    }
    private double clampViewY(double y) {
        return Math.max(0, Math.min(mapPixelHeight(), y));
    }

    // =========================================================================
    // Coordinate conversions
    // =========================================================================

    private int[] mapPixelToScreen(double mpx, double mpy) {
        double cx, cy;
        synchronized (viewLock) { cx = viewCenterX; cy = viewCenterY; }
        return new int[]{
                (int) Math.round(mpx - cx) + canvasW / 2,
                (int) Math.round(mpy - cy) + canvasH / 2
        };
    }

    private double[] screenToMapPixel(int sx, int sy) {
        double cx, cy;
        synchronized (viewLock) { cx = viewCenterX; cy = viewCenterY; }
        return new double[]{sx - canvasW / 2.0 + cx, sy - canvasH / 2.0 + cy};
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

    public int[] screenToCell(int sx, int sy) {
        double[] mp = screenToMapPixel(sx, sy);
        int col, row;
        if (options.viewType == EngineOptions.ViewType.ISOMETRIC) {
            double a = mp[0] * 2.0 / options.cellWidth;
            double b = mp[1] * 2.0 / options.cellHeight;
            col = (int) Math.floor((a + b) / 2.0);
            row = (int) Math.floor((b - a) / 2.0);
        } else {
            col = (int) Math.floor(mp[0] / options.cellWidth);
            row = (int) Math.floor(mp[1] / options.cellHeight);
        }
        if (!isCellValid(col, row)) return new int[]{-1, -1};
        return new int[]{col, row};
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

        if (dx != 0 || dy != 0) scrollBy(dx, dy);
    }

    // =========================================================================
    // Rendering
    // =========================================================================

    public void draw(Graphics2D gfx) {
        if (canvas != null) {
            canvasW = Math.max(canvas.getWidth(),  1);
            canvasH = Math.max(canvas.getHeight(), 1);
        }

        ensureBuffer();

        int attempts = 0;
        do {
            int valid = buffer.validate(gc);
            if (valid == VolatileImage.IMAGE_INCOMPATIBLE) {
                buffer = gc.createCompatibleVolatileImage(canvasW, canvasH);
            }

            Graphics2D bg = buffer.createGraphics();
            try {
                bg.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                        RenderingHints.VALUE_ANTIALIAS_OFF);
                bg.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                        RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);

                bg.setColor(Color.BLACK);
                bg.fillRect(0, 0, canvasW, canvasH);

                if (options.viewType == EngineOptions.ViewType.ISOMETRIC)
                    renderIsometric(bg);
                else
                    renderTopDown(bg);

                onDraw(bg);

            } finally {
                bg.dispose();
            }
        } while (buffer.contentsLost() && ++attempts < 3);

        gfx.drawImage(buffer, 0, 0, null);
    }

    private void ensureBuffer() {
        if (buffer == null
                || buffer.getWidth()  != canvasW
                || buffer.getHeight() != canvasH) {
            buffer = gc.createCompatibleVolatileImage(canvasW, canvasH);
        }
    }

    // ── Top-down pass ─────────────────────────────────────────────────────────

    private void renderTopDown(Graphics2D gfx) {
        int cw = options.cellWidth, ch = options.cellHeight;

        double[] tl = screenToMapPixel(0, 0);
        double[] br = screenToMapPixel(canvasW, canvasH);

        int colMin = Math.max(0, (int) Math.floor(tl[0] / cw) - 1);
        int colMax = Math.min(options.mapWidthCells  - 1, (int) Math.ceil(br[0] / cw));
        int rowMin = Math.max(0, (int) Math.floor(tl[1] / ch) - 1);
        int rowMax = Math.min(options.mapHeightCells - 1, (int) Math.ceil(br[1] / ch));

        for (int row = rowMin; row <= rowMax; row++) {
            for (int col = colMin; col <= colMax; col++) {
                double[] mp = cellToMapPixel(col, row);
                int[]    sp = mapPixelToScreen(mp[0], mp[1]);
                renderCell(gfx, map[row][col], sp[0], sp[1], cw, ch, col, row);
            }
        }
    }

    // ── Isometric pass (painter's algorithm) ─────────────────────────────────

    private void renderIsometric(Graphics2D gfx) {
        int mw = options.mapWidthCells, mh = options.mapHeightCells;

        for (int depth = 0; depth < mw + mh - 1; depth++) {
            int colStart = Math.max(0, depth - mh + 1);
            int colEnd   = Math.min(mw - 1, depth);

            for (int col = colStart; col <= colEnd; col++) {
                int row = depth - col;
                double[] mp = cellToMapPixel(col, row);
                int[]    sp = mapPixelToScreen(mp[0], mp[1]);

                int cw = options.cellWidth, ch = options.cellHeight;
                if (sp[0] + cw < 0 || sp[0] > canvasW + cw) continue;
                if (sp[1] + ch < 0 || sp[1] > canvasH + ch) continue;

                renderCell(gfx, map[row][col], sp[0], sp[1], cw, ch, col, row);
            }
        }
    }

    // ── Single cell ───────────────────────────────────────────────────────────

    private void renderCell(Graphics2D gfx, MapCell cell, int sx, int sy,
                            int cw, int ch, int col, int row) {
        for (GraphicObject obj : cell.getObjects()) {
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
                } else {
                    gfx.setColor(obj.collision ? new Color(120, 30, 30) : new Color(40, 40, 80));
                    gfx.fillRect(sx, sy, cw, ch);
                    gfx.setColor(Color.GRAY);
                    gfx.drawRect(sx, sy, cw - 1, ch - 1);
                }
                continue;
            }

            int imgW = img.getWidth(null);
            int imgH = img.getHeight(null);
            if (imgW <= 0 || imgH <= 0) continue;

            int dw, dh;
            if (obj.fitToCell) {
                double targetW = cw * obj.fitScale;
                dw = (int) Math.round(targetW);
                dh = (int) Math.round(targetW * imgH / (double) imgW);
            } else {
                dw = obj.drawWidth  > 0 ? obj.drawWidth  : imgW;
                dh = obj.drawHeight > 0 ? obj.drawHeight : imgH;
            }
            if (dw <= 0 || dh <= 0) continue;

            // ── Спрайт ───────────────────────────────────────────────────────
            if (options.viewType == EngineOptions.ViewType.ISOMETRIC) {
                if (obj.isIsometric()) {
                    int dx = sx - dw / 2;
                    int dy = sy + ch / 2 - dh + obj.yOffset;
                    gfx.drawImage(img, dx, dy, dw, dh, null);

                    // === DEBUG overlay =======================================
                    Color oldColor = gfx.getColor();
                    Font  oldFont  = gfx.getFont();

                    gfx.setColor(Color.YELLOW);
                    gfx.drawRect(dx, dy, dw, dh);

                    gfx.setColor(Color.RED);
                    gfx.fillOval(sx - 4, sy + ch / 2 - 4, 8, 8);

                    gfx.setColor(Color.YELLOW);
                    gfx.setFont(new Font("Monospaced", Font.PLAIN, 10));
                    String info = dw + "x" + dh + " (" + col + "," + row + ") @(" + dx + "," + dy + ")";
                    gfx.drawString(info, dx, dy - 4);

                    gfx.setColor(oldColor);
                    gfx.setFont(oldFont);
                    // ==========================================================

                } else {
                    double cx = sx;
                    double cy = sy + ch / 2.0 + obj.yOffset;

                    AffineTransform old = gfx.getTransform();
                    AffineTransform at  = new AffineTransform();
                    at.translate(cx, cy);
                    at.rotate(Math.toRadians(45));
                    at.scale(1.0, 0.5);
                    at.translate(-dw / 2.0, -dh / 2.0);

                    gfx.setTransform(at);
                    gfx.drawImage(img, 0, 0, dw, dh, null);
                    gfx.setTransform(old);
                }
            } else {
                int dx = sx + (cw - dw) / 2;
                int dy = sy + ch - dh + obj.yOffset;
                gfx.drawImage(img, dx, dy, dw, dh, null);
            }
        }
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
            scrollBy(lastDragX - x, lastDragY - y);
        }
        lastDragX = x;
        lastDragY = y;
    }

    public void mouseLeftDown(int x, int y)  { onMouseLeftDown(x, y); }
    public void mouseLeftUp(int x, int y)    { onMouseLeftUp(x, y); }

    public void mouseRightDown(int x, int y) {
        rightMouseDown = true;
        lastDragX = x;
        lastDragY = y;
        onMouseRightDown(x, y);
    }

    public void mouseRightUp(int x, int y) {
        rightMouseDown = false;
        onMouseRightUp(x, y);
    }

    public void mouseClick(int x, int y, int button) {
        int[] cell = screenToCell(x, y);
        if (cell[0] >= 0 && cellClickListener != null)
            cellClickListener.onCellClick(cell[0], cell[1], button);
    }

    public void mouseWheel(int x, int y, int delta) {
        if (scrollListener != null)
            scrollListener.onScroll(x, y, delta);
        onMouseWheel(x, y, delta);
    }

    public void keyPressed(int keyCode) {
        if (keyPressedListener != null) keyPressedListener.onKey(keyCode);
        onKeyPressed(keyCode);
    }

    public void keyReleased(int keyCode) {
        if (keyReleasedListener != null) keyReleasedListener.onKey(keyCode);
        onKeyReleased(keyCode);
    }

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