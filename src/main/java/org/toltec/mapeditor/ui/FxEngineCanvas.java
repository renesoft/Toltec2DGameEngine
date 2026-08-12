package org.toltec.mapeditor.ui;

import javafx.animation.AnimationTimer;
import javafx.embed.swing.SwingFXUtils;
import javafx.scene.canvas.Canvas;
import javafx.scene.image.WritableImage;
import javafx.scene.input.KeyCode;
import javafx.scene.input.MouseButton;
import org.toltec.engine.GameCanvas;
import org.toltec.engine.TileGameEngine;

import javax.swing.SwingUtilities;
import java.awt.Frame;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.awt.event.MouseEvent;

/**
 * A {@link Canvas} that renders a {@link TileGameEngine} without going
 * through {@code SwingNode} at all.
 * <p>
 * {@code SwingNode} embeds a real, continuously-repainting {@code JPanel}
 * and has to copy its entire rendered surface into JavaFX every frame
 * through an internal {@code JLightweightFrame} bridge — a cost that scales
 * with the panel's pixel area and, independently of that, has its own
 * per-frame overhead beyond a plain pixel copy. That's fine for something
 * small and occasionally-repainted (the object editor's little preview
 * cell), but for a canvas that fills most of the window and repaints
 * continuously (a map editor), it's the difference between the engine's own
 * 80+ FPS in a plain {@code JFrame} and a handful of FPS once wrapped in
 * {@code SwingNode}.
 * <p>
 * Instead, this class keeps a {@link GameCanvas} that's never added to any
 * <em>shown</em> AWT/Swing window — just tall/wide enough to make
 * {@link TileGameEngine#draw} render at the right resolution — and, on
 * every JavaFX pulse, pulls a frame by calling {@code engine.draw(...)}
 * directly into an offscreen {@link BufferedImage}, converts it, and blits
 * it onto this {@code Canvas}. Mouse/keyboard input is wired directly from
 * this node's own JavaFX event handlers into the engine's public
 * {@code mouseLeftDown}/{@code mouseDragged}/{@code keyPressed}/etc. methods
 * — the same methods {@link GameCanvas} itself calls internally — so
 * anything built against the engine's input API (like
 * {@code MapEditorEngine}'s paint-stroke handling) needs no changes at all.
 * <p>
 * <b>Displayable, but never visible.</b> {@link TileGameEngine#draw} only
 * allocates its accelerated {@code VolatileImage} backbuffer once
 * {@code headless.getGraphicsConfiguration()} is non-null (see
 * {@code TileGameEngine#ensureBuffer}), which AWT only provides once a
 * component is <i>displayable</i> — i.e. {@code addNotify()} has run,
 * normally as a side effect of being added to a realized, shown window.
 * Since {@code headless} is deliberately never shown, it's hosted inside a
 * hidden top-level {@link Frame} that we realize via {@code addNotify()}
 * but never {@code setVisible(true)} — giving it a real
 * {@code GraphicsConfiguration} (and therefore a real, accelerated
 * backbuffer) without ever putting an actual window on screen or letting
 * Swing's own repaint machinery touch it.
 * <p>
 * <b>Sizing.</b> This node deliberately reports a "no preference" size
 * ({@code prefWidth}/{@code prefHeight} return 0, {@code maxWidth}/
 * {@code maxHeight} return {@code Double.MAX_VALUE}) rather than echoing
 * back its own current {@code getWidth()}/{@code getHeight()}. A resizable
 * {@code Canvas} has no natural/content size the way a labeled control
 * does, so its "preferred size" is whatever the layout gives it — echoing
 * back the current size instead creates a feedback loop with
 * content-biased parents (HBox/VBox with grow priorities, BorderPane,
 * SplitPane, etc.): parent asks prefWidth → gets current width → resizes
 * the canvas to current-width-plus-insets → next pulse prefWidth reports
 * that larger value → parent grows it again, and so on, so the panel keeps
 * growing wider on every layout pulse. Callers MUST size this node by
 * binding {@code widthProperty()}/{@code heightProperty()} to the
 * container that hosts it, e.g.:
 * <pre>
 *   FxEngineCanvas engineCanvas = new FxEngineCanvas(engine);
 *   container.getChildren().add(engineCanvas);
 *   engineCanvas.widthProperty().bind(container.widthProperty());
 *   engineCanvas.heightProperty().bind(container.heightProperty());
 * </pre>
 * <p>
 * <b>Panning.</b> Only right-mouse-button drag pans the camera (handled
 * entirely inside {@link TileGameEngine} via {@code mouseRightDown}/
 * {@code mouseDragged}/{@code mouseRightUp} — nothing extra needed here).
 * The engine's own edge-scrolling ({@code EngineOptions#edgeScrollWidth})
 * is explicitly disabled in the constructor below: it's driven by the last
 * {@code mouseX}/{@code mouseY} the engine was told about, and since a
 * JavaFX {@code Canvas} never forwards a "mouse exited" event into that
 * tracking, the pointer leaving the canvas near an edge (e.g. dragging the
 * mouse off the bottom of the window) leaves the engine's last-known
 * position pinned at that edge — which then reads as "still hovering the
 * edge" forever and scrolls the map in that direction indefinitely, only
 * stopping once the pointer re-enters the canvas and moves. Disabling
 * edge-scroll removes that failure mode entirely rather than trying to
 * patch it with synthetic exit events.
 */
public class FxEngineCanvas extends Canvas {

    private final TileGameEngine engine;
    private final GameCanvas headless;
    private final Frame hiddenHost;
    private final AnimationTimer timer;

    private BufferedImage frame;
    private WritableImage fxImage;

    private int midPressX, midPressY;

    public FxEngineCanvas(TileGameEngine engine) {
        this.engine = engine;
        this.headless = new GameCanvas(engine); // never shown — see class javadoc

        // Only right-mouse-button drag should pan the camera (see class
        // javadoc "Panning" section) — edge-scrolling is engine-driven and
        // gets stuck once the pointer leaves this canvas near an edge, so
        // it's turned off here rather than left to misbehave.
        engine.options.edgeScrollWidth = 0;

        int initW = Math.max(1, (int) getWidth());
        int initH = Math.max(1, (int) getHeight());
        headless.setSize(initW, initH);

        // Host `headless` in a hidden, never-shown top-level Frame just to
        // give it a real peer/GraphicsConfiguration (see class javadoc).
        // Everything here must happen on the EDT, and must happen before
        // engine.start()/the first render pulse, or TileGameEngine.draw()
        // will hit its GraphicsConfiguration-less fallback path and NPE.
        this.hiddenHost = new Frame();
        runOnEdtNow(() -> {
            hiddenHost.setLayout(null);
            hiddenHost.add(headless);
            hiddenHost.setSize(initW, initH);
            hiddenHost.addNotify(); // realizes hiddenHost AND cascades to headless
        });

        setFocusTraversable(true);
        widthProperty().addListener((obs, was, now) -> resizeHeadless());
        heightProperty().addListener((obs, was, now) -> resizeHeadless());

        wireInput();

        timer = new AnimationTimer() {
            @Override public void handle(long now) { renderFrame(); }
        };
    }

    // =========================================================================
    // Sizing — see "Sizing" section of the class javadoc. Do NOT change these
    // to echo back getWidth()/getHeight(): that reintroduces a layout
    // feedback loop with content-biased parents (continuously-growing
    // width/height). Size this node from outside via width/heightProperty
    // bindings instead.
    // =========================================================================

    @Override public boolean isResizable() { return true; }
    @Override public double prefWidth(double height) { return 0; }
    @Override public double prefHeight(double width) { return 0; }
    @Override public double minWidth(double height) { return 0; }
    @Override public double minHeight(double width) { return 0; }
    @Override public double maxWidth(double height) { return Double.MAX_VALUE; }
    @Override public double maxHeight(double width) { return Double.MAX_VALUE; }

    /** Starts the engine (logic + its own, now-inert render thread — see class javadoc) and this canvas's own render pulse. */
    public void start() {
        resizeHeadlessNow();
        engine.start(headless);
        timer.start();
    }

    /** Stops both the engine and this canvas's render pulse — call before discarding, e.g. on "New map"/"Load map". */
    public void stop() {
        timer.stop();
        engine.stop();
        runOnEdtNow(hiddenHost::dispose); // tears down the hidden peer; safe, it was never shown
    }

    private void resizeHeadless() {
        SwingUtilities.invokeLater(this::resizeHeadlessNow);
    }

    private void resizeHeadlessNow() {
        int w = Math.max(1, (int) getWidth());
        int h = Math.max(1, (int) getHeight());
        headless.setSize(w, h);
        hiddenHost.setSize(w, h);
    }

    /**
     * Runs {@code r} synchronously on the EDT, from whatever thread we're
     * called from (constructor/{@code stop()} may run on the JavaFX
     * Application Thread). Peer creation/teardown needs to happen on the
     * EDT and, for the constructor's case, needs to have actually finished
     * before {@link #start()} can safely call {@code engine.start(...)}.
     */
    private static void runOnEdtNow(Runnable r) {
        if (SwingUtilities.isEventDispatchThread()) {
            r.run();
        } else {
            try {
                SwingUtilities.invokeAndWait(r);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }
    }

    private void renderFrame() {
        int w = Math.max(1, headless.getWidth());
        int h = Math.max(1, headless.getHeight());
        if (frame == null || frame.getWidth() != w || frame.getHeight() != h) {
            frame = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
            fxImage = null; // size changed — let SwingFXUtils allocate a fresh one below
        }

        Graphics2D g = frame.createGraphics();
        try {
            engine.draw(g);
        } finally {
            g.dispose();
        }

        fxImage = SwingFXUtils.toFXImage(frame, fxImage);
        getGraphicsContext2D().drawImage(fxImage, 0, 0);
    }

    // =========================================================================
    // Input — mirrors GameCanvas's own AWT wiring, translated to JavaFX events
    // =========================================================================

    private void wireInput() {
        setOnMousePressed(e -> {
            requestFocus();
            int x = (int) e.getX(), y = (int) e.getY();
            if (e.getButton() == MouseButton.PRIMARY) {
                engine.mouseLeftDown(x, y);
            } else if (e.getButton() == MouseButton.SECONDARY) {
                engine.mouseRightDown(x, y);
            } else if (e.getButton() == MouseButton.MIDDLE) {
                midPressX = x;
                midPressY = y;
            }
        });

        setOnMouseReleased(e -> {
            int x = (int) e.getX(), y = (int) e.getY();
            if (e.getButton() == MouseButton.PRIMARY) {
                engine.mouseLeftUp(x, y);
            } else if (e.getButton() == MouseButton.SECONDARY) {
                engine.mouseRightUp(x, y);
            } else if (e.getButton() == MouseButton.MIDDLE) {
                int dx = x - midPressX, dy = y - midPressY;
                if (dx * dx + dy * dy <= 36) // ~6px tolerance, matches GameCanvas
                    engine.mouseClick(x, y, MouseEvent.BUTTON2);
            }
        });

        setOnMouseMoved(e -> engine.mouseMove((int) e.getX(), (int) e.getY()));
        setOnMouseDragged(e -> engine.mouseDragged((int) e.getX(), (int) e.getY()));

        setOnScroll(e -> {
            // JavaFX's deltaY is positive scrolling up/away; AWT wheel rotation
            // is positive scrolling down/toward the user — invert to match
            // what the engine (written against AWT's convention) expects.
            int rotation = (int) Math.signum(-e.getDeltaY());
            if (rotation != 0) engine.mouseWheel((int) e.getX(), (int) e.getY(), rotation);
        });

        setOnKeyPressed(e -> {
            int code = awtKeyCode(e.getCode());
            if (code >= 0) engine.keyPressed(code);
        });
        setOnKeyReleased(e -> {
            int code = awtKeyCode(e.getCode());
            if (code >= 0) engine.keyReleased(code);
        });
    }

    /**
     * JavaFX's {@link KeyCode#getCode()} is deprecated but still returns the
     * same integer values as {@code java.awt.event.KeyEvent.VK_*} for every
     * key the engine actually cares about (letters, arrows, WASD) — exactly
     * the pragmatic AWT/JavaFX bridging this whole class already does.
     */
    @SuppressWarnings("deprecation")
    private static int awtKeyCode(KeyCode code) {
        return code == null ? -1 : code.getCode();
    }
}