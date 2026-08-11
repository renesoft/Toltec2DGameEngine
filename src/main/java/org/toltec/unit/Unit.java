package org.toltec.unit;

import org.toltec.engine.MapCell;
import org.toltec.engine.PathFinder;
import org.toltec.engine.TileGameEngine;
import org.toltec.render.AnimationClip;
import org.toltec.render.GraphicObject;
import org.toltec.render.TileTextureConfig;

import java.util.Collections;
import java.util.List;

/**
 * A {@link GraphicObject} whose displayed image is driven by
 * gender + weapon + {@link UnitStatus} + {@link Direction8}, resolved
 * through a shared {@link UnitAnimationConfig}, and which can walk itself
 * across the map cell-by-cell along a path computed by its own
 * {@link PathFinder}.
 *
 * Use {@link #placeOn} to add a Unit to the map instead of calling
 * {@code MapCell.addObject} directly — the unit needs to track which cell
 * it's in so it can move itself between cells and hand-off cleanly.
 */
public class Unit extends GraphicObject {

    private final UnitAnimationConfig animConfig;
    private final PathFinder          pathFinder = new PathFinder();

    private Gender      gender;
    private String      weapon;
    private UnitStatus  status    = UnitStatus.IDLE;
    private Direction8  direction = Direction8.S;

    private AnimationClip currentClip;
    private int     frameIndex;
    private int     ticksSinceFrame;
    private long    lastFrameTimeNs = -1; // used when the clip is timed in ms rather than ticks
    private boolean finishedNotified;

    // ── Position & movement ──────────────────────────────────────────────────
    private TileGameEngine engine;
    private int    col, row;
    private List<int[]> path = Collections.emptyList();
    private int    pathIndex;
    private double moveProgress; // 0..1 progress crossing into path.get(pathIndex)
    private double moveSpeed = 0.15; // fallback: fraction of a cell crossed per tick (~7 ticks/cell), used only when the walk/run clip has no duration configured
    private long   moveStepStartNs = -1; // wall-clock start of the current cell-crossing, used when the clip is duration-timed
    private double motionDX, motionDY; // current sub-cell slide offset, in map pixels
    private int    anchorXOffset, anchorYOffset; // static sprite-anchor tweak, independent of motion

    // ── Floor tile effects (see TileTextureConfig / GraphicObject#speedMultiplier / #damagePerSecond) ──
    private double tileSpeedMultiplier = 1.0; // read off the floor tile of the cell this unit currently occupies
    private double pendingTileDamage;         // fractional damage/sec accumulator, applied in whole-number chunks

    // ── Debug movement/animation logging — see setDebugLogging ─────────────────
    private boolean debugLogging = false;
    private long    debugLogTick = 0;

    /** Whether the engine should draw this unit's remaining path when it's moving. Default true. */
    public boolean showTrajectory = true;

    // =========================================================================
    // Combat rolls
    // =========================================================================
    //
    // All default to "no randomness, always resolves the old hard-coded way" —
    // hitChance=1 / dodgeChance=0 / blockChance=0 means every attack connects,
    // and attackInterruptChance=1 means taking damage always cancels whatever
    // this unit is doing (including its own in-progress ATTACK swing), exactly
    // like before these existed. Set narrower/lower values per-unit (see
    // Player/Goblin construction in your game) to get dodges, blocks, misses,
    // and swings that survive getting tagged once.

    /** 0.0–1.0 chance this unit's own attacks connect at all — rolled once per swing, see {@link #resolveAttack}. */
    public double hitChance = 1.0;
    /** 0.0–1.0 chance this unit dodges an incoming attack entirely (no damage, checked before hitChance/blockChance). */
    public double dodgeChance = 0.0;
    /** 0.0–1.0 chance this unit blocks an incoming attack that otherwise would have connected, negating its damage. */
    public double blockChance = 0.0;
    /**
     * 0.0–1.0 chance that taking damage while this unit is mid-swing (status
     * {@link UnitStatus#ATTACK}) interrupts the swing — switching it to HIT
     * and forfeiting the hit it would otherwise have landed once the swing
     * finished. Rolled by {@link #rollAttackInterrupted()}, which callers use
     * from their own {@code takeDamage()}. Being hit while <em>not</em>
     * mid-swing (idle/walking/etc) always plays the HIT reaction regardless
     * of this value — there's no swing to protect in that case.
     */
    public double attackInterruptChance = 1.0;

    private static final java.util.Random COMBAT_RNG = new java.util.Random();

    /** Why an attack did or didn't deal damage — see {@link #resolveAttack}. */
    public enum AttackOutcome { HIT, MISS, DODGED, BLOCKED }

    /** The result of resolving one attack swing: how much damage got through, and why. */
    public record AttackResult(AttackOutcome outcome, int damage) {}

    /**
     * Resolves one attack swing from {@code attacker} against {@code defender}
     * using their {@link #dodgeChance}/{@link #hitChance}/{@link #blockChance},
     * checked in that order — a dodge or a miss means block never gets a
     * chance to matter, matching "the defender got out of the way entirely"
     * outranking "they blocked the hit that would have landed". Meant to be
     * called once, from the attacker's own landHit()-equivalent, when its
     * ATTACK animation finishes; apply the returned damage (0 for anything
     * but {@link AttackOutcome#HIT}) via the defender's takeDamage().
     */
    public static AttackResult resolveAttack(Unit attacker, Unit defender, int baseDamage) {
        if (defender != null && COMBAT_RNG.nextDouble() < defender.dodgeChance)
            return new AttackResult(AttackOutcome.DODGED, 0);
        if (attacker != null && COMBAT_RNG.nextDouble() >= attacker.hitChance)
            return new AttackResult(AttackOutcome.MISS, 0);
        if (defender != null && COMBAT_RNG.nextDouble() < defender.blockChance)
            return new AttackResult(AttackOutcome.BLOCKED, 0);
        return new AttackResult(AttackOutcome.HIT, Math.max(0, baseDamage));
    }

    /**
     * Whether taking damage right now should interrupt this unit's current
     * animation. Always {@code true} unless this unit is currently mid-swing
     * ({@link UnitStatus#ATTACK}), in which case it's a roll against
     * {@link #attackInterruptChance} — so a slow/heavy attack has a real
     * chance to survive getting tagged once and still land, instead of every
     * incoming hit unconditionally cancelling it (the old, hard-coded
     * behaviour — still exactly what you get by leaving
     * {@code attackInterruptChance} at its default of 1.0).
     */
    public boolean rollAttackInterrupted() {
        if (status != UnitStatus.ATTACK) return true;
        return COMBAT_RNG.nextDouble() < attackInterruptChance;
    }

    public Unit(UnitAnimationConfig animConfig, Gender gender, String weapon) {
        super(""); // imageName is set below once the initial clip resolves
        this.animConfig = animConfig;
        this.gender     = gender;
        this.weapon     = weapon;
        refreshClip(true);
    }

    // =========================================================================
    // Placement
    // =========================================================================

    /** Adds the unit to the map at (col,row), or moves it there instantly if it was already placed. */
    public void placeOn(TileGameEngine engine, int col, int row) {
        if (this.engine != null) {
            MapCell old = this.engine.getCell(this.col, this.row);
            if (old != null) old.removeObject(this);
        }
        this.engine = engine;
        this.col = col;
        this.row = row;
        path = Collections.emptyList();
        pathIndex = 0;
        moveProgress = 0;
        moveStepStartNs = -1;
        applyMotionOffset(0, 0);

        MapCell cell = engine.getCell(col, row);
        if (cell != null) cell.addObject(this);
    }

    public int getCol() { return col; }
    public int getRow() { return row; }

    /** The engine this unit is placed on, or {@code null} before {@link #placeOn}. Handy for subclasses (AI, etc). */
    protected TileGameEngine getEngine() { return engine; }

    // =========================================================================
    // Look / state
    // =========================================================================

    public Gender     getGender()    { return gender; }
    public String     getWeapon()    { return weapon; }
    public UnitStatus getStatus()    { return status; }
    public Direction8 getDirection() { return direction; }

    public void setGender(Gender g) {
        if (g == gender) return;
        gender = g;
        refreshClip(true);
    }

    public void setWeapon(String w) {
        if (w != null && w.equals(weapon)) return;
        weapon = w;
        refreshClip(true);
    }

    /**
     * Change behavioural state. Restarts the new clip from frame 0 — except
     * when switching between {@link UnitStatus#WALK} and
     * {@link UnitStatus#RUN}, which keeps whatever frame the unit was
     * already showing (so e.g. toggling the run key mid-stride doesn't snap
     * the legs back to the start of the cycle and break the illusion of
     * continuous motion; see {@link #isMovementStatus}).
     */
    public void setStatus(UnitStatus s) {
        if (s == status) return;
        boolean keepFrame = isMovementStatus(status) && isMovementStatus(s);
        log("status %s -> %s (keepFrame=%b, frameIndex=%d)", status, s, keepFrame, frameIndex);
        status = s;
        refreshClip(!keepFrame);
    }

    /** Whether {@code s} is one of the "in-motion" statuses (WALK/RUN) that carry animation frame position across a {@link #setStatus} switch between each other. */
    private static boolean isMovementStatus(UnitStatus s) {
        return s == UnitStatus.WALK || s == UnitStatus.RUN;
    }

    /** Change facing. Keeps the current frame position (e.g. mid-walk-cycle). */
    public void setDirection(Direction8 d) {
        if (d == direction) return;
        log("direction %s -> %s (frameIndex=%d)", direction, d, frameIndex);
        direction = d;
        refreshClip(false);
    }

    public void setDirectionFromAngle(double degrees) {
        setDirection(Direction8.fromAngle(degrees));
    }

    /** Face the direction of travel; used automatically while following a path. */
    public void setDirectionFromVector(double dx, double dy) {
        setDirection(Direction8.fromVector(dx, dy));
    }

    /** The clip currently backing this unit's animation, or {@code null} if nothing in the config matches. */
    public AnimationClip getCurrentClip() { return currentClip; }

    /** Static per-unit sprite-anchor tweak (e.g. foot alignment), independent of movement sliding. */
    public void setAnchorOffset(int x, int y) {
        anchorXOffset = x;
        anchorYOffset = y;
        applyMotionOffset(motionDX, motionDY);
    }

    // =========================================================================
    // Movement / pathfinding
    // =========================================================================

    /** Equivalent to {@code moveTo(targetCol, targetRow, UnitStatus.WALK)}. */
    public boolean moveTo(int targetCol, int targetRow) {
        return moveTo(targetCol, targetRow, UnitStatus.WALK);
    }

    /**
     * Computes a path to (targetCol,targetRow) avoiding cells whose
     * {@link MapCell#hasCollision()} is true, and starts walking it,
     * switching to {@code movingStatus} (e.g. WALK or RUN) immediately.
     *
     * @return true if a path was found and the unit started moving; false
     *         if the target is unreachable (unit stays where it is).
     */
    public boolean moveTo(int targetCol, int targetRow, UnitStatus movingStatus) {
        if (engine == null)
            throw new IllegalStateException("Unit must be placed on the map first — call placeOn()");

        boolean found = pathFinder.findPath(this::isWalkable, col, row, targetCol, targetRow);
        if (found) {
            List<int[]> newPath = pathFinder.getPath();

            // If the unit is already mid-slide into a cell, and this new path's
            // very first step is that exact same cell (typical when game code
            // re-issues moveTo() every tick — held-key movement, chasing a
            // moving target, re-clicking the same destination, etc.), keep the
            // in-progress crossing's timing instead of snapping moveProgress
            // back to 0. Without this, every one of those re-issued calls
            // yanks the slide back to the start of the current cell, which
            // reads as the sprite jumping backward mid-stride instead of
            // gliding smoothly through it.
            boolean continuingSameStep = isMoving() && !newPath.isEmpty()
                    && path.get(pathIndex)[0] == newPath.get(0)[0]
                    && path.get(pathIndex)[1] == newPath.get(0)[1];

            log("moveTo(%d,%d,%s) called at cell=(%d,%d) moveProgress=%.3f isMoving=%b -> continuingSameStep=%b, newPathLen=%d",
                    targetCol, targetRow, movingStatus, col, row, moveProgress, isMoving(), continuingSameStep, newPath.size());

            path = newPath;
            pathIndex = 0;
            if (!continuingSameStep) {
                moveProgress = 0;
                moveStepStartNs = -1;
            }
            if (!path.isEmpty()) {
                setStatus(movingStatus);
                faceNextStep();
            }
        } else {
            log("moveTo(%d,%d,%s) — no path found from (%d,%d)", targetCol, targetRow, movingStatus, col, row);
            onPathNotFound(targetCol, targetRow);
        }
        return found;
    }

    /** Stops wherever the unit currently is and switches to IDLE. */
    public void stopMoving() {
        log("stopMoving() at cell=(%d,%d) moveProgress=%.3f", col, row, moveProgress);
        path = Collections.emptyList();
        pathIndex = 0;
        moveProgress = 0;
        moveStepStartNs = -1;
        applyMotionOffset(0, 0);
        setStatus(UnitStatus.IDLE);
    }

    public boolean isMoving() { return pathIndex < path.size(); }

    /** Cells still ahead of the unit on its current path (empty if not moving). */
    public List<int[]> getRemainingPath() {
        if (pathIndex >= path.size()) return Collections.emptyList();
        return path.subList(pathIndex, path.size());
    }

    /**
     * Fallback movement speed used only when the current walk/run clip has no
     * {@code duration[...]} configured (see {@link UnitAnimationConfig}). Ticks it
     * takes to fully cross one cell is roughly {@code 1/cellsPerTick}; higher = faster.
     */
    public void setMoveSpeed(double cellsPerTick) { moveSpeed = Math.max(0.001, cellsPerTick); }

    /** Current sub-cell slide offset in map pixels (used by the engine to draw the trajectory from the right spot). */
    public double getMotionDX() { return motionDX; }
    public double getMotionDY() { return motionDY; }

    private boolean isWalkable(int c, int r) {
        if (!engine.isCellValid(c, r)) return false;
        MapCell cell = engine.getCell(c, r);
        return cell == null || !cell.hasCollision();
    }

    /**
     * Faces the direction of the next path step, computed in screen space
     * (via {@link TileGameEngine#cellScreenDelta}) rather than raw grid
     * coordinates. For isometric maps these differ: a single grid step (say,
     * col-1) actually renders as a diagonal on-screen move, so using the raw
     * grid delta here would pick the wrong one of the 8 sprite directions.
     * Screen-space delta makes angle 0 line up with "sprite visibly walks
     * toward the top of the screen", matching how directional sprite sheets
     * are normally authored/read.
     */
    private void faceNextStep() {
        if (pathIndex >= path.size()) return;
        int[] next = path.get(pathIndex);
        double[] delta = engine.cellScreenDelta(col, row, next[0], next[1]);
        setDirectionFromVector(delta[0], delta[1]);
    }

    private void advanceToCell(int newCol, int newRow) {
        log("cell (%d,%d) -> (%d,%d)", col, row, newCol, newRow);
        MapCell oldCell = engine.getCell(col, row);
        if (oldCell != null) oldCell.removeObject(this);
        col = newCol;
        row = newRow;
        MapCell newCell = engine.getCell(col, row);
        if (newCell != null) newCell.addObject(this);
    }

    private void applyMotionOffset(double dx, double dy) {
        motionDX = dx;
        motionDY = dy;
        xOffset = anchorXOffset + (int) Math.round(dx);
        yOffset = anchorYOffset + (int) Math.round(dy);
    }

    /**
     * Advances the unit across the current cell of its path.
     *
     * If the active clip (the walk/run animation for the current direction)
     * has a configured {@link AnimationClip#durationMs()}, crossing one cell
     * takes exactly that many wall-clock milliseconds — the same duration
     * the animation itself plays over — so the sprite's feet and the walk
     * cycle never drift apart. Otherwise falls back to the legacy
     * tick-based {@code moveSpeed}.
     */
    private void stepAlongPath() {
        if (pathIndex >= path.size()) return;

        int[] target = path.get(pathIndex);

        // tileSpeedMultiplier (see applyTileEffects/GraphicObject#speedMultiplier) scales however
        // long crossing this cell takes: below 1.0 stretches the duration / shrinks the per-tick
        // progress step, i.e. slower, matching e.g. dirt's speedMultiplier=0.5 taking twice as long.
        double speedMul = Math.max(0.01, tileSpeedMultiplier);

        int cellDurationMs = currentClip != null ? currentClip.durationMs() : 0;
        if (cellDurationMs > 0) {
            long now = System.nanoTime();
            if (moveStepStartNs < 0) moveStepStartNs = now;
            long elapsedMs = (now - moveStepStartNs) / 1_000_000L;
            moveProgress = Math.min(1.0, elapsedMs / (cellDurationMs / speedMul));
        } else {
            moveProgress += moveSpeed * speedMul;
        }

        if (moveProgress >= 1.0) {
            moveProgress = 0;
            moveStepStartNs = -1;
            advanceToCell(target[0], target[1]);
            pathIndex++;
            if (pathIndex >= path.size()) {
                path = Collections.emptyList();
                applyMotionOffset(0, 0);
                onDestinationReached();
                return;
            }
            faceNextStep();
            applyMotionOffset(0, 0);
            return;
        }

        double[] delta = engine.cellScreenDelta(col, row, target[0], target[1]);
        applyMotionOffset(delta[0] * moveProgress, delta[1] * moveProgress);
    }

    private Runnable destinationReachedListener;

    /**
     * Set this instead of subclassing {@link #onDestinationReached} when you
     * just want to chain the next move — e.g. continuous WASD/held-key
     * movement, where every cell border calls {@code moveTo} again for the
     * next cell in the same direction. Fires right before the default
     * IDLE switch, and if it starts a new move (so {@link #isMoving()}
     * becomes true), the IDLE switch is skipped entirely: since the new
     * move keeps the same WALK/RUN status, {@link #setStatus} never even
     * fires and the walk cycle's current frame carries straight through —
     * no more per-cell stutter from bouncing WALK → IDLE → WALK every
     * time a held key is re-evaluated.
     */
    public void setDestinationReachedListener(Runnable r) { destinationReachedListener = r; }

    /**
     * Called once the unit reaches the end of its path. Default: switch to
     * IDLE — unless {@link #destinationReachedListener} (fired first) already
     * queued another move, in which case the unit is still mid-walk and
     * switching to IDLE would just reset the animation for no reason. Override
     * for something other than a plain listener; call {@code super} last if
     * you also want the "skip IDLE when already moving again" behaviour.
     */
    protected void onDestinationReached() {
        log("onDestinationReached at cell=(%d,%d)", col, row);
        if (destinationReachedListener != null) destinationReachedListener.run();
        if (!isMoving()) setStatus(UnitStatus.IDLE);
    }

    /** Called if {@link #moveTo} couldn't find a walkable path. No-op by default. */
    protected void onPathNotFound(int targetCol, int targetRow) {}

    // =========================================================================
    // Animation stepping
    // =========================================================================

    private void refreshClip(boolean resetFrame) {
        AnimationClip old = currentClip;
        currentClip = animConfig.resolve(gender, weapon, status, direction);
        finishedNotified = false;
        if (resetFrame) {
            frameIndex      = 0;
            ticksSinceFrame = 0;
            lastFrameTimeNs = -1; // restart the wall-clock frame timer too
        }
        log("refreshClip(resetFrame=%b) status=%s dir=%s clip %s -> %s, frameIndex=%d",
                resetFrame, status, direction,
                old == null ? "null" : old.getClass().getSimpleName() + "@" + Integer.toHexString(System.identityHashCode(old)),
                currentClip == null ? "null" : currentClip.getClass().getSimpleName() + "@" + Integer.toHexString(System.identityHashCode(currentClip)),
                frameIndex);
        applyCurrentFrame();
    }

    private void applyCurrentFrame() {
        if (currentClip == null) return; // no matching config entry — keep last known image
        if (frameIndex >= currentClip.frameCount()) frameIndex = currentClip.frameCount() - 1;
        imageName = currentClip.frame(frameIndex);

        // Config-driven overrides (see scale[...] / sizeCells[...] / fitToCell[...] /
        // hitChance[...] / dodgeChance[...] / blockChance[...] / attackInterruptChance[...]
        // in UnitAnimationConfig) — each is only
        // applied when the resolved clip actually carries a value (i.e. some line in the ini
        // configured it, possibly via a wildcard). A clip with none of these set leaves the
        // unit's current values exactly as they are — whatever the game already assigned in
        // Java (e.g. `player.hitChance = 0.9;` in onStart()) — instead of silently resetting
        // them back to hard-coded defaults every time the clip refreshes (which happens on
        // basically every status/direction change).
        //
        // fitScale defaults to 1.0 and fitToCell to false (GraphicObject's own defaults) when
        // nothing configures them; the combat fields default to whatever the game set (or
        // their own class defaults — see their declarations above) when nothing configures them.
        if (currentClip.scale()                 != null) fitScale               = currentClip.scale();
        if (currentClip.sizeCells()              != null) { footprintCols = currentClip.sizeCells(); footprintRows = currentClip.sizeCells(); }
        if (currentClip.fitToCell()              != null) fitToCell             = currentClip.fitToCell();
        if (currentClip.hitChance()              != null) hitChance             = currentClip.hitChance();
        if (currentClip.dodgeChance()            != null) dodgeChance           = currentClip.dodgeChance();
        if (currentClip.blockChance()            != null) blockChance           = currentClip.blockChance();
        if (currentClip.attackInterruptChance()  != null) attackInterruptChance = currentClip.attackInterruptChance();
    }

    @Override
    public void tick() {
        stepAnimation();
        applyTileEffects();
        stepAlongPath();
        logTickSnapshot();
    }

    // =========================================================================
    // Debug logging — see setDebugLogging
    // =========================================================================

    /**
     * Turns on a per-event/per-tick movement+animation log to stdout for this
     * unit — status/direction changes, cell crossings, {@link #moveTo} calls
     * (including whether a re-issued call preserved or reset the in-progress
     * slide), clip refreshes (with the resolved {@link AnimationClip}'s
     * identity, so you can see if the config is unexpectedly resolving a
     * *different* clip object for what should be the same walk cycle), and
     * one snapshot line per tick while moving. Off by default — this is
     * chatty, meant for tracking down exactly the kind of "stutters and I
     * can't tell why" issue this exists for. Call {@code player.setDebugLogging(true)}
     * (or on whichever Unit you're chasing the bug on) and watch stdout while
     * it happens.
     */
    public void setDebugLogging(boolean enabled) { debugLogging = enabled; }

    private void log(String fmt, Object... args) {
        if (!debugLogging) return;
        System.out.printf("[unit-log t=%d] " + fmt + "%n",
                prepend(debugLogTick, args));
    }

    private static Object[] prepend(Object first, Object[] rest) {
        Object[] out = new Object[rest.length + 1];
        out[0] = first;
        System.arraycopy(rest, 0, out, 1, rest.length);
        return out;
    }

    /** One line per tick while moving (or once right after arriving) — the numeric ground truth to line up against the event lines above. */
    private void logTickSnapshot() {
        if (!debugLogging) return;
        debugLogTick++;
        if (!isMoving() && status != UnitStatus.WALK && status != UnitStatus.RUN) return; // skip idle spam
        String clipFrame = currentClip != null ? currentClip.frame(Math.min(frameIndex, currentClip.frameCount() - 1)) : "null";
        log("tick status=%s dir=%s cell=(%d,%d) frame=%d/%s clipFrame=%s moveProgress=%.3f motion=(%.1f,%.1f) pathIdx=%d/%d",
                status, direction, col, row,
                frameIndex, currentClip != null ? currentClip.frameCount() : -1,
                clipFrame, moveProgress, motionDX, motionDY, pathIndex, path.size());
    }

    /**
     * Reads {@link GraphicObject#speedMultiplier}/{@link GraphicObject#damagePerSecond}
     * off the floor tile of the cell this unit currently occupies (see
     * {@link MapCell#getFloorObject()}) — floor tiles built via
     * {@link TileTextureConfig#createFloorObject} carry these. Speed feeds into
     * {@link #stepAlongPath()}; damage is applied here directly, in whole-number
     * chunks accumulated from the per-tick fractional amount (so e.g. 5 damage/sec
     * at a 6ms tick still adds up correctly rather than rounding away to zero).
     */
    private void applyTileEffects() {
        if (engine == null) return;
        MapCell cell = engine.getCell(col, row);
        GraphicObject floor = cell != null ? cell.getFloorObject() : null;

        tileSpeedMultiplier = floor != null ? floor.speedMultiplier : 1.0;

        double dps = floor != null ? floor.damagePerSecond : 0.0;
        if (dps <= 0 || !(this instanceof Damageable dmg) || !dmg.isAlive()) return;

        pendingTileDamage += dps * (engine.options.tickIntervalMs / 1000.0);
        int whole = (int) pendingTileDamage;
        if (whole > 0) {
            pendingTileDamage -= whole;
            dmg.takeDamage(whole, null);
        }
    }

    private void stepAnimation() {
        if (currentClip == null || currentClip.frameCount() <= 1) return;

        if (currentClip.msPerFrame() > 0) {
            // Fixed wall-clock speed: frames advance every msPerFrame regardless of
            // how often (or irregularly) tick() is actually called.
            long now = System.nanoTime();
            if (lastFrameTimeNs < 0) lastFrameTimeNs = now;
            long elapsedMs = (now - lastFrameTimeNs) / 1_000_000L;
            if (elapsedMs < currentClip.msPerFrame()) return;
            lastFrameTimeNs = now;
        } else {
            // Legacy behaviour: advance every N logic ticks.
            if (++ticksSinceFrame < currentClip.ticksPerFrame()) return;
            ticksSinceFrame = 0;
        }

        advanceFrame();
    }

    private void advanceFrame() {
        int next = frameIndex + 1;
        if (next >= currentClip.frameCount()) {
            if (currentClip.isLoop()) {
                frameIndex = 0;
            } else {
                frameIndex = currentClip.frameCount() - 1;
                if (!finishedNotified) {
                    finishedNotified = true;
                    onAnimationFinished(status);
                }
            }
        } else {
            frameIndex = next;
        }
        applyCurrentFrame();
    }

    /**
     * Called once when a non-looping clip (attack, hit, falling, dying, …)
     * reaches its last frame and holds there. Override to chain into the
     * next status — e.g. ATTACK → IDLE, or DYING → LYING (see {@link Player}).
     */
    protected void onAnimationFinished(UnitStatus finishedStatus) {}
}
