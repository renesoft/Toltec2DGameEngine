package org.toltec.engine;

import org.toltec.render.GraphicObject;
import org.toltec.unit.Unit;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * One tile on the game map.
 *
 * Contains an arbitrary number of {@link GraphicObject}s which are always
 * returned sorted by their {@code layer} field (ascending) for correct draw
 * order. Sorting is lazy — it only happens when the list is actually needed
 * after a change.
 *
 * Thread-safe: the game logic thread can add/remove objects (e.g. a
 * {@link Unit} walking between cells) at the same time the render thread is
 * reading {@link #getObjects()} for the very same cell. All access goes
 * through a single lock, and {@link #getObjects()} / {@link #tick()} both
 * work off a private snapshot rather than the live list, so neither thread
 * can ever see — or throw over — a list the other is modifying.
 */
public class MapCell {

    private final List<GraphicObject> objects = new ArrayList<>();
    private boolean dirty = false;
    private final Object lock = new Object();

    // =========================================================================
    // Object management
    // =========================================================================

    /** Add an object to this cell. */
    public void addObject(GraphicObject obj) {
        if (obj == null) throw new IllegalArgumentException("obj must not be null");
        synchronized (lock) {
            objects.add(obj);
            dirty = true;
        }
    }

    /** Remove an object from this cell. @return true if the object was present. */
    public boolean removeObject(GraphicObject obj) {
        synchronized (lock) {
            return objects.remove(obj);
        }
    }

    /** Remove all objects from this cell. */
    public void clearObjects() {
        synchronized (lock) {
            objects.clear();
            dirty = false;
        }
    }

    /**
     * Returns a snapshot of the objects in ascending {@code layer} order.
     * Safe to iterate freely, including while another thread concurrently
     * adds/removes objects in this same cell — you're holding a copy, not a
     * live view.
     */
    public List<GraphicObject> getObjects() {
        synchronized (lock) {
            if (dirty) {
                objects.sort(Comparator.comparingInt(o -> o.layer));
                dirty = false;
            }
            return new ArrayList<>(objects);
        }
    }

    /** @return true if any object in this cell has collision enabled. */
    public boolean hasCollision() {
        synchronized (lock) {
            for (GraphicObject o : objects)
                if (o.collision) return true;
            return false;
        }
    }

    /** @return number of objects in this cell. */
    public int size() { synchronized (lock) { return objects.size(); } }

    /**
     * @return the floor object in this cell ({@link GraphicObject#isFloor}
     *         {@code == true}), or {@code null} if this cell has none.
     *         Assumes at most one floor object per cell — see
     *         {@link Unit#tick()}, which reads {@link GraphicObject#speedMultiplier}
     *         / {@link GraphicObject#damagePerSecond} off of it.
     */
    public GraphicObject getFloorObject() {
        synchronized (lock) {
            for (GraphicObject o : objects) if (o.isFloor) return o;
            return null;
        }
    }

    // =========================================================================
    // Per-tick update
    // =========================================================================

    /**
     * Advance all objects in this cell by one logic tick. Called by the engine.
     * Ticks a snapshot, not the live list — a {@link Unit}'s tick() can move
     * it to a different cell mid-iteration (removing itself from whichever
     * cell it's currently in).
     */
    public void tick() {
        List<GraphicObject> snapshot;
        synchronized (lock) { snapshot = new ArrayList<>(objects); }
        for (GraphicObject o : snapshot) o.tick();
    }
}

