package org.toltec.unit;

/**
 * Player-controlled {@link Unit}. Adds health and wires the obvious status
 * transitions (hit → idle, attack → idle, dying → lying) on top of the base
 * animation handling — a starting point, adjust to taste.
 */
public class Player extends Unit implements Damageable {

    private final int maxHealth;
    private int        health;
    private boolean    alive = true;

    // ── Pending attack: set by attack(target, damage), resolved once the ATTACK clip
    //    finishes playing (see onAnimationFinished) so damage lands when the swing
    //    actually connects on screen, not the instant the player clicked. ──────────
    private Unit    attackTargetUnit;
    private Damageable attackTarget;
    private int     attackDamage;
    private AttackResult lastAttackResult; // last resolveAttack() outcome, for game code / HUD feedback

    // ── Walk/run toggle (e.g. bound to the R key) — see toggleRunning() ────
    private boolean running = false;

    public Player(UnitAnimationConfig animConfig, Gender gender, String weapon, int maxHealth) {
        super(animConfig, gender, weapon);
        this.maxHealth = maxHealth;
        this.health    = maxHealth;
    }

    @Override public int     getHealth()    { return health; }
    @Override public int     getMaxHealth() { return maxHealth; }
    @Override public boolean isAlive()      { return alive; }

    /** Face and walk towards (dx, dy). */
    public void walk(double dx, double dy) {
        if (!alive) return;
        setDirectionFromVector(dx, dy);
        setStatus(UnitStatus.WALK);
    }

    /** Face and run towards (dx, dy). */
    public void run(double dx, double dy) {
        if (!alive) return;
        setDirectionFromVector(dx, dy);
        setStatus(UnitStatus.RUN);
    }

    public void stand() {
        if (!alive) return;
        setStatus(UnitStatus.IDLE);
    }

    /** Whether the player is currently in "run" mode — see {@link #toggleRunning()}. */
    public boolean isRunning() { return running; }

    /**
     * Flips between walking and running (e.g. bind this to the R key).
     * If the player is mid-move, switches its status immediately — thanks
     * to {@link Unit#setStatus}'s WALK/RUN handling this keeps the current
     * animation frame, path and cell progress untouched, so the stride
     * doesn't stutter. If idle, this just changes which status the next
     * {@link #moveTo} call (see the override below) will use.
     */
    public void toggleRunning() {
        if (!alive) return;
        running = !running;
        if (isMoving()) setStatus(running ? UnitStatus.RUN : UnitStatus.WALK);
    }

    /** Like {@link Unit#moveTo(int, int)}, but uses RUN instead of WALK while {@link #isRunning()}. */
    @Override
    public boolean moveTo(int targetCol, int targetRow) {
        return moveTo(targetCol, targetRow, running ? UnitStatus.RUN : UnitStatus.WALK);
    }

    /** Plays the attack animation without landing any damage (e.g. attacking into empty air). */
    public void attack() {
        if (!alive) return;
        attackTargetUnit = null;
        attackTarget     = null;
        setStatus(UnitStatus.ATTACK);
    }

    /**
     * Faces {@code target} and plays the attack animation; {@code amount} damage is applied
     * to it only once the animation finishes (see {@link #onAnimationFinished}), and only if
     * the target is still alive and still in range at that point — not the instant this is
     * called. Mirrors {@link Goblin#landHit()}.
     */
    public void attack(Unit target, int amount) {
        if (!alive || target == null) return;
        setDirectionFromVector(target.getCol() - getCol(), target.getRow() - getRow());
        attackTargetUnit = target;
        attackTarget      = (target instanceof Damageable d) ? d : null;
        attackDamage      = amount;
        setStatus(UnitStatus.ATTACK);
        // Aggro the target the moment the swing starts, not once it lands —
        // see Damageable#notifyAttacked.
        if (attackTarget != null) attackTarget.notifyAttacked(this);
    }

    /** Apply damage: plays HIT, or DYING (then LYING) once health hits 0. */
    public void takeDamage(int amount) {
        takeDamage(amount, null);
    }

    /** Same as {@link #takeDamage(int)}; the attacker is accepted for {@link Damageable} but not tracked. */
    @Override
    public void takeDamage(int amount, Unit attacker) {
        if (!alive) return;
        health = Math.max(0, health - amount);

        if (health == 0) {
            setStatus(UnitStatus.DYING);
            return;
        }

        // Only actually interrupts an in-progress ATTACK swing, and even then only
        // per attackInterruptChance — see Unit#rollAttackInterrupted(). Being hit
        // while not mid-swing always plays the HIT reaction as before.
        if (rollAttackInterrupted()) {
            setStatus(UnitStatus.HIT);
        }
        // else: still mid-swing, kept the animation — landHit() fires normally
        // once it finishes, same as if this hit had never landed.
    }

    /** The outcome (hit/miss/dodged/blocked) of the last swing this player finished, or {@code null} before the first one. */
    public AttackResult getLastAttackResult() { return lastAttackResult; }

    @Override
    protected void onAnimationFinished(UnitStatus finishedStatus) {
        switch (finishedStatus) {
            case HIT -> setStatus(UnitStatus.IDLE);
            case ATTACK -> {
                landHit();
                setStatus(UnitStatus.IDLE);
            }
            case DYING -> { alive = false; setStatus(UnitStatus.LYING); }
            default -> {}
        }
    }

    /** Called when the ATTACK swing completes; resolves hit/dodge/block and applies damage if it lands. */
    private void landHit() {
        if (attackTarget != null && attackTarget.isAlive() && chebyshev(attackTargetUnit) <= 1) {
            lastAttackResult = resolveAttack(this, attackTargetUnit, attackDamage);
            if (lastAttackResult.damage() > 0) attackTarget.takeDamage(lastAttackResult.damage(), this);
        }
        attackTargetUnit = null;
        attackTarget     = null;
    }

    private int chebyshev(Unit other) {
        return Math.max(Math.abs(other.getCol() - getCol()), Math.abs(other.getRow() - getRow()));
    }
}
