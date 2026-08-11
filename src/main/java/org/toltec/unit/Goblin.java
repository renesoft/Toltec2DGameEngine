package org.toltec.unit;

import org.toltec.engine.TileGameEngine;

import java.util.Random;

/**
 * Hostile NPC unit.
 *
 * <ul>
 *   <li>When left alone it wanders the map at random (picks a random nearby
 *       walkable cell every few seconds and walks there) — see {@link #wanderTick()}.</li>
 *   <li>When hit via {@link #takeDamage(int, Unit)} it remembers the attacker and
 *       switches to combat: it walks up to them and attacks back on a cooldown
 *       until either it or the attacker dies — see {@link #combatTick()}.</li>
 *   <li>Its current health / max health are exposed via {@link Damageable}, which
 *       {@link TileGameEngine} uses to automatically draw a health bar above it.</li>
 * </ul>
 *
 * AI runs from {@link #tick()}, which piggy-backs on {@link Unit#tick()} — no
 * extra wiring is needed beyond placing the goblin on the map with {@link #placeOn}.
 */
public class Goblin extends Unit implements Damageable {

    private static final Random RNG = new Random();

    // ── Health ────────────────────────────────────────────────────────────────
    private final int     maxHealth;
    private int           health;
    private boolean       alive = true;

    // ── Wandering ─────────────────────────────────────────────────────────────
    private static final int WANDER_MIN_TICKS = 40;
    private static final int WANDER_MAX_TICKS = 120;
    private static final int WANDER_RADIUS    = 5;
    private int wanderCooldownTicks;

    // ── Combat ────────────────────────────────────────────────────────────────
    private static final int ATTACK_RANGE      = 1;  // chebyshev distance counted as "adjacent"
    private static final int ATTACK_COOLDOWN   = 20;  // ticks between attack swings
    private static final int ATTACK_DAMAGE     = 8;

    private Unit target;
    private int  attackCooldownTicks;
    private AttackResult lastAttackResult; // last resolveAttack() outcome, for game code / HUD feedback

    public Goblin(UnitAnimationConfig animConfig, String weapon, int maxHealth) {
        super(animConfig, Gender.GOBLIN, weapon);
        this.maxHealth = maxHealth;
        this.health    = maxHealth;
        rollNextWanderDelay();
    }

    // =========================================================================
    // Damageable
    // =========================================================================

    @Override public int     getHealth()    { return health; }
    @Override public int     getMaxHealth() { return maxHealth; }
    @Override public boolean isAlive()      { return alive; }

    /**
     * Aggro the instant {@code attacker} starts swinging at us — don't wait for
     * the hit to actually land (that's {@link #takeDamage}). Doesn't interrupt
     * whatever animation is currently playing; {@link #combatTick()} picks the
     * fight up on the next tick it's free to make a decision.
     */
    @Override
    public void notifyAttacked(Unit attacker) {
        if (!alive || attacker == null) return;
        target = attacker;
    }

    /** Apply damage and start fighting back against {@code attacker} until one of us dies. */
    @Override
    public void takeDamage(int amount, Unit attacker) {
        if (!alive) return;
        health = Math.max(0, health - amount);
        if (attacker != null) target = attacker;

        if (health == 0) {
            target = null;
            setStatus(UnitStatus.DYING);
            return;
        }

        // Only actually interrupts an in-progress ATTACK swing, and even then only
        // per attackInterruptChance — see Unit#rollAttackInterrupted(). Being hit
        // while not mid-swing always plays the HIT reaction (and cancels any
        // in-progress move) as before.
        if (rollAttackInterrupted()) {
            stopMoving();
            setStatus(UnitStatus.HIT);
        }
        // else: still mid-swing, kept the animation — landHit() fires normally
        // once it finishes, same as if this hit had never landed.
    }

    /** The outcome (hit/miss/dodged/blocked) of the last swing this goblin finished, or {@code null} before the first one. */
    public AttackResult getLastAttackResult() { return lastAttackResult; }

    // =========================================================================
    // AI
    // =========================================================================

    @Override
    public void tick() {
        super.tick(); // handles animation stepping + path following
        if (!alive) return;

        // Don't make new decisions while a one-shot animation (HIT/ATTACK/DYING) is playing —
        // onAnimationFinished() resumes the AI once it completes.
        UnitStatus s = getStatus();
        if (s == UnitStatus.HIT || s == UnitStatus.DYING || s == UnitStatus.ATTACK) return;

        if (target != null) combatTick();
        else                 wanderTick();
    }

    @Override
    protected void onAnimationFinished(UnitStatus finishedStatus) {
        switch (finishedStatus) {
            case HIT -> setStatus(UnitStatus.IDLE); // combatTick()/wanderTick() take it from here
            case ATTACK -> {
                landHit();
                setStatus(UnitStatus.IDLE); // combatTick() re-triggers ATTACK once cooldown allows
            }
            case DYING -> { alive = false; setStatus(UnitStatus.LYING); }
            default -> {}
        }
    }

    // ── Wandering ─────────────────────────────────────────────────────────────

    private void wanderTick() {
        if (isMoving()) return;
        if (attackCooldownTicks > 0) attackCooldownTicks--;
        if (--wanderCooldownTicks > 0) return;

        rollNextWanderDelay();

        TileGameEngine engine = getEngine();
        if (engine == null) return;

        int dc = RNG.nextInt(WANDER_RADIUS * 2 + 1) - WANDER_RADIUS;
        int dr = RNG.nextInt(WANDER_RADIUS * 2 + 1) - WANDER_RADIUS;
        int destCol = clamp(getCol() + dc, 0, engine.options.mapWidthCells  - 1);
        int destRow = clamp(getRow() + dr, 0, engine.options.mapHeightCells - 1);

        if (destCol == getCol() && destRow == getRow()) return;
        moveTo(destCol, destRow, UnitStatus.WALK); // no-op (stays put) if unreachable
    }

    private void rollNextWanderDelay() {
        wanderCooldownTicks = WANDER_MIN_TICKS + RNG.nextInt(WANDER_MAX_TICKS - WANDER_MIN_TICKS + 1);
    }

    // ── Combat ────────────────────────────────────────────────────────────────

    private void combatTick() {
        if (!(target instanceof Damageable dmg) || !dmg.isAlive()) {
            target = null;
            return;
        }

        if (attackCooldownTicks > 0) attackCooldownTicks--;

        int dist = chebyshev(target);
        if (dist <= ATTACK_RANGE) {
            if (isMoving()) stopMoving();
            setDirectionFromVector(target.getCol() - getCol(), target.getRow() - getRow());
            if (attackCooldownTicks == 0) {
                setStatus(UnitStatus.ATTACK);
                attackCooldownTicks = ATTACK_COOLDOWN;
            }
        } else if (!isMoving()) {
            moveTo(target.getCol(), target.getRow(), UnitStatus.RUN);
        }
    }

    /** Called when an ATTACK swing completes; resolves hit/dodge/block and applies damage if it lands. */
    private void landHit() {
        if (!(target instanceof Damageable dmg) || !dmg.isAlive()) {
            target = null;
            return;
        }
        if (chebyshev(target) <= ATTACK_RANGE) {
            lastAttackResult = resolveAttack(this, target, ATTACK_DAMAGE);
            if (lastAttackResult.damage() > 0) dmg.takeDamage(lastAttackResult.damage(), this);
        }
    }

    private int chebyshev(Unit other) {
        return Math.max(Math.abs(other.getCol() - getCol()), Math.abs(other.getRow() - getRow()));
    }

    private static int clamp(int v, int lo, int hi) { return Math.max(lo, Math.min(hi, v)); }
}
