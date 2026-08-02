package org.toltec;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.Collections;
import java.util.List;

/**
 * One tile on the game map.
 *
 * Contains an arbitrary number of {@link GraphicObject}s which are always returned
 * sorted by their {@code layer} field (ascending) for correct draw order.
 * Sorting is lazy — it only happens when the list is actually needed after a change.
 */
public class MapCell {

    private final List<GraphicObject> objects = new ArrayList<>();
    private boolean dirty = false;

    // =========================================================================
    // Object management
    // =========================================================================

    /** Add an object to this cell. */
    public void addObject(GraphicObject obj) {
        if (obj == null) throw new IllegalArgumentException("obj must not be null");
        objects.add(obj);
        dirty = true;
    }

    /** Remove an object from this cell. @return true if the object was present. */
    public boolean removeObject(GraphicObject obj) {
        return objects.remove(obj);
    }

    /** Remove all objects from this cell. */
    public void clearObjects() {
        objects.clear();
        dirty = false;
    }

    /**
     * Returns the objects in ascending {@code layer} order.
     * The returned list is live — do not modify it directly.
     */
    public List<GraphicObject> getObjects() {
        if (dirty) {
            objects.sort(Comparator.comparingInt(o -> o.layer));
            dirty = false;
        }
        return Collections.unmodifiableList(objects);
    }

    /** @return true if any object in this cell has collision enabled. */
    public boolean hasCollision() {
        for (GraphicObject o : objects)
            if (o.collision) return true;
        return false;
    }

    /** @return number of objects in this cell. */
    public int size() { return objects.size(); }

    // =========================================================================
    // Per-tick update
    // =========================================================================

    /** Advance all animated objects by one logic tick. Called by the engine. */
    public void tick() {
        for (GraphicObject o : objects) o.tick();
    }
}
