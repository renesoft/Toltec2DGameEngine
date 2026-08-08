package org.toltec;

import java.util.List;

/**
 * A concrete, resolved sequence of frames for one
 * (gender, weapon, status, direction) combination. Frame strings are asset
 * keys already loaded into {@link AssetStorage} — see {@link UnitAnimationConfig}.
 */
public class AnimationClip {

    private final List<String> frames;
    private final boolean      loop;
    private final int          ticksPerFrame;
    private final int          durationMs;
    private final Double       scale;
    private final Double       hitChance;
    private final Double       dodgeChance;
    private final Double       blockChance;
    private final Double       attackInterruptChance;

    /** Legacy constructor: frame timing is driven by logic ticks only. */
    public AnimationClip(List<String> frames, boolean loop, int ticksPerFrame) {
        this(frames, loop, ticksPerFrame, 0, null, null, null, null, null);
    }

    /** @deprecated use the full constructor; kept for any external callers built against it. */
    @Deprecated
    public AnimationClip(List<String> frames, boolean loop, int ticksPerFrame, int durationMs) {
        this(frames, loop, ticksPerFrame, durationMs, null, null, null, null, null);
    }

    /** @deprecated use the full constructor; kept for any external callers built against it. */
    @Deprecated
    public AnimationClip(List<String> frames, boolean loop, int ticksPerFrame, int durationMs, double scale) {
        this(frames, loop, ticksPerFrame, durationMs, scale, null, null, null, null);
    }

    /**
     * @param ticksPerFrame fallback tick-based timing, used only when {@code durationMs <= 0}
     * @param durationMs    if {@code > 0}, the total wall-clock time (milliseconds) it takes to
     *                      play this clip once — one loop cycle for a looping clip (e.g. one walk
     *                      cycle), or the whole sequence for a one-shot clip (attack, dying, ...).
     *                      Frames are spread evenly across that time, independent of
     *                      {@code tickIntervalMs}. For movement statuses (walk/run) this is also
     *                      how long the unit takes to cross one map cell — see {@link Unit} —
     *                      so the sprite and the movement stay in sync. If both durationMs and
     *                      ticksPerFrame are given, durationMs wins and ticksPerFrame is ignored.
     * @param scale         config-specified size multiplier for this clip (see
     *                      {@code scale[gender][weapon][status][angle]} in {@link UnitAnimationConfig}),
     *                      or {@code null} if not configured. {@link Unit} applies this to
     *                      {@link GraphicObject#fitScale} whenever this clip becomes current, so
     *                      {@code 1.6} draws the sprite 1.6× as big while keeping its feet anchored
     *                      to the same point on the tile. {@code null} leaves whatever {@code fitScale}
     *                      the game already set alone, instead of resetting it to 1.0.
     * @param hitChance             config-specified {@link Unit#hitChance}, or {@code null} if not configured.
     * @param dodgeChance           config-specified {@link Unit#dodgeChance}, or {@code null} if not configured.
     * @param blockChance           config-specified {@link Unit#blockChance}, or {@code null} if not configured.
     * @param attackInterruptChance config-specified {@link Unit#attackInterruptChance}, or {@code null} if not configured.
     *                      Like {@code scale}, {@link Unit} only overwrites its own field when the
     *                      resolved clip actually carries a non-null value here — a {@code null}
     *                      leaves whatever the game already set in Java (e.g. in your onStart())
     *                      alone, rather than silently resetting it back to the hard-coded default
     *                      the next time the unit's clip refreshes.
     */
    public AnimationClip(List<String> frames, boolean loop, int ticksPerFrame, int durationMs,
                          Double scale, Double hitChance, Double dodgeChance,
                          Double blockChance, Double attackInterruptChance) {
        if (frames == null || frames.isEmpty())
            throw new IllegalArgumentException("frames must not be empty");
        this.frames        = frames;
        this.loop          = loop;
        this.ticksPerFrame = Math.max(1, ticksPerFrame);
        this.durationMs    = Math.max(0, durationMs);
        this.scale                 = (scale != null && scale > 0) ? scale : null;
        this.hitChance              = hitChance;
        this.dodgeChance            = dodgeChance;
        this.blockChance            = blockChance;
        this.attackInterruptChance  = attackInterruptChance;
    }

    public int     frameCount()        { return frames.size(); }
    public String  frame(int index)    { return frames.get(index); }
    public boolean isLoop()            { return loop; }
    public int     ticksPerFrame()     { return ticksPerFrame; }
    /**
     * Total milliseconds to play this clip once (one loop cycle if it loops); 0 means
     * "no wall-clock timing configured, use ticksPerFrame instead". See {@link Unit} for how
     * this also drives cell-crossing speed for movement statuses.
     */
    public int     durationMs()        { return durationMs; }
    /** Milliseconds each frame is held before advancing; 0 means "use ticksPerFrame instead". */
    public int     msPerFrame()        { return durationMs > 0 ? Math.max(1, durationMs / frames.size()) : 0; }
    public boolean isStatic()          { return frames.size() == 1; }
    /** Size multiplier to apply to the sprite while this clip is playing, or {@code null} if not configured — see the constructor doc. */
    public Double  scale()                  { return scale; }
    /** Config-specified {@link Unit#hitChance}, or {@code null} if not configured — see the constructor doc. */
    public Double  hitChance()              { return hitChance; }
    /** Config-specified {@link Unit#dodgeChance}, or {@code null} if not configured — see the constructor doc. */
    public Double  dodgeChance()            { return dodgeChance; }
    /** Config-specified {@link Unit#blockChance}, or {@code null} if not configured — see the constructor doc. */
    public Double  blockChance()            { return blockChance; }
    /** Config-specified {@link Unit#attackInterruptChance}, or {@code null} if not configured — see the constructor doc. */
    public Double  attackInterruptChance()  { return attackInterruptChance; }
}
