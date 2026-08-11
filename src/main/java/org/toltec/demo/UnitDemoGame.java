package org.toltec.demo;

import org.toltec.engine.EngineOptions;
import org.toltec.engine.GameCanvas;
import org.toltec.engine.GpuAcceleration;
import org.toltec.engine.MapCell;
import org.toltec.engine.PathFinder;
import org.toltec.engine.TileGameEngine;
import org.toltec.render.GraphicObject;
import org.toltec.render.TileTextureConfig;
import org.toltec.unit.Damageable;
import org.toltec.unit.Gender;
import org.toltec.unit.Goblin;
import org.toltec.unit.Player;
import org.toltec.unit.Unit;
import org.toltec.unit.UnitAnimationConfig;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.event.KeyEvent;
import java.awt.event.MouseEvent;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import javax.swing.JFrame;
import javax.swing.SwingUtilities;

/**
 * Example: click the unit's own sprite to select it (a thin green outline
 * appears around it — see {@link EngineOptions#selectionOutlineColor}), then
 * click any other walkable cell to send it there, or click a goblin to walk
 * up to and attack it. The unit paths around the obstacle wall on its own
 * ({@link PathFinder}), and its remaining route is drawn automatically while
 * it's moving (see {@link Unit#showTrajectory}). Hovering any unit outlines
 * it too (red for goblins, blue for your own unit).
 *
 * ANIM_CONFIG_PATH points at an ini file in {@link UnitAnimationConfig}'s
 * format (see units-example.ini). Until that file exists — or is missing
 * some entries — the unit just draws as the engine's usual placeholder
 * diamond, so you can try selection/pathfinding before any art is ready.
 */
public class UnitDemoGame extends TileGameEngine {

    // This is a CLASSPATH resource path, not a filesystem path — it resolves against
    // src/main/resources (once compiled, target/classes), regardless of the process's
    // working directory. No need for an absolute "Z:\..." path here — see
    // UnitAnimationConfig#load.
    private static final String ANIM_CONFIG_PATH = "/units.ini";

    // Same deal — classpath resource, see TileTextureConfig#load.
    private static final String TILE_CONFIG_PATH = "/tiles.ini";

    private Player       player;
    private boolean      playerSelected = false;
    private String       lastMessage = "";
    private final List<Goblin> goblins = new ArrayList<>();
    private final Random rng = new Random();

    // Unit the player is following: walks up to it every tick it isn't
    // already adjacent, and attacks once it's in range — kept until the
    // target dies, the player is deselected, or a different destination/
    // target is clicked. See updateFollow() below.
    private Unit followTarget;
    // Tracks the last Unit.AttackResult object we've already shown, so the HUD message
    // updates exactly once per finished swing (landHit() runs async, when the ATTACK
    // animation completes, not synchronously after attack() is called) — see tick().
    private Unit.AttackResult lastShownAttackResult;

    // ── WASD held-key movement ───────────────────────────────────────────────
    // Written from the AWT event thread (keyPressed/keyReleased), read from the
    // logic thread inside stepWasd()/the destination-reached listener — plain
    // int keycodes in a concurrent set, no extra locking needed.
    private final Set<Integer> heldKeys = ConcurrentHashMap.newKeySet();

    public UnitDemoGame() {
        super(buildOptions());
    }

    private static EngineOptions buildOptions() {
        EngineOptions o = new EngineOptions();
        o.mapWidthCells    = 20;
        o.mapHeightCells   = 20;
        o.cellWidth        = 64;
        o.cellHeight       = 32;
        o.viewType         = EngineOptions.ViewType.ISOMETRIC;
        o.tickIntervalMs   = 10;
        o.renderIntervalMs = 10;
        return o;
    }

    // =========================================================================
    // onStart – build the map, load the unit, wire up click-to-select/move
    // =========================================================================

    @Override
    protected void onStart() {
        setCenterToCell(options.mapWidthCells / 2, options.mapHeightCells / 2);

        // ── Floor tiles (see tiles.ini / TileTextureConfig) ───────────────────
        TileTextureConfig tileConfig = new TileTextureConfig(assets);
        try {
            tileConfig.load(TILE_CONFIG_PATH);
        } catch (IOException e) {
            e.printStackTrace();
            System.err.println("UnitDemoGame: couldn't load " + TILE_CONFIG_PATH
                    + " (" + e.getMessage() + ") — floor tiles will draw as placeholder diamonds.");
        }

        // ── Floor + a wall with a single gap, so the pathfinder has to route around it ──
        for (int row = 0; row < options.mapHeightCells; row++) {
            for (int col = 0; col < options.mapWidthCells; col++) {
                GraphicObject floor = buildFloorTile(tileConfig, col, row);
                floor.drawWidth  = options.cellWidth;
                floor.drawHeight = options.cellHeight;
                getCell(col, row).addObject(floor);
            }
        }
        int wallRow = options.mapHeightCells / 2 - 3;
        for (int col = 3; col < options.mapWidthCells - 3; col++) {
            if (col == options.mapWidthCells / 2) continue; // gap in the wall
            GraphicObject rock = new GraphicObject("rock", 1, true);
            rock.drawWidth  = options.cellWidth  / 2;
            rock.drawHeight = options.cellHeight * 2;
            getCell(col, wallRow).addObject(rock);
        }

        // ── Load unit animations & spawn the player ──────────────────────────
        UnitAnimationConfig animConfig = new UnitAnimationConfig(assets);
        try {
            animConfig.load(ANIM_CONFIG_PATH);
        } catch (IOException e) {
            e.printStackTrace();
            System.err.println("UnitDemoGame: couldn't load " + ANIM_CONFIG_PATH
                    + " (" + e.getMessage() + ") — the unit will draw as a placeholder until it exists.");
        }

        player = new Player(animConfig, Gender.FEMALE, "sword", 100);
        player.setIsometricType();
        player.fitToCell = true;
        player.layer = 5;
        // Combat rolls (hitChance/dodgeChance/blockChance/attackInterruptChance) are configured
        // in units.ini now (hitChance[woman][sword][*][*] etc — see the comment there for why
        // attackInterruptChance in particular matters for a slow attack) rather than hardcoded
        // here, so tuning them doesn't need a recompile. If units.ini is missing/incomplete,
        // Unit's own field defaults apply (1.0/0/0/1.0 — every attack always connects, and any
        // damage taken always cancels whatever this unit is doing, matching the old hard-coded
        // behaviour) — see Unit#hitChance and friends.
        player.placeOn(this, options.mapWidthCells / 2, options.mapHeightCells / 2 + 5);
        setSelfUnit(player); // hovering your own unit gets selfHoverOutlineColor instead of hoverOutlineColor

        // Prints every status/direction/cell change plus a per-tick snapshot
        // (status, animation frame, direction, coordinates) to stdout while
        // the player moves — see Unit#setDebugLogging. Toggle off with
        // player.setDebugLogging(false) once you've found what you needed.
        player.setDebugLogging(true);

        // Chains straight into the next cell the instant this one finishes —
        // see setDestinationReachedListener's docs on Unit for why this is
        // what stops the walk cycle stuttering back to frame 0 every cell
        // while a movement key is held (the old naive way — poll isMoving()
        // from tick() and call moveTo() again once it's false — always dips
        // through IDLE in between, which does legitimately reset the frame).
        player.setDestinationReachedListener(this::stepWasd);

        // ── Goblins: wander the map on their own, fight back if attacked ─────
        for (int i = 0; i < 4; i++) {
            Goblin goblin = new Goblin(animConfig, "unarmed", 50);
            goblin.setIsometricType();
            goblin.fitToCell = true;
            goblin.layer = 5;
            placeGoblinRandomly(goblin);
            goblins.add(goblin);
        }

        // ── Click a unit's sprite to select it (player) or target it (goblin);
        //    click empty ground to walk there ────────────────────────────────
        setUnitClickListener((unit, button) -> {
            if (button != MouseEvent.BUTTON1) return;

            if (unit == player) {
                setPlayerSelected(/*!playerSelected*/true);
                return;
            }

            if (!playerSelected) return;

            if (unit instanceof Goblin g && g.isAlive()) {
                if (isAdjacent(g)) {
                    // Already close enough: land exactly one hit, right now — no follow needed.
                    followTarget = null;
                    player.attack(g, 20);
                    lastMessage = "Attacking goblin...";
                } else {
                    // Too far: walk up to it, then land exactly one hit once adjacent — see updateFollow().
                    followTarget = g;
                    lastMessage = "Moving to goblin...";
                    moveAdjacentTo(g);
                }
            }
        });

        // Click on open ground (no unit under the cursor) — walk there and drop any follow target.
        setCellClickListener((col, row, button) -> {
            if (button != MouseEvent.BUTTON1) return;
            if (!playerSelected) return;

            followTarget = null;
            boolean found = player.moveTo(col, row);
            lastMessage = found
                    ? "Moving to (" + col + "," + row + ")"
                    : "No path to (" + col + "," + row + ")";
        });
    }

    /**
     * Builds one floor tile — looks up the demo layout's tile type for
     * (col,row) (see {@link #pickDemoTileType}) and asks {@code tileConfig}
     * for a ready-to-place {@link GraphicObject} (walkable/speed/damage
     * already set from tiles.ini — see {@link TileTextureConfig#createFloorObject}).
     * Falls back to the engine's old placeholder-diamond floor if tiles.ini
     * failed to load (isFloor set, but nothing configured to draw/collide).
     */
    private GraphicObject buildFloorTile(TileTextureConfig tileConfig, int col, int row) {
        String type = pickDemoTileType(col, row);
        if (tileConfig.has(type)) return tileConfig.createFloorObject(type, col, row);

        GraphicObject floor = new GraphicObject("grass", 0, false);
        floor.isFloor = true;
        return floor;
    }

    /**
     * Just for this demo: mostly stone, with a damaging dirtTiles patch
     * (see tiles.ini damagePerSecond[dirtTiles]), a slow dirt patch
     * (speedMultiplier[dirt]), and a couple of impassable broken-plank
     * tiles (walkable[planksBroken]=false) scattered in so you can see and
     * feel all three parameters in action.
     */
    private String pickDemoTileType(int col, int row) {
        if (row >= 2 && row <= 4 && col >= 2 && col <= 4)   return "dirtTiles";   // hurts to stand on
        if (row >= 6 && row <= 8 && col >= 14 && col <= 16) return "dirt";        // slows you down
        if (row == 10 && (col == 10 || col == 11))          return "planksBroken"; // impassable
        return "brickFloor1";
    }

    private void setPlayerSelected(boolean selected) {
        playerSelected = selected;
        setSelectedUnit(selected ? player : null); // engine auto-draws the outline
        lastMessage = selected ? "Unit selected" : "Unit deselected";
    }

    private boolean isAdjacent(Unit other) {
        int dist = Math.max(Math.abs(other.getCol() - player.getCol()), Math.abs(other.getRow() - player.getRow()));
        return dist <= 1;
    }

    /**
     * Walks the player to a free cell adjacent to {@code target} (never onto
     * the target's own cell). Tries every one of the 8 neighbouring cells,
     * closest to the player first, and moves to the first one a path exists
     * to. Returns false (player stays put) if none are reachable.
     */
    private boolean moveAdjacentTo(Unit target) {
        int tc = target.getCol(), tr = target.getRow();

        List<int[]> candidates = new ArrayList<>();
        for (int dc = -1; dc <= 1; dc++) {
            for (int dr = -1; dr <= 1; dr++) {
                if (dc == 0 && dr == 0) continue;
                int c = tc + dc, r = tr + dr;
                if (!isCellValid(c, r)) continue;
                MapCell cell = getCell(c, r);
                if (cell != null && cell.hasCollision()) continue;
                candidates.add(new int[]{c, r});
            }
        }

        candidates.sort((a, b) -> {
            int da = Math.abs(a[0] - player.getCol()) + Math.abs(a[1] - player.getRow());
            int db = Math.abs(b[0] - player.getCol()) + Math.abs(b[1] - player.getRow());
            return Integer.compare(da, db);
        });

        for (int[] cand : candidates) {
            if (player.moveTo(cand[0], cand[1])) return true;
        }
        return false;
    }

    private void placeGoblinRandomly(Goblin goblin) {
        for (int attempt = 0; attempt < 50; attempt++) {
            int col = rng.nextInt(options.mapWidthCells);
            int row = rng.nextInt(options.mapHeightCells);
            MapCell cell = getCell(col, row);
            if (cell != null && !cell.hasCollision()) {
                goblin.placeOn(this, col, row);
                return;
            }
        }
        goblin.placeOn(this, 0, 0); // fallback, shouldn't normally happen
    }

    @Override
    protected void onKeyPressed(int keyCode) {
        if (keyCode == KeyEvent.VK_R) {
            player.toggleRunning();
            lastMessage = player.isRunning() ? "Running" : "Walking";
            return;
        }
        if (isWasdKey(keyCode)) {
            heldKeys.add(keyCode);
            if (!player.isMoving()) stepWasd(); // was standing still: kick off immediately
        }
    }

    @Override
    protected void onKeyReleased(int keyCode) {
        heldKeys.remove(keyCode);
    }

    private static boolean isWasdKey(int keyCode) {
        return switch (keyCode) {
            case KeyEvent.VK_W, KeyEvent.VK_A, KeyEvent.VK_S, KeyEvent.VK_D,
                 KeyEvent.VK_UP, KeyEvent.VK_DOWN, KeyEvent.VK_LEFT, KeyEvent.VK_RIGHT -> true;
            default -> false;
        };
    }

    /**
     * Reads whichever WASD/arrow keys are currently held and, if any are,
     * walks the player exactly one cell that way — bound as the player's
     * {@link Unit#setDestinationReachedListener destination-reached listener}
     * so it fires the instant the previous cell finishes, before the unit
     * would otherwise fall back to IDLE. If nothing's held any more, does
     * nothing and lets the unit settle into IDLE normally.
     */
    private void stepWasd() {
        int dc = 0, dr = 0;
        if (heldKeys.contains(KeyEvent.VK_W) || heldKeys.contains(KeyEvent.VK_UP))    dr -= 1;
        if (heldKeys.contains(KeyEvent.VK_S) || heldKeys.contains(KeyEvent.VK_DOWN))  dr += 1;
        if (heldKeys.contains(KeyEvent.VK_A) || heldKeys.contains(KeyEvent.VK_LEFT))  dc -= 1;
        if (heldKeys.contains(KeyEvent.VK_D) || heldKeys.contains(KeyEvent.VK_RIGHT)) dc += 1;
        if (dc == 0 && dr == 0) return; // nothing held: let the default onDestinationReached set IDLE

        followTarget = null; // manual movement always overrides auto-follow
        player.moveTo(player.getCol() + dc, player.getRow() + dr); // uses RUN if toggled, see Player#moveTo
    }

    @Override
    protected void tick() {
        // Player#tick() runs on its own via MapCell#tick() — nothing extra needed here.
        updateFollow();
        showLatestAttackResult();
    }

    /** Surfaces the outcome of the player's most recently finished swing in the HUD — see {@link #lastShownAttackResult}. */
    private void showLatestAttackResult() {
        Unit.AttackResult r = player.getLastAttackResult();
        if (r == null || r == lastShownAttackResult) return;
        lastShownAttackResult = r;
        lastMessage = switch (r.outcome()) {
            case HIT     -> "Hit! " + r.damage() + " damage";
            case MISS    -> "Missed!";
            case DODGED  -> "Goblin dodged!";
            case BLOCKED -> "Blocked!";
        };
    }

    /**
     * Walks the player up to {@link #followTarget} while it isn't adjacent yet
     * (re-pathing once idle, so a wandering goblin actually gets followed
     * rather than walked to a stale spot), then lands exactly one hit the
     * moment it's in range and stops — see the click handler above for why
     * attacking isn't automatic/repeating: one click, one swing.
     */
    private void updateFollow() {
        if (followTarget == null) return;

        if (!(followTarget instanceof Damageable dmg) || !dmg.isAlive()) {
            followTarget = null;
            return;
        }
        if (!player.isAlive()) {
            followTarget = null;
            return;
        }

        if (isAdjacent(followTarget)) {
            if (player.isMoving()) player.stopMoving();
            player.attack(followTarget, 20);
            lastMessage = "Attacking goblin...";
            followTarget = null; // one swing per click — don't keep re-attacking automatically
        } else if (!player.isMoving()) {
            if (!moveAdjacentTo(followTarget)) {
                lastMessage = "Can't reach goblin";
                followTarget = null;
            }
        }
    }

    // =========================================================================
    // onDraw – HUD only; selection/hover outlines are drawn automatically by
    // the engine (see EngineOptions#selectionOutlineColor / hoverOutlineColor
    // / selfHoverOutlineColor and TileGameEngine#setSelectedUnit / #setSelfUnit)
    // =========================================================================

    @Override
    protected void onDraw(Graphics2D gfx) {
        gfx.setColor(Color.WHITE);
        gfx.drawString("Click the unit to select it, click elsewhere to send it there", 8, 18);
        gfx.drawString("Select it, then click a goblin to walk up and attack it", 8, 34);
        gfx.drawString("WASD / arrows to walk, R toggles walk/run", 8, 50);
        gfx.drawString("Scroll / +- to zoom, right-drag to pan", 8, 66);
        gfx.drawString(lastMessage, 8, 82);
    }

    // =========================================================================
    // Entry point
    // =========================================================================

    public static void main(String[] args) {
        // See GpuAcceleration for why this must run before any AWT/Swing
        // class is touched.
        GpuAcceleration.enable();

        SwingUtilities.invokeLater(() -> {
            UnitDemoGame engine = new UnitDemoGame();
            GameCanvas   canvas = new GameCanvas(engine);

            JFrame frame = new JFrame("TileGameEngine – Unit / PathFinder demo");
            frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
            frame.setLayout(new BorderLayout());
            frame.add(canvas, BorderLayout.CENTER);
            frame.setSize(1024, 640);
            frame.setLocationRelativeTo(null);
            frame.setVisible(true);

            engine.start(canvas);
        });
    }
}
