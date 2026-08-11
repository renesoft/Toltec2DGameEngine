package org.toltec.editor.model;

/**
 * The handful of playback parameters a clip group (one state/weapon/gender
 * combo for a unit, or one state for an object) carries, written into the
 * ini with the angle bracket wildcarded — one line covers all 8 directions.
 * {@code null} means "not set, use the engine's own default".
 */
public class ClipParams {
    public Integer durationMs;
    public Integer ticksPerFrame;
    public Boolean loop;
    public boolean reverse;
    public Double scale;

    public ClipParams copy() {
        ClipParams c = new ClipParams();
        c.durationMs = durationMs;
        c.ticksPerFrame = ticksPerFrame;
        c.loop = loop;
        c.reverse = reverse;
        c.scale = scale;
        return c;
    }
}
