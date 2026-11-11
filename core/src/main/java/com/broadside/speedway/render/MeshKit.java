package com.broadside.speedway.render;

import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.VertexAttributes.Usage;
import com.badlogic.gdx.graphics.g3d.Material;
import com.badlogic.gdx.graphics.g3d.attributes.BlendingAttribute;
import com.badlogic.gdx.graphics.g3d.attributes.ColorAttribute;
import com.badlogic.gdx.graphics.g3d.attributes.IntAttribute;
import com.badlogic.gdx.graphics.g3d.attributes.TextureAttribute;
import com.badlogic.gdx.graphics.g3d.utils.MeshPartBuilder;
import com.badlogic.gdx.graphics.g3d.utils.ModelBuilder;
import com.badlogic.gdx.math.CatmullRomSpline;
import com.badlogic.gdx.math.Matrix4;
import com.badlogic.gdx.math.Vector3;
import com.broadside.speedway.Track;

/** Geometry helpers on top of libGDX MeshPartBuilder, plus material shortcuts. */
public final class MeshKit {
    private MeshKit() {}

    public static final long ATTR = Usage.Position | Usage.Normal | Usage.TextureCoordinates;
    public static final long ATTR_COLOR = ATTR | Usage.ColorPacked;

    // ------------------------------------------------------------------ materials
    public static Material mat(int rgb, float metal, float rough, float clearcoat) {
        return new Material(ColorAttribute.createDiffuse(rgbColor(rgb)), new Lighting.Pbr(metal, rough, clearcoat));
    }

    public static Material mat(int rgb) { return mat(rgb, 0, 0.8f, 0); }

    public static Material texMat(Texture t, float rough) {
        return new Material(TextureAttribute.createDiffuse(t), new Lighting.Pbr(0, rough, 0));
    }

    public static Material emissive(int rgb, float intensity) {
        Color c = rgbColor(rgb);
        return new Material(ColorAttribute.createDiffuse(c), ColorAttribute.createEmissive(c.r, c.g, c.b, intensity / 4f),
            new Lighting.Pbr(0, 0.5f, 0));
    }

    public static Material twoSided(Material m) {
        m.set(IntAttribute.createCullFace(0));
        return m;
    }

    public static Material transparent(Material m, float opacity) {
        m.set(new BlendingAttribute(GL20.GL_SRC_ALPHA, GL20.GL_ONE_MINUS_SRC_ALPHA, opacity));
        return m;
    }

    public static Color rgbColor(int rgb) {
        return new Color(((rgb >> 16) & 255) / 255f, ((rgb >> 8) & 255) / 255f, (rgb & 255) / 255f, 1f);
    }

    // ------------------------------------------------------------------ primitives
    private static final Vector3 P = new Vector3(), N = new Vector3();
    private static final MeshPartBuilder.VertexInfo VI = new MeshPartBuilder.VertexInfo();

    private static short vert(MeshPartBuilder b, float px, float py, float pz, float nx, float ny, float nz, float u, float v) {
        VI.reset();
        VI.setPos(px, py, pz).setNor(nx, ny, nz).setUV(u, v);
        return b.vertex(VI);
    }

    /**
     * Torus with the ring in the plane given: axis 'x' means the ring lies in the YZ plane
     * (a wheel rolling along z). arc in radians (2π = full).
     */
    public static void torusX(MeshPartBuilder b, float R, float r, int segR, int segr, float arc, float arcStart) {
        short base = -1;
        for (int i = 0; i <= segR; i++) {
            float a = arcStart + arc * i / segR;
            float ca = (float) Math.cos(a), sa = (float) Math.sin(a);
            for (int j = 0; j <= segr; j++) {
                float t = (float) (2 * Math.PI * j / segr);
                float ct = (float) Math.cos(t), st = (float) Math.sin(t);
                // ring centre at (0, R*sa, R*ca); tube offset along radial and x
                float ny = ca * 0 + sa * ct, nz = ca * ct, nx = st;
                float py = (R + r * ct) * sa, pz = (R + r * ct) * ca, px = r * st;
                short idx = vert(b, px, py, pz, nx, ny, nz, i / (float) segR, j / (float) segr);
                if (base < 0) base = idx;
            }
        }
        int row = segr + 1;
        for (int i = 0; i < segR; i++) for (int j = 0; j < segr; j++) {
            short a = (short) (base + i * row + j), c = (short) (a + row);
            b.triangle(a, (short) (a + 1), c);
            b.triangle((short) (a + 1), (short) (c + 1), c);
        }
    }

    /** Tube following a Catmull-Rom curve through the given points. */
    public static void tube(MeshPartBuilder b, Vector3[] pts, float r, int seg, int radial, boolean closed) {
        CatmullRomSpline<Vector3> spline = new CatmullRomSpline<>(pts, closed);
        Vector3[] c = new Vector3[seg + 1];
        for (int i = 0; i <= seg; i++) {
            c[i] = new Vector3();
            float t = closed ? i / (float) seg : i / (float) seg;
            spline.valueAt(c[i], Math.min(t, closed ? 0.99999f : 1f));
        }
        Vector3 tan = new Vector3(), n1 = new Vector3(), n2 = new Vector3(), ref = new Vector3(0, 1, 0);
        short base = -1;
        for (int i = 0; i <= seg; i++) {
            Vector3 a = c[Math.max(0, i - 1)], d = c[Math.min(seg, i + 1)];
            tan.set(d).sub(a).nor();
            if (Math.abs(tan.dot(ref)) > 0.95f) ref.set(1, 0, 0);
            n1.set(tan).crs(ref).nor();
            n2.set(tan).crs(n1).nor();
            for (int j = 0; j <= radial; j++) {
                float th = (float) (2 * Math.PI * j / radial);
                float cx = (float) Math.cos(th), sx = (float) Math.sin(th);
                N.set(n1).scl(cx).mulAdd(n2, sx);
                P.set(c[i]).mulAdd(N, r);
                short idx = vert(b, P.x, P.y, P.z, N.x, N.y, N.z, i / (float) seg, j / (float) radial);
                if (base < 0) base = idx;
            }
        }
        int row = radial + 1;
        for (int i = 0; i < seg; i++) for (int j = 0; j < radial; j++) {
            short a = (short) (base + i * row + j), d = (short) (a + row);
            b.triangle(a, (short) (a + 1), d);
            b.triangle((short) (a + 1), (short) (d + 1), d);
        }
    }

    /** Flat ribbon following the oval between offsets d0..d1 at height y. u = s/uScale, v = 0..1 across. */
    public static void ribbon(MeshPartBuilder b, float d0, float d1, float y, float uScale, int cols, float step) {
        int n = (int) Math.ceil(Track.LENGTH / step);
        Track.Point p = new Track.Point();
        short base = -1;
        for (int i = 0; i <= n; i++) {
            float s = i / (float) n * Track.LENGTH;
            for (int j = 0; j <= cols; j++) {
                float d = d0 + (d1 - d0) * j / cols;
                Track.point(s, d, p);
                short idx = vert(b, p.x, y, p.z, 0, 1, 0, s / uScale, j / (float) cols);
                if (base < 0) base = idx;
            }
        }
        int row = cols + 1;
        for (int i = 0; i < n; i++) for (int j = 0; j < cols; j++) {
            short a = (short) (base + i * row + j), c = (short) (a + row);
            b.triangle(a, (short) (a + 1), c);
            b.triangle(c, (short) (a + 1), (short) (c + 1));
        }
    }

    /** Vertical wall following the oval at offset d from y0 to y1. Normal faces the track centre-line side. */
    public static void wall(MeshPartBuilder b, float d, float y0, float y1, float uScale, boolean flipU, float step, boolean faceInward) {
        int n = (int) Math.ceil(Track.LENGTH / step);
        Track.Point p = new Track.Point();
        short base = -1;
        for (int i = 0; i <= n; i++) {
            float s = i / (float) n * Track.LENGTH;
            Track.point(s, d, p);
            // left of travel = inward; left vector = (cos h, -sin h)
            float lx = (float) Math.cos(p.heading), lz = (float) -Math.sin(p.heading);
            if (!faceInward) { lx = -lx; lz = -lz; }
            float u = (flipU ? -s : s) / uScale;
            short i0 = vert(b, p.x, y0, p.z, lx, 0, lz, u, 1);
            vert(b, p.x, y1, p.z, lx, 0, lz, u, 0);
            if (base < 0) base = i0;
        }
        for (int i = 0; i < n; i++) {
            short a = (short) (base + i * 2);
            if (faceInward) {
                b.triangle(a, (short) (a + 1), (short) (a + 2));
                b.triangle((short) (a + 1), (short) (a + 3), (short) (a + 2));
            } else {
                b.triangle(a, (short) (a + 2), (short) (a + 1));
                b.triangle((short) (a + 1), (short) (a + 2), (short) (a + 3));
            }
        }
    }

    /**
     * Band of a cylinder whose axis is X: points (x, R·sin a, R·cos a), a in [a0, a1] (radians).
     * a = π/2 is straight up, a = π is behind (−z). Used for mudguards.
     */
    public static void arcBandX(MeshPartBuilder b, float R, float halfWidth, float a0, float a1, int seg) {
        short base = -1;
        for (int i = 0; i <= seg; i++) {
            float a = a0 + (a1 - a0) * i / seg;
            float sa = (float) Math.sin(a), ca = (float) Math.cos(a);
            short i0 = vert(b, -halfWidth, R * sa, R * ca, 0, sa, ca, i / (float) seg, 0);
            vert(b, halfWidth, R * sa, R * ca, 0, sa, ca, i / (float) seg, 1);
            if (base < 0) base = i0;
        }
        for (int i = 0; i < seg; i++) {
            short a = (short) (base + i * 2);
            b.triangle(a, (short) (a + 2), (short) (a + 1));
            b.triangle((short) (a + 1), (short) (a + 2), (short) (a + 3));
        }
    }

    /** Conical band around the Y axis from radius r0 (bottom, y=0) to r1 (top, y=h), angles a0..a1 (x = r·sin a, z = r·cos a). */
    public static void coneBandY(MeshPartBuilder b, float r0, float r1, float h, float a0, float a1, int seg) {
        short base = -1;
        float slope = (r0 - r1) / h;
        for (int i = 0; i <= seg; i++) {
            float a = a0 + (a1 - a0) * i / seg;
            float sa = (float) Math.sin(a), ca = (float) Math.cos(a);
            N.set(sa, slope, ca).nor();
            short i0 = vert(b, r0 * sa, 0, r0 * ca, N.x, N.y, N.z, i / (float) seg * 8, 0);
            vert(b, r1 * sa, h, r1 * ca, N.x, N.y, N.z, i / (float) seg * 8, 1);
            if (base < 0) base = i0;
        }
        for (int i = 0; i < seg; i++) {
            short a = (short) (base + i * 2);
            b.triangle(a, (short) (a + 2), (short) (a + 1));
            b.triangle((short) (a + 1), (short) (a + 2), (short) (a + 3));
        }
    }

    /** Start a part with a vertex transform applied. */
    public static MeshPartBuilder part(ModelBuilder mb, String id, Material m) {
        return mb.part(id, GL20.GL_TRIANGLES, ATTR, m);
    }

    public static void at(MeshPartBuilder b, Matrix4 m) { b.setVertexTransform(m); }

    public static Matrix4 tr(float x, float y, float z) { return new Matrix4().setToTranslation(x, y, z); }

    public static Matrix4 tr(float x, float y, float z, float rxDeg, float ryDeg, float rzDeg) {
        return new Matrix4().setToTranslation(x, y, z).rotate(Vector3.Y, ryDeg).rotate(Vector3.X, rxDeg).rotate(Vector3.Z, rzDeg);
    }

    public static Vector3 v(float x, float y, float z) { return new Vector3(x, y, z); }
}
