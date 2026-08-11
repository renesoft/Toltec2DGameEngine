package org.toltec.unit;

/**
 * One of 8 compass directions a {@link Unit} can face, spaced 45° apart.
 * 0° = facing "up" (north / away from camera), increasing clockwise —
 * matches how sprite sheets are usually authored (N, NE, E, SE, S, SW, W, NW).
 */
public enum Direction8 {
    N(0), NE(45), E(90), SE(135), S(180), SW(225), W(270), NW(315);

    private final int angle;

    Direction8(int angle) { this.angle = angle; }

    /** Angle in degrees, 0..315 step 45. */
    public int angle() { return angle; }

    /** Config-file key for this direction ("0".."315"). */
    public String key() { return Integer.toString(angle); }

    /** Nearest of the 8 directions to an arbitrary angle (degrees, any range/sign). */
    public static Direction8 fromAngle(double degrees) {
        double a = ((degrees % 360) + 360) % 360;
        int idx = (int) Math.round(a / 45.0) % 8;
        return values()[idx];
    }

    /**
     * Nearest direction a unit faces when moving by (dx, dy) in map/screen
     * space, where +x is right and +y is down (so (0,-1) faces N).
     */
    public static Direction8 fromVector(double dx, double dy) {
        if (dx == 0 && dy == 0) return S;
        double degrees = Math.toDegrees(Math.atan2(dx, -dy));
        return fromAngle(degrees);
    }
}
