package org.toltec;

/**
 * Behavioural state of a {@link Unit}. Drives which animation clip is shown
 * (together with gender, weapon and facing direction).
 */
public enum UnitStatus {
    /** Standing still. */
    IDLE(true),
    /** Walking. */
    WALK(true),
    /** Running. */
    RUN(true),
    /** Attacking (melee swing, bow shot, etc). Plays once. */
    ATTACK(false),
    /** Reacting to incoming damage ("flinch"). Plays once. */
    HIT(false),
    /** Being knocked down / falling over. Plays once. */
    FALLING(false),
    /** Death animation. Plays once. */
    DYING(false),
    /** Lying dead/unconscious on the ground. Usually a static pose. */
    LYING(false),
    /** Berserk / enraged movement or stance. */
    BERSERK(true);

    private final boolean defaultLoop;

    UnitStatus(boolean defaultLoop) { this.defaultLoop = defaultLoop; }

    /** Config-file key for this status (lower-case name), e.g. "walk". */
    public String key() { return name().toLowerCase(); }

    /**
     * Whether a clip for this status loops by default when the config file
     * doesn't specify {@code loop[...]=} explicitly.
     */
    public boolean defaultLoop() { return defaultLoop; }
}
