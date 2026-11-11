package com.broadside.speedway;

/**
 * Oval speedway track geometry (pure math).
 *
 * World: y up, track in the x/z plane, racing anti-clockwise seen from above (left turns).
 * Home straight at z = +r travelling +x; right bend centre (+S/2, 0); back straight z = -r
 * travelling -x; left bend centre (-S/2, 0). Heading θ: forward = (sin θ, 0, cos θ);
 * increasing θ = turning left. Lateral offset d is measured outward from the white line.
 */
public final class Track {
    public static final float STRAIGHT = 84f;
    public static final float INNER_RADIUS = 27f;
    public static final float WIDTH = 13f;
    public static final int LAPS = 4;
    public static final float REF_RADIUS = INNER_RADIUS + WIDTH * 0.5f;
    public static final float BEND_LENGTH = (float) Math.PI * REF_RADIUS;
    public static final float LENGTH = 2 * STRAIGHT + 2 * BEND_LENGTH;
    /** Start/finish line: middle of the home straight (x = 0). */
    public static final float START_S = STRAIGHT * 0.5f;

    private static final float HS = STRAIGHT * 0.5f;

    private Track() {}

    /** Result holder to avoid allocations. */
    public static final class Point {
        public float x, z, heading, curvature;
    }

    public static final class Proj {
        public float s, d;
        public boolean inBend;
    }

    public static float wrapS(float s) {
        float L = LENGTH;
        return ((s % L) + L) % L;
    }

    public static Point point(float s, float d, Point out) {
        s = wrapS(s);
        float r = INNER_RADIUS + d;
        float B = BEND_LENGTH;
        float S = STRAIGHT;
        if (s < S) {
            out.x = -HS + s; out.z = r; out.heading = (float) (Math.PI / 2); out.curvature = 0;
        } else if (s < S + B) {
            float phi = (s - S) / REF_RADIUS;
            out.x = HS + r * (float) Math.sin(phi);
            out.z = r * (float) Math.cos(phi);
            out.heading = (float) (Math.PI / 2 + phi);
            out.curvature = 1f / r;
        } else if (s < 2 * S + B) {
            float t = s - S - B;
            out.x = HS - t; out.z = -r; out.heading = (float) (-Math.PI / 2); out.curvature = 0;
        } else {
            float phi = (s - 2 * S - B) / REF_RADIUS;
            out.x = -HS - r * (float) Math.sin(phi);
            out.z = -r * (float) Math.cos(phi);
            out.heading = (float) (-Math.PI / 2 + phi);
            out.curvature = 1f / r;
        }
        return out;
    }

    public static Point point(float s, float d) { return point(s, d, new Point()); }

    public static Proj project(float x, float z, Proj out) {
        float B = BEND_LENGTH, S = STRAIGHT;
        if (x >= -HS && x <= HS) {
            out.inBend = false;
            if (z >= 0) { out.s = x + HS; out.d = z - INNER_RADIUS; }
            else { out.s = S + B + (HS - x); out.d = -z - INNER_RADIUS; }
            return out;
        }
        out.inBend = true;
        if (x > HS) {
            float dx = x - HS;
            float phi = (float) Math.atan2(dx, z);
            out.s = S + phi * REF_RADIUS;
            out.d = (float) Math.hypot(dx, z) - INNER_RADIUS;
        } else {
            float dx = x + HS;
            float phi = (float) Math.atan2(-dx, -z);
            out.s = 2 * S + B + phi * REF_RADIUS;
            out.d = (float) Math.hypot(dx, z) - INNER_RADIUS;
        }
        return out;
    }

    /** Signed shortest difference a-b around the lap. */
    public static float lapDelta(float a, float b) {
        float L = LENGTH;
        float d = (a - b) % L;
        if (d > L / 2) d -= L;
        if (d < -L / 2) d += L;
        return d;
    }

    public static float wrapAngle(float a) {
        while (a > Math.PI) a -= 2 * Math.PI;
        while (a < -Math.PI) a += 2 * Math.PI;
        return a;
    }

    /** 0 on straights, 1 deep in the bends, smooth ramps either side. */
    public static float bendPhase(float s) {
        s = wrapS(s);
        float S = STRAIGHT, B = BEND_LENGTH, ramp = 14f;
        float best = 0;
        float[][] bends = {{S, S + B}, {2 * S + B, 2 * S + 2 * B}};
        for (float[] bd : bends) {
            float a = bd[0], b = bd[1], v = 0;
            if (s >= a && s <= b) v = 1;
            else if (s < a && a - s < ramp) v = 1 - (a - s) / ramp;
            else if (s > b && s - b < ramp) v = 1 - (s - b) / ramp;
            best = Math.max(best, v);
        }
        return best;
    }
}
