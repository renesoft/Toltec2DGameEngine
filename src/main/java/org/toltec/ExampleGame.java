package org.toltec;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.event.KeyEvent;
import java.awt.event.MouseEvent;
import java.io.IOException;
import javax.swing.JFrame;
import javax.swing.SwingUtilities;

/**
 * Minimal example showing how to extend {@link TileGameEngine}.
 *
 * Run main() to see a live isometric map with:
 *  - scrollable viewport (edge scroll + right-drag)
 *  - cell-click feedback printed to console
 *  - SPACE key toggles pause
 *  - WASD keys move the "camera focus" cell
 */
public class ExampleGame extends TileGameEngine {

    // Camera target cell
    private int camCol, camRow;

    // Simple animation tick counter for demo
    private int waveTick = 0;

    // =========================================================================
    // Constructor – configure options here
    // =========================================================================

    public ExampleGame() {
        super(buildOptions());
    }

    private static EngineOptions buildOptions() {
        EngineOptions o = new EngineOptions();
        o.mapWidthCells   = 20;
        o.mapHeightCells  = 20;
        //o.cellWidth       = 96;
        //o.cellHeight      = (int)96/2;                       // typical 2:1 isometric ratio
        o.cellWidth       = 48;
        o.cellHeight      = 24;                       // typical 2:1 isometric ratio
        o.viewType        = EngineOptions.ViewType.ISOMETRIC;
        o.tickIntervalMs  = 20;                      // 5 logic ticks / second
        o.renderIntervalMs = 16;                      // ~60 FPS
        o.edgeScrollWidth = 30;
        o.edgeScrollSpeed = 8;
        return o;
    }

    // =========================================================================
    // onStart – load assets, build map, register listeners
    // =========================================================================

    @Override
    protected void onStart() {
        camCol = options.mapWidthCells  / 2;
        camRow = options.mapHeightCells / 2;
        setCenterToCell(camCol, camRow);

        // ── Demo: fill map with placeholder objects ───────────────────────────
        //
        // In a real game you would call assets.loadImage() / assets.loadAnimation()
        // here, then reference the asset names in GraphicObject constructors.
        //
        // Example (commented out – needs actual image files):
        //
        //   try {
        //       assets.loadImage("grass", "res/grass.png", Color.MAGENTA);
        //       assets.loadAnimation("water", "res/water_sheet.png", Color.MAGENTA);
        //   } catch (IOException e) { e.printStackTrace(); }

        for (int row = 0; row < options.mapHeightCells; row++) {
            for (int col = 0; col < options.mapWidthCells; col++) {
                // Floor tile (layer 0)
                boolean isWater = (row + col) % 7 == 0;
                GraphicObject floor = new GraphicObject(
                        isWater ? "water[0]" : "grass",   // asset name
                        0,                                 // layer
                        isWater                            // water blocks movement
                );
                // Override draw size to fill the isometric cell
                floor.drawWidth  = options.cellWidth;
                floor.drawHeight = options.cellHeight;
                getCell(col, row).addObject(floor);

                // Some cells get an extra "decoration" on layer 1
                if ((col * 3 + row * 5) % 11 == 0) {
                    GraphicObject deco = new GraphicObject("tree", 1, true);
                    deco.drawWidth  = options.cellWidth  / 2;
                    deco.drawHeight = options.cellHeight * 2;
                    getCell(col, row).addObject(deco);
                }
            }
        }
        try {

            //assets.loadAnimation("woman","Z:\\java_workspace_agent\\GameAssets\\UPDATE_x320p_Spritesheets\\x320p_Spritesheets\\Attack_Sword\\Attack_Sword_Body_000.png",new Color(0,0,0,0));
            assets.loadAnimation("woman","Z:\\java_workspace_agent\\GameAssets\\UPDATE_x320p_Spritesheets\\x320p_Spritesheets\\WalkBack_Sword\\WalkBack_Sword_Body_045.png",new Color(0,0,0,0));
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        GraphicObject w = new GraphicObject("woman",5);
        w.setupAnimation("woman",24,2);
        w.setIsometricType();
        w.fitToCell=true;
        //w.yOffset=12;
        getCell(10,10).addObject(w);

        // ── Listeners ─────────────────────────────────────────────────────────

        setCellClickListener((col, row, button) -> {
            String btn = button == MouseEvent.BUTTON1 ? "LEFT"
                       : button == MouseEvent.BUTTON3 ? "RIGHT" : "MIDDLE";
            System.out.printf("Cell clicked: col=%d  row=%d  button=%s%n", col, row, btn);
        });

        setScrollListener((sx, sy, delta) ->
                System.out.printf("Scroll  screen(%d,%d)  delta=%d%n", sx, sy, delta));

        setKeyPressedListener(keyCode -> {
            if (keyCode == KeyEvent.VK_SPACE) {
                togglePause();
                System.out.println(isPaused() ? "PAUSED" : "RESUMED");
            }
        });
    }

    // =========================================================================
    // tick – called every tickIntervalMs (skipped while paused)
    // =========================================================================

    @Override
    protected void tick() {
        waveTick++;
        // Example: move camera with WASD – handled via onKeyPressed below
    }

    // =========================================================================
    // onDraw – HUD / overlay (runs every render tick, even while paused)
    // =========================================================================

    @Override
    protected void onDraw(Graphics2D gfx) {
        // Simple HUD
        gfx.setColor(Color.WHITE);
        gfx.drawString(isPaused() ? "PAUSED  (SPACE to resume)" : "SPACE = pause", 8, 18);
        gfx.drawString("WASD = move camera focus", 8, 34);
        gfx.drawString("Right-drag or edge = scroll viewport", 8, 50);
        gfx.drawString(String.format("Camera cell: %d, %d", camCol, camRow), 8, 66);
    }

    // =========================================================================
    // Keyboard input
    // =========================================================================

    @Override
    protected void onKeyPressed(int keyCode) {
        int step = 1;
        switch (keyCode) {
            case KeyEvent.VK_W, KeyEvent.VK_UP    -> camRow = Math.max(0, camRow - step);
            case KeyEvent.VK_S, KeyEvent.VK_DOWN  -> camRow = Math.min(options.mapHeightCells - 1, camRow + step);
            case KeyEvent.VK_A, KeyEvent.VK_LEFT  -> camCol = Math.max(0, camCol - step);
            case KeyEvent.VK_D, KeyEvent.VK_RIGHT -> camCol = Math.min(options.mapWidthCells - 1, camCol + step);
        }
        setCenterToCell(camCol, camRow);
    }

    // =========================================================================
    // Entry point
    // =========================================================================

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            ExampleGame engine = new ExampleGame();
            GameCanvas  canvas = new GameCanvas(engine);

            JFrame frame = new JFrame("TileGameEngine – Example");
            frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
            frame.setLayout(new BorderLayout());
            frame.add(canvas, BorderLayout.CENTER);
            frame.setSize(1024, 640);
            frame.setLocationRelativeTo(null);
            frame.setVisible(true);

            // Start AFTER the frame is visible so the canvas has a real size
            engine.start(canvas);
        });
    }
}
