package org.toltec.editor.model;

import org.toltec.unit.Direction8;

import java.io.File;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * The 8 directional drop-cells' contents for one clip (one unit state/weapon/gender
 * combo, or one object state), plus its shared duration/loop/scale/etc.
 * <p>
 * When the owning object is "omnidirectional" (objects only — no facing),
 * only the {@link org.toltec.unit.Direction8#S} slot is used, by convention, and
 * is written to the ini with the angle bracket wildcarded instead of "180".
 */
public class ClipGroup {
    private final Map<Direction8, List<File>> frames = new EnumMap<>(Direction8.class);
    public final ClipParams params = new ClipParams();

    public List<File> frames(Direction8 dir) {
        return frames.computeIfAbsent(dir, d -> new ArrayList<>());
    }

    /** Read-only lookup that never mutates the map (safe to call while iterating/writing). */
    public List<File> peek(Direction8 dir) {
        return frames.getOrDefault(dir, List.of());
    }

    public void setFrames(Direction8 dir, List<File> files) {
        frames.put(dir, new ArrayList<>(files));
    }

    /** Rewrites every stored file reference in place — used after a folder rename/move. */
    public void mapFiles(java.util.function.UnaryOperator<File> mapper) {
        for (Map.Entry<Direction8, List<File>> e : frames.entrySet()) {
            List<File> mapped = new ArrayList<>(e.getValue().size());
            for (File f : e.getValue()) mapped.add(mapper.apply(f));
            e.setValue(mapped);
        }
    }

    public boolean isEmpty() {
        return frames.values().stream().allMatch(List::isEmpty);
    }

    public boolean has(Direction8 dir) {
        List<File> f = frames.get(dir);
        return f != null && !f.isEmpty();
    }

    public Map<Direction8, List<File>> allFrames() { return frames; }

    public ClipGroup copy() {
        ClipGroup c = new ClipGroup();
        for (Map.Entry<Direction8, List<File>> e : frames.entrySet()) {
            c.frames.put(e.getKey(), new ArrayList<>(e.getValue()));
        }
        c.params.durationMs = params.durationMs;
        c.params.ticksPerFrame = params.ticksPerFrame;
        c.params.loop = params.loop;
        c.params.reverse = params.reverse;
        c.params.scale = params.scale;
        return c;
    }
}
