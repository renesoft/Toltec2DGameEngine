package org.toltec.editor.preview;

import org.toltec.render.AnimationClip;
import org.toltec.render.GraphicObject;

/**
 * Plays back an {@link AnimationClip} resolved from {@code ObjectTextureConfig}
 * for the live preview. {@link GraphicObject}'s own built-in animation helper
 * ({@code setupAnimation}) only handles a contiguous {@code baseName[i]} key
 * sequence (an auto-sliced sheet); a clip made of several already-cut frame
 * files doesn't fit that shape, so this steps frames manually instead —
 * mirroring the same duration/ticks/loop logic {@code Unit} uses internally.
 */
public class AnimatedPreviewObject extends GraphicObject {

    private final AnimationClip clip;
    private int frameIndex;
    private int ticksSinceFrame;
    private long lastFrameTimeNs = -1;

    public AnimatedPreviewObject(AnimationClip clip) {
        super(clip.frame(0));
        this.clip = clip;
    }

    @Override
    public void tick() {
        if (clip.frameCount() <= 1) return;

        if (clip.msPerFrame() > 0) {
            long now = System.nanoTime();
            if (lastFrameTimeNs < 0) lastFrameTimeNs = now;
            long elapsedMs = (now - lastFrameTimeNs) / 1_000_000L;
            if (elapsedMs < clip.msPerFrame()) return;
            lastFrameTimeNs = now;
        } else {
            if (++ticksSinceFrame < clip.ticksPerFrame()) return;
            ticksSinceFrame = 0;
        }

        int next = frameIndex + 1;
        frameIndex = next >= clip.frameCount() ? (clip.isLoop() ? 0 : clip.frameCount() - 1) : next;
        imageName = clip.frame(frameIndex);
    }
}
