package com.broadside.speedway.render;

import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.g3d.Material;
import com.badlogic.gdx.graphics.g3d.Model;
import com.badlogic.gdx.graphics.g3d.ModelInstance;
import com.badlogic.gdx.graphics.g3d.model.Node;
import com.badlogic.gdx.graphics.g3d.utils.MeshPartBuilder;
import com.badlogic.gdx.graphics.g3d.utils.ModelBuilder;
import com.badlogic.gdx.math.MathUtils;
import com.badlogic.gdx.math.Matrix4;
import com.badlogic.gdx.math.Quaternion;
import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.utils.Disposable;

import static com.broadside.speedway.render.MeshKit.*;

/**
 * Jointed human built from capsules; posed each frame by two-bone IK (hands to bars/flags,
 * feet to pegs/shale). Pose coordinates are in the figure instance's local space (+z forward).
 */
public class Figure implements Disposable {
    public static final float UPPER_ARM = 0.3f, FORE_ARM = 0.29f, THIGH = 0.45f, SHIN = 0.45f;

    public static class Pose {
        public final Vector3 pelvis = new Vector3(), chest = new Vector3(), head = new Vector3(),
            fwd = new Vector3(0, 0, 1), headFwd = new Vector3(0, 0, 1),
            handL = new Vector3(), handR = new Vector3(), footL = new Vector3(), footR = new Vector3(),
            elbowPoleL = new Vector3(), elbowPoleR = new Vector3(), kneePoleL = new Vector3(), kneePoleR = new Vector3(),
            footFwdL = new Vector3(0, 0, 1), footFwdR = new Vector3(0, 0, 1);
    }

    public final Model model;
    public final ModelInstance instance;
    private final Node torso, pelvis, head;
    private final Node[] upperArm = new Node[2], foreArm = new Node[2], thigh = new Node[2], shin = new Node[2],
        hand = new Node[2], foot = new Node[2], shoulder = new Node[2];
    /** World-space (instance-local) hand positions after the last pose, e.g. for attaching a flag. */
    public final Vector3 handRPos = new Vector3();

    /**
     * @param suit       leathers / trousers material
     * @param vest       race bib or hi-vis vest material (wrapped cylinder) or null
     * @param helmetColor helmet colour, or -1 for a cap (marshal)
     */
    public Figure(Material suit, Material vest, int helmetColor, int capColor, boolean steelShoe, Material glove) {
        ModelBuilder mb = new ModelBuilder();
        mb.begin();
        Material boot = mat(0x0c0c0c, 0.1f, 0.45f, 0.2f);
        Node n;

        n = mb.node(); n.id = "torso";
        MeshPartBuilder b = mb.part("torso", GL20.GL_TRIANGLES, ATTR, suit);
        at(b, new Matrix4().scale(1.15f, 1, 0.78f));
        b.capsule(0.16f, 0.6f, 14);
        if (vest != null) {
            b = mb.part("vest", GL20.GL_TRIANGLES, ATTR, twoSided(vest));
            at(b, new Matrix4().translate(0, 0.04f, 0).scale(1.12f, 1, 0.82f));
            b.cylinder(0.39f, 0.36f, 0.39f, 24, 0, 360, false);
        }
        n = mb.node(); n.id = "pelvis";
        b = mb.part("pelvis", GL20.GL_TRIANGLES, ATTR, suit);
        at(b, new Matrix4().scale(1.15f, 0.8f, 0.85f));
        b.sphere(0.3f, 0.3f, 0.3f, 14, 10);

        n = mb.node(); n.id = "head";
        if (helmetColor >= 0) buildHelmet(mb, helmetColor);
        else buildCapHead(mb, capColor);

        String[] sides = {"L", "R"};
        for (String s : sides) {
            limb(mb, "upperArm" + s, 0.062f, UPPER_ARM + 0.05f, suit);
            limb(mb, "foreArm" + s, 0.052f, FORE_ARM + 0.03f, suit);
            limb(mb, "thigh" + s, 0.085f, THIGH + 0.06f, suit);
            limb(mb, "shin" + s, 0.066f, SHIN + 0.03f, suit);
            n = mb.node(); n.id = "hand" + s;
            b = mb.part("hand" + s, GL20.GL_TRIANGLES, ATTR, glove != null ? glove : mat(0x151515, 0, 0.7f, 0));
            at(b, new Matrix4().scale(0.9f, 1.1f, 1.3f));
            b.sphere(0.11f, 0.11f, 0.11f, 10, 8);
            n = mb.node(); n.id = "shoulder" + s;
            b = mb.part("shoulder" + s, GL20.GL_TRIANGLES, ATTR, suit);
            b.sphere(0.15f, 0.15f, 0.15f, 10, 8);
            n = mb.node(); n.id = "foot" + s;
            b = mb.part("foot" + s, GL20.GL_TRIANGLES, ATTR, boot);
            at(b, tr(0, 0, 0.06f)); b.box(0.11f, 0.09f, 0.27f);
            at(b, new Matrix4().translate(0, 0, 0.18f).scale(1, 0.8f, 1)); b.sphere(0.112f, 0.112f, 0.112f, 10, 8);
            if (steelShoe && s.equals("L")) {
                b = mb.part("shoe", GL20.GL_TRIANGLES, ATTR, mat(0xd8dde2, 1, 0.22f, 0));
                at(b, tr(0, -0.055f, 0.08f)); b.box(0.13f, 0.02f, 0.36f);
            }
        }
        model = mb.end();
        instance = new ModelInstance(model);
        torso = instance.getNode("torso");
        pelvis = instance.getNode("pelvis");
        head = instance.getNode("head");
        for (int i = 0; i < 2; i++) {
            String s = sides[i];
            upperArm[i] = instance.getNode("upperArm" + s);
            foreArm[i] = instance.getNode("foreArm" + s);
            thigh[i] = instance.getNode("thigh" + s);
            shin[i] = instance.getNode("shin" + s);
            hand[i] = instance.getNode("hand" + s);
            foot[i] = instance.getNode("foot" + s);
            shoulder[i] = instance.getNode("shoulder" + s);
        }
    }

    private static void limb(ModelBuilder mb, String id, float r, float len, Material m) {
        Node n = mb.node();
        n.id = id;
        MeshPartBuilder b = mb.part(id, GL20.GL_TRIANGLES, ATTR, m);
        b.capsule(r, Math.max(len, 2 * r + 0.01f), 10);
    }

    /** Speedway helmet: coloured cover, chin guard, peak, goggles. */
    static void buildHelmet(ModelBuilder mb, int color) {
        Material shell = mat(color, 0, 0.32f, 0.8f);
        Material dark = mat(0x111111, 0, 0.5f, 0);
        MeshPartBuilder b = mb.part("shell", GL20.GL_TRIANGLES, ATTR, shell);
        at(b, new Matrix4().scale(0.95f, 1, 1.08f));
        b.sphere(0.32f, 0.32f, 0.32f, 22, 16);
        // chin guard: partial torus around the front, lower
        at(b, new Matrix4().translate(0, -0.075f, 0.02f).rotate(Vector3.X, 14).rotate(Vector3.Z, 90));
        torusX(b, 0.12f, 0.042f, 16, 8, (float) (Math.PI * 0.8), (float) (-Math.PI * 0.4));
        at(b, new Matrix4().translate(0, 0.09f, 0.15f).rotate(Vector3.X, 17));
        b.box(0.19f, 0.012f, 0.11f);
        b = mb.part("goggles", GL20.GL_TRIANGLES, ATTR, dark);
        at(b, new Matrix4().translate(0, 0.03f, 0).rotate(Vector3.X, -7).rotate(Vector3.Z, 90).scale(1, 1.1f, 0.97f));
        torusX(b, 0.158f, 0.014f, 32, 6, (float) (Math.PI * 2), 0);
        at(b, tr(0, -0.085f, 0.16f)); b.box(0.08f, 0.05f, 0.03f);
        at(b, tr(0, 0.025f, 0.155f)); b.box(0.19f, 0.075f, 0.02f);
        b = mb.part("lens", GL20.GL_TRIANGLES, ATTR, mat(0x2a2f45, 0.6f, 0.05f, 1));
        at(b, tr(0, 0.025f, 0.167f)); b.box(0.17f, 0.06f, 0.03f);
    }

    private static void buildCapHead(ModelBuilder mb, int capColor) {
        Material skin = mat(0xd9a27c, 0, 0.7f, 0);
        Material cap = mat(capColor, 0, 0.6f, 0);
        MeshPartBuilder b = mb.part("face", GL20.GL_TRIANGLES, ATTR, skin);
        at(b, new Matrix4().scale(0.92f, 1.08f, 1));
        b.sphere(0.22f, 0.22f, 0.22f, 16, 12);
        at(b, tr(0, -0.12f, 0));
        b.capsule(0.05f, 0.14f, 8);
        b = mb.part("cap", GL20.GL_TRIANGLES, ATTR, cap);
        at(b, tr(0, 0.025f, 0));
        b.sphere(0.235f, 0.235f, 0.235f, 16, 8, 0, 360, 0, 90);
        at(b, new Matrix4().translate(0, 0.03f, 0.08f).scale(1, 1, 1.1f));
        b.cylinder(0.18f, 0.012f, 0.14f, 14, 180, 360, true);
    }

    // ------------------------------------------------------------------ posing
    private final Vector3 up = new Vector3(), right = new Vector3(), tmp = new Vector3(), tmp2 = new Vector3();
    private final Vector3 sh = new Vector3(), hip = new Vector3(), mid = new Vector3(), end = new Vector3();
    private final Quaternion q = new Quaternion();

    /** Two-bone IK: middle joint into out, reachable end point into endOut. */
    public static void solveIK(Vector3 root, Vector3 target, float l1, float l2, Vector3 pole, Vector3 out, Vector3 endOut) {
        Vector3 dir = new Vector3(target).sub(root);
        float d = dir.len();
        dir.nor();
        d = MathUtils.clamp(d, Math.abs(l1 - l2) + 1e-3f, l1 + l2 - 1e-3f);
        float a = (l1 * l1 - l2 * l2 + d * d) / (2 * d);
        float h = (float) Math.sqrt(Math.max(0, l1 * l1 - a * a));
        Vector3 pd = new Vector3(pole).sub(root);
        pd.mulAdd(dir, -pd.dot(dir));
        if (pd.len2() < 1e-8f) pd.set(0, 0, 1).mulAdd(dir, -dir.z);
        pd.nor();
        out.set(root).mulAdd(dir, a).mulAdd(pd, h);
        if (endOut != null) endOut.set(root).mulAdd(dir, d);
    }

    private void between(Node n, Vector3 a, Vector3 b) {
        tmp.set(b).sub(a);
        n.translation.set(a).mulAdd(tmp, 0.5f);
        n.rotation.setFromCross(Vector3.Y, tmp.nor());
        n.scale.set(1, 1, 1);
    }

    private void basis(Node n, Vector3 pos, Vector3 upDir, Vector3 fwdDir) {
        Vector3 y = new Vector3(upDir).nor();
        Vector3 z = new Vector3(fwdDir).mulAdd(y, -fwdDir.dot(y));
        if (z.len2() < 1e-8f) z.set(0, 0, 1);
        z.nor();
        Vector3 x = new Vector3(y).crs(z).nor();
        n.rotation.setFromAxes(true, x.x, y.x, z.x, x.y, y.y, z.y, x.z, y.z, z.z);
        n.translation.set(pos);
        n.scale.set(1, 1, 1);
    }

    public void pose(Pose p) {
        up.set(p.chest).sub(p.pelvis);
        float torsoLen = up.len();
        up.nor();
        right.set(p.fwd).crs(up).nor(); // figure's right (−x when facing +z)
        mid.set(p.pelvis).mulAdd(up, torsoLen * 0.55f);
        basis(torso, mid, up, p.fwd);
        basis(pelvis, p.pelvis, up, p.fwd);
        tmp2.set(p.head).sub(p.chest).nor().add(0, 1, 0);
        basis(head, p.head, tmp2, p.headFwd);
        for (int i = 0; i < 2; i++) {
            float s = i == 0 ? -1 : 1; // L is +x
            sh.set(p.chest).mulAdd(right, 0.2f * s).mulAdd(up, -0.05f);
            shoulder[i].translation.set(sh);
            Vector3 handT = i == 0 ? p.handL : p.handR;
            solveIK(sh, handT, UPPER_ARM, FORE_ARM, i == 0 ? p.elbowPoleL : p.elbowPoleR, mid, end);
            between(upperArm[i], sh, mid);
            between(foreArm[i], mid, end);
            basis(hand[i], end, tmp2.set(end).sub(mid), p.fwd);
            if (i == 1) handRPos.set(end);
            hip.set(p.pelvis).mulAdd(right, 0.1f * s).mulAdd(up, -0.04f);
            Vector3 footT = i == 0 ? p.footL : p.footR;
            solveIK(hip, footT, THIGH, SHIN, i == 0 ? p.kneePoleL : p.kneePoleR, mid, end);
            between(thigh[i], hip, mid);
            between(shin[i], mid, end);
            basis(foot[i], end, Vector3.Y, i == 0 ? p.footFwdL : p.footFwdR);
        }
        instance.calculateTransforms();
    }

    @Override
    public void dispose() { model.dispose(); }
}
