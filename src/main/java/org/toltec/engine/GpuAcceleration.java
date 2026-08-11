package org.toltec.engine;

/**
 * Opts in to Java2D's hardware-accelerated rendering pipelines.
 * <p>
 * By default, Java2D falls back to a software (CPU) rasterizer for
 * {@link java.awt.Canvas}/{@code Graphics2D} drawing, which is fine for
 * simple UIs but leaves real performance on the table for a game that
 * repaints a full tile map every frame — every {@code drawImage} call,
 * the backbuffer blit, and the final scale-to-screen in
 * {@link TileGameEngine#draw} all end up walking pixels on the CPU
 * instead of the GPU.
 * <p>
 * {@link #enable()} turns on OpenGL-backed rendering on Linux/Windows and
 * Direct3D on Windows (whichever is available; the JVM silently falls back
 * to software if neither initializes, e.g. in a headless/VM environment
 * without a real GPU driver) and Quartz's accelerated path on macOS. It has
 * to run before <em>any</em> AWT/Swing class is touched — the pipeline is
 * selected once, the first time the toolkit initializes, and can't be
 * changed afterwards — so call this as the very first line of
 * {@code main()}, before creating any {@link javax.swing.JFrame},
 * {@link GameCanvas}, or even a {@link java.awt.Color}.
 */
public final class GpuAcceleration {
    private GpuAcceleration() {}

    public static void enable() {
        // OpenGL pipeline — used on Linux, and on Windows as an alternative
        // to Direct3D. Also respected (as the "sun.java2d.metal"-adjacent
        // Quartz path) on macOS via -Dapple.awt.graphics.UseQuartz below.
        setIfAbsent("sun.java2d.opengl", "true");
        // Direct3D pipeline on Windows; ignored elsewhere.
        setIfAbsent("sun.java2d.d3d", "true");
        // Accelerated Quartz rendering path on macOS; ignored elsewhere.
        setIfAbsent("apple.awt.graphics.UseQuartz", "true");
    }

    private static void setIfAbsent(String key, String value) {
        // Respect anything the user already passed on the command line
        // (e.g. -Dsun.java2d.opengl=false to debug a driver issue) instead
        // of clobbering it.
        if (System.getProperty(key) == null) {
            System.setProperty(key, value);
        }
    }
}
