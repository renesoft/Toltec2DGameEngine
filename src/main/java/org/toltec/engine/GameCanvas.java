package org.toltec.engine;

import java.awt.Color;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseMotionAdapter;
import java.awt.event.MouseWheelListener;
import javax.swing.JPanel;

/**
 * Lightweight Swing {@link JPanel} that:
 * <ul>
 *   <li>calls {@link TileGameEngine#draw(Graphics2D)} every repaint;</li>
 *   <li>routes all mouse and keyboard events to the engine;</li>
 *   <li>notifies the engine when the canvas is resized.</li>
 * </ul>
 *
 * Add this canvas to a {@link javax.swing.JFrame} (inside a
 * {@link javax.swing.JPanel} with {@link java.awt.BorderLayout}) — or embed
 * it inside a JavaFX {@code SwingNode}, since it's a plain lightweight Swing
 * component rather than a heavyweight {@link java.awt.Canvas} (SwingNode
 * doesn't support heavyweight AWT components — see its javadoc) — and then
 * call {@link TileGameEngine#start(GameCanvas)}.
 *
 * <pre>
 *   MyGame    engine = new MyGame(opts);
 *   GameCanvas canvas = new GameCanvas(engine);
 *
 *   JFrame frame = new JFrame("My Game");
 *   frame.setLayout(new BorderLayout());
 *   frame.add(canvas, BorderLayout.CENTER);
 *   frame.setSize(1024, 768);
 *   frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
 *   frame.setVisible(true);
 *
 *   engine.start(canvas);
 * </pre>
 */
public class GameCanvas extends JPanel {

    private final TileGameEngine engine;

    // Middle-button click detection (left/right go through the engine; the
    // engine has no notion of a middle button, so it's tracked here).
    private int midPressX, midPressY;

    public GameCanvas(TileGameEngine engine) {
        this.engine = engine;
        setBackground(Color.BLACK);
        setFocusable(true);
        // Painting is entirely manual (engine.draw() fills every pixel every
        // frame) and the engine already double-buffers internally via its own
        // VolatileImage backbuffer — Swing's own double-buffering on top of
        // that would just be a second, redundant copy.
        setDoubleBuffered(false);
        requestFocusInWindow();

        // ── Mouse buttons ─────────────────────────────────────────────────────
        addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                requestFocusInWindow();
                switch (e.getButton()) {
                    case MouseEvent.BUTTON1 -> engine.mouseLeftDown(e.getX(), e.getY());
                    case MouseEvent.BUTTON3 -> engine.mouseRightDown(e.getX(), e.getY());
                    case MouseEvent.BUTTON2 -> { midPressX = e.getX(); midPressY = e.getY(); }
                }
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                switch (e.getButton()) {
                    case MouseEvent.BUTTON1 -> engine.mouseLeftUp(e.getX(), e.getY());
                    case MouseEvent.BUTTON3 -> engine.mouseRightUp(e.getX(), e.getY());
                    case MouseEvent.BUTTON2 -> {
                        int dx = e.getX() - midPressX, dy = e.getY() - midPressY;
                        if (dx * dx + dy * dy <= 36) // ~6px tolerance, same default as left/right
                            engine.mouseClick(e.getX(), e.getY(), MouseEvent.BUTTON2);
                    }
                }
            }

            // Note: we deliberately do NOT use AWT's native mouseClicked here.
            // AWT only fires it when press and release happen at the *exact*
            // same point, so a press-drag-release (e.g. someone panning
            // slightly before letting go) was silently swallowed and never
            // counted as a click. Click detection now happens inside the
            // engine itself (see TileGameEngine#mouseLeftUp /
            // #isClickGesture), which tolerates small movement and can be
            // configured or overridden via EngineOptions.
        });

        // ── Mouse motion ──────────────────────────────────────────────────────
        addMouseMotionListener(new MouseMotionAdapter() {
            @Override
            public void mouseMoved(MouseEvent e) {
                engine.mouseMove(e.getX(), e.getY());
            }

            @Override
            public void mouseDragged(MouseEvent e) {
                    engine.mouseDragged(e.getX(), e.getY());
            }
        });

        // ── Mouse wheel ───────────────────────────────────────────────────────
        addMouseWheelListener((MouseWheelListener) e ->
                engine.mouseWheel(e.getX(), e.getY(), e.getWheelRotation()));

        // ── Keyboard ──────────────────────────────────────────────────────────
        addKeyListener(new KeyAdapter() {
            @Override
            public void keyPressed(KeyEvent e) {
                engine.keyPressed(e.getKeyCode());
            }

            @Override
            public void keyReleased(KeyEvent e) {
                engine.keyReleased(e.getKeyCode());
            }
        });

        // ── Resize ────────────────────────────────────────────────────────────
        addComponentListener(new ComponentAdapter() {
            @Override
            public void componentResized(ComponentEvent e) {
                engine.notifyResized(getWidth(), getHeight());
            }
        });
    }

    // =========================================================================
    // Rendering
    // =========================================================================

    @Override
    protected void paintComponent(Graphics g) {
        engine.draw((Graphics2D) g);
    }
}
