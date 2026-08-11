package org.toltec.unit;

import org.toltec.engine.TileGameEngine;

/**
 * Implemented by {@link Unit}s that have health and can be hurt/killed —
 * currently {@link Player} and {@link Goblin}. Lets generic code (health-bar
 * rendering in {@link TileGameEngine}, combat AI in {@link Goblin}) work with
 * any damageable unit without knowing its concrete type.
 */
public interface Damageable {

    int getHealth();

    int getMaxHealth();

    boolean isAlive();

    /**
     * Apply damage from {@code attacker} (may be {@code null} for
     * environmental/scripted damage). Implementations decide what happens
     * next (play a HIT/DYING animation, remember the attacker to fight back, …).
     */
    void takeDamage(int amount, Unit attacker);

    /**
     * Called the instant {@code attacker} commits to an attack against this
     * unit — when the attack swing/animation starts, not when it lands (that's
     * still {@link #takeDamage}, fired once the swing actually connects).
     * Lets AI (e.g. {@link Goblin}) aggro and start fighting back as soon as
     * it's being attacked, rather than only after eating the first hit — which
     * matters because the swing can take a while to land, or can even miss
     * (target moves out of range before it connects). No-op by default.
     */
    default void notifyAttacked(Unit attacker) {}
}
