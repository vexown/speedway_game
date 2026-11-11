package com.broadside.speedway.render;

import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.g3d.Material;
import com.badlogic.gdx.graphics.g3d.Model;
import com.badlogic.gdx.graphics.g3d.ModelBatch;
import com.badlogic.gdx.graphics.g3d.ModelInstance;
import com.badlogic.gdx.graphics.g3d.model.Node;
import com.badlogic.gdx.graphics.g3d.utils.MeshPartBuilder;
import com.badlogic.gdx.graphics.g3d.utils.ModelBuilder;
import com.badlogic.gdx.math.MathUtils;
import com.badlogic.gdx.math.Matrix4;
import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.utils.Array;
import com.badlogic.gdx.utils.Disposable;
import com.broadside.speedway.Bike;
import com.broadside.speedway.Phys;
import com.broadside.speedway.Ragdoll;

import static com.broadside.speedway.render.MeshKit.*;

/** Detailed procedural speedway bike + jointed rider + ragdoll visuals for one rider slot. */
public class BikeView implements Disposable {
    public static int helmetColor(String helmet) {
        switch (helmet) {
            case "red": return 0xe01e1e;
            case "blue": return 0x1f5fe0;
            case "white": return 0xf5f5f5;
            default: return 0xf5c518;
        }
    }

    public Bike bike;
    private final Model model;
    public final ModelInstance instance;
    private final Node front, rear;
    public final Figure figure;
    private final Figure.Pose pose = new Figure.Pose();
    private final Material suitMat;
    private final int helmet;
    private final Array<Model> ragModels = new Array<>();
    private final Array<ModelInstance> ragInstances = new Array<>();
    private float wheelSpin;
    public boolean riderVisible = true;
    /** Bike-local (lean frame) to world transform; also the figure's transform. */
    public final Matrix4 leanWorld = new Matrix4();

    public BikeView(Textures tex, int slot, int color, String helmetName) {
        int number = slot + 1;
        helmet = helmetColor(helmetName);
        int frameColor = helmetName.equals("blue") ? 0x1f3a7a : helmetName.equals("white") ? 0xd8d8d8 : 0x181818;
        Material frame = mat(frameColor, 0.4f, 0.35f, 0.6f);
        Material paint = mat(color, 0.2f, 0.25f, 1f);
        Material chrome = mat(0xf0f0f0, 1, 0.12f, 0);
        Material alloy = mat(0xb9bec4, 0.9f, 0.35f, 0);
        Material engine = mat(0x8e9399, 0.85f, 0.42f, 0);
        Material dark = mat(0x1a1a1c, 0.2f, 0.6f, 0);
        Material rubber = mat(0x141414, 0, 0.92f, 0);
        Material white = twoSided(mat(0xf4f4f4, 0, 0.35f, 0.6f));
        Material paint2 = twoSided(mat(color, 0.2f, 0.25f, 1f));
        Material seat = mat(0x101010, 0, 0.8f, 0);
        Material red = mat(0xcc1111, 0, 0.5f, 0);

        ModelBuilder mb = new ModelBuilder();
        mb.begin();
        // ---------- static body (all parts in the lean frame)
        Node body = mb.node();
        body.id = "body";
        Vector3 head = v(0, 1.0f, 0.5f);
        MeshPartBuilder b = mb.part("frame", GL20.GL_TRIANGLES, ATTR, frame);
        tube(b, new Vector3[]{v(0, 1.05f, 0.52f), head, v(0, 0.9f, 0.22f), v(0, 0.86f, -0.12f), v(0, 0.84f, -0.4f), v(0, 0.8f, -0.45f)}, 0.022f, 24, 8, false);
        tube(b, new Vector3[]{v(0, 1.0f, 0.5f), v(0, 0.96f, 0.48f), v(0, 0.6f, 0.36f), v(0, 0.24f, 0.2f), v(0, 0.2f, -0.1f), v(0, 0.36f, -0.42f), v(0, 0.4f, -0.45f)}, 0.024f, 30, 8, false);
        for (float x : new float[]{0.07f, -0.07f}) {
            tube(b, new Vector3[]{v(x * 0.4f, 0.35f, -0.38f), v(x * 0.5f, 0.36f, -0.42f), v(x, 0.38f, -0.56f), v(x, 0.38f, -0.66f), v(x, 0.38f, -0.68f)}, 0.016f, 10, 6, false);
            tube(b, new Vector3[]{v(0, 0.85f, -0.38f), v(0, 0.84f, -0.4f), v(x * 0.8f, 0.62f, -0.55f), v(x, 0.4f, -0.66f), v(x, 0.38f, -0.68f)}, 0.014f, 10, 6, false);
        }
        at(b, new Matrix4().translate(head).rotate(Vector3.X, 24));
        b.cylinder(0.07f, 0.16f, 0.07f, 12);
        b = mb.part("chrome", GL20.GL_TRIANGLES, ATTR, chrome);
        at(b, new Matrix4());
        for (float x : new float[]{0.06f, -0.06f})
            tube(b, new Vector3[]{v(x, 1.1f, 0.45f), v(x, 1.06f, 0.47f), v(x, 0.75f, 0.6f), v(x, 0.48f, 0.68f), v(x, 0.45f, 0.69f)}, 0.02f, 12, 8, false);
        // exhaust sweeping down the right side, upswept silencer
        tube(b, new Vector3[]{v(0, 0.78f, 0.2f), v(0, 0.76f, 0.22f), v(-0.06f, 0.66f, 0.36f), v(-0.14f, 0.42f, 0.32f), v(-0.16f, 0.3f, 0f), v(-0.16f, 0.42f, -0.35f), v(-0.15f, 0.62f, -0.58f), v(-0.15f, 0.64f, -0.6f)}, 0.026f, 40, 8, false);
        at(b, new Matrix4().translate(-0.15f, 0.7f, -0.7f).rotate(Vector3.X, -90 - 31.5f));
        b.cylinder(0.1f, 0.32f, 0.1f, 16);
        // handlebars (wide, high-rise)
        at(b, new Matrix4());
        tube(b, new Vector3[]{v(0.46f, 1.21f, 0.35f), v(0.44f, 1.2f, 0.36f), v(0.3f, 1.16f, 0.42f), v(0.12f, 1.1f, 0.48f), v(0, 1.09f, 0.49f), v(-0.12f, 1.1f, 0.48f), v(-0.3f, 1.16f, 0.42f), v(-0.44f, 1.2f, 0.36f), v(-0.46f, 1.21f, 0.35f)}, 0.012f, 30, 6, false);
        at(b, tr(0, 1.05f, 0.25f)); b.cylinder(0.05f, 0.02f, 0.05f, 12);
        // primary chain cover
        at(b, tr(0.11f, 0.35f, -0.05f)); b.box(0.03f, 0.17f, 0.36f);

        b = mb.part("alloy", GL20.GL_TRIANGLES, ATTR, alloy);
        for (float x : new float[]{0.06f, -0.06f}) {
            at(b, new Matrix4().translate(x, 0.42f, 0.72f).rotate(Vector3.X, 28.6f)); b.box(0.025f, 0.04f, 0.16f);
        }
        at(b, tr(0, 1.05f, 0.47f)); b.box(0.17f, 0.03f, 0.08f);
        at(b, tr(0, 0.79f, 0.12f, 6.9f, 0, 0)); b.box(0.2f, 0.09f, 0.2f);
        at(b, new Matrix4().translate(0, 0.74f, -0.04f).rotate(Vector3.X, 90 + 17)); b.cylinder(0.07f, 0.1f, 0.07f, 12);
        at(b, new Matrix4().translate(0.32f, 1.17f, 0.45f).rotate(Vector3.Y, 28.6f)); b.box(0.15f, 0.012f, 0.02f);
        at(b, new Matrix4().translate(-0.12f, 0.33f, -0.08f).rotate(Vector3.Z, 90)); b.cylinder(0.036f, 0.1f, 0.036f, 8);

        b = mb.part("engine", GL20.GL_TRIANGLES, ATTR, engine);
        at(b, tr(0, 0.36f, 0.06f)); b.box(0.17f, 0.24f, 0.32f);
        Matrix4 cylM = new Matrix4().translate(0, 0.5f, 0.08f).rotate(Vector3.X, 6.9f);
        at(b, new Matrix4(cylM).translate(0, 0.1f, 0)); b.cylinder(0.14f, 0.25f, 0.14f, 16);
        for (int i = 0; i < 7; i++) {
            float r = 0.23f - i * 0.008f;
            at(b, new Matrix4(cylM).translate(0, i * 0.035f, 0)); b.cylinder(r, 0.012f, r, 20);
        }
        at(b, tr(0, 0.85f, 0.12f)); b.box(0.14f, 0.05f, 0.14f);

        b = mb.part("paint", GL20.GL_TRIANGLES, ATTR, paint);
        at(b, new Matrix4().translate(0, 0.97f, 0.22f).rotate(Vector3.X, -10.3f).scale(0.8f, 0.62f, 1.35f));
        b.sphere(0.26f, 0.26f, 0.26f, 20, 14);
        for (float x : new float[]{0.06f, -0.06f}) {
            at(b, new Matrix4().translate(x, 0.6f, 0.69f).rotate(Vector3.X, 20)); b.cylinder(0.044f, 0.16f, 0.044f, 10);
        }
        b = mb.part("stripe", GL20.GL_TRIANGLES, ATTR, mat(0xf4f4f4, 0, 0.35f, 0.6f));
        at(b, new Matrix4().translate(0, 0.97f, 0.22f).rotate(Vector3.X, -10.3f).scale(0.81f, 0.63f, 1.36f));
        b.sphere(0.26f, 0.26f, 0.26f, 20, 3, 0, 360, 50, 68);

        b = mb.part("seat", GL20.GL_TRIANGLES, ATTR, seat);
        at(b, tr(0, 0.9f, -0.28f, 2.9f, 0, 0)); b.box(0.15f, 0.06f, 0.34f);
        b = mb.part("dark", GL20.GL_TRIANGLES, ATTR, dark);
        at(b, new Matrix4());
        tube(b, new Vector3[]{v(0.075f, 0.43f, -0.05f), v(0.075f, 0.48f, -0.66f), v(0.075f, 0.38f, -0.79f), v(0.075f, 0.27f, -0.66f), v(0.075f, 0.33f, -0.05f)}, 0.008f, 40, 4, true);
        at(b, new Matrix4().translate(-0.15f, 0.78f, -0.84f).rotate(Vector3.X, -90 - 31.5f)); b.cylinder(0.06f, 0.02f, 0.06f, 12);
        b = mb.part("rubber", GL20.GL_TRIANGLES, ATTR, rubber);
        for (float x : new float[]{0.4f, -0.4f}) {
            at(b, new Matrix4().translate(x, 1.19f, 0.37f).rotate(Vector3.Y, x > 0 ? 20 : -20).rotate(Vector3.Z, 90));
            b.cylinder(0.04f, 0.12f, 0.04f, 10);
        }
        b = mb.part("lanyard", GL20.GL_TRIANGLES, ATTR, red);
        at(b, new Matrix4());
        tube(b, new Vector3[]{v(-0.31f, 1.17f, 0.43f), v(-0.3f, 1.16f, 0.42f), v(-0.25f, 1.05f, 0.3f), v(-0.18f, 1.12f, 0.1f), v(-0.17f, 1.13f, 0.08f)}, 0.006f, 12, 4, false);
        // mudguard + shale deflector (cylinder bands around the rear axle)
        b = mb.part("guard", GL20.GL_TRIANGLES, ATTR, white);
        at(b, tr(0, 0.38f, -0.66f));
        arcBandX(b, 0.43f, 0.1f, (float) (Math.PI * 0.36), (float) (Math.PI * 1.1), 20);
        b = mb.part("deflector", GL20.GL_TRIANGLES, ATTR, paint2);
        at(b, tr(0, 0.38f, -0.66f));
        arcBandX(b, 0.475f, 0.15f, (float) (Math.PI * 0.52), (float) (Math.PI * 0.94), 16);
        // number plates
        Material plateMat = texMat(tex.numberPlate(number), 0.5f);
        b = mb.part("plates", GL20.GL_TRIANGLES, ATTR, twoSided(plateMat));
        at(b, new Matrix4().translate(0, 0.64f, -1.1f).rotate(Vector3.Y, 180).rotate(Vector3.X, -31.5f));
        b.rect(-0.13f, -0.09f, 0, 0.13f, -0.09f, 0, 0.13f, 0.09f, 0, -0.13f, 0.09f, 0, 0, 0, 1);
        at(b, new Matrix4().translate(0, 0.92f, 0.6f).rotate(Vector3.X, -20));
        b.rect(-0.13f, -0.1f, 0, 0.13f, -0.1f, 0, 0.13f, 0.1f, 0, -0.13f, 0.1f, 0, 0, 0, 1);

        // ---------- wheels
        wheelNode(mb, "front", true, rubber, alloy);
        wheelNode(mb, "rear", false, rubber, alloy);
        model = mb.end();
        instance = new ModelInstance(model);
        // Nodes must come from the instance (ModelInstance copies the model's nodes).
        front = instance.getNode("front");
        rear = instance.getNode("rear");

        // ---------- rider
        suitMat = texMat(tex.suit(0x16161a, color), 0.62f);
        Material vest = texMat(tex.bib(helmet, number), 0.7f);
        figure = new Figure(suitMat, vest, helmet, 0, true, null);
        pose.pelvis.set(0, 1.0f, -0.3f);
        pose.chest.set(0, 1.42f, -0.02f);
        pose.head.set(0, 1.64f, 0.13f);
        pose.fwd.set(0, -0.3f, 1).nor();
        pose.headFwd.set(0, -0.15f, 1).nor();
        pose.handL.set(0.41f, 1.2f, 0.37f);
        pose.handR.set(-0.41f, 1.2f, 0.37f);
        pose.elbowPoleL.set(1.2f, 0.9f, -0.4f);
        pose.elbowPoleR.set(-1.2f, 0.9f, -0.4f);
        pose.footL.set(0.16f, 0.34f, -0.06f);
        pose.footR.set(-0.17f, 0.36f, -0.08f);
        pose.kneePoleL.set(0.5f, 1.0f, 1.5f);
        pose.kneePoleR.set(-0.5f, 1.0f, 1.5f);
    }

    private Node wheelNode(ModelBuilder mb, String id, boolean isFront, Material rubber, Material alloy) {
        Node n = mb.node();
        n.id = id;
        float R = isFront ? 0.335f : 0.31f, r = isFront ? 0.036f : 0.068f;
        MeshPartBuilder b = mb.part(id + "Tyre", GL20.GL_TRIANGLES, ATTR, rubber);
        torusX(b, R, r, 40, 12, (float) (Math.PI * 2), 0);
        int knobs = isFront ? 40 : 34;
        for (int i = 0; i < knobs; i++) {
            float a = (float) (i / (double) knobs * 360);
            if (isFront) {
                at(b, new Matrix4().rotate(Vector3.X, a).translate(0, 0.37f, 0));
                b.box(0.05f, 0.012f, 0.02f);
            } else {
                for (float off : new float[]{-0.035f, 0.035f}) {
                    at(b, new Matrix4().rotate(Vector3.X, a).translate(off * (i % 2 == 1 ? 1 : 0.6f), 0.345f, 0));
                    b.box(0.03f, 0.022f, 0.034f);
                }
            }
        }
        at(b, new Matrix4());
        b = mb.part(id + "Rim", GL20.GL_TRIANGLES, ATTR, alloy);
        torusX(b, isFront ? 0.3f : 0.245f, isFront ? 0.012f : 0.016f, 36, 6, (float) (Math.PI * 2), 0);
        at(b, new Matrix4().rotate(Vector3.Z, 90)); b.cylinder(0.11f, 0.12f, 0.11f, 14);
        if (!isFront) { at(b, new Matrix4().translate(0.075f, 0, 0).rotate(Vector3.Z, 90)); b.cylinder(0.24f, 0.008f, 0.24f, 28); }
        at(b, new Matrix4());
        // spokes (crossed lacing) as thin boxes
        float rimR = isFront ? 0.295f : 0.24f, hubR = isFront ? 0.04f : 0.05f, w = isFront ? 0.04f : 0.05f;
        b = mb.part(id + "Spokes", GL20.GL_TRIANGLES, ATTR, mat(0xc8ccd0, 0.8f, 0.3f, 0));
        for (int i = 0; i < 32; i++) {
            float a = (float) (i / 32.0 * Math.PI * 2);
            float side = i % 2 == 1 ? w : -w;
            float ha = a + (i % 4 < 2 ? 0.35f : -0.35f);
            Vector3 p0 = v(side, (float) Math.cos(ha) * hubR, (float) Math.sin(ha) * hubR);
            Vector3 p1 = v(0, (float) Math.cos(a) * rimR, (float) Math.sin(a) * rimR);
            Vector3 dir = new Vector3(p1).sub(p0);
            float len = dir.len();
            Matrix4 m = new Matrix4().translate(new Vector3(p0).mulAdd(dir, 0.5f))
                .rotate(new com.badlogic.gdx.math.Quaternion().setFromCross(Vector3.Y, dir.nor()));
            at(b, m);
            b.box(0.006f, len, 0.006f);
        }
        at(b, new Matrix4());
        return n;
    }

    public void bind(Bike bike) {
        this.bike = bike;
        clearRagdoll();
    }

    private final Matrix4 bodyM = new Matrix4();
    private final Vector3 pos = new Vector3();

    public void sync(float dt, boolean hideRider) {
        Bike bk = bike;
        if (bk.state == Bike.State.CRASHED) {
            bk.transform(bodyM);
            leanWorld.set(bodyM).translate(0, -Phys.RIDE_HEIGHT, 0);
            riderVisible = false;
            syncRagdoll(true);
        } else {
            bk.position(pos);
            leanWorld.setToTranslation(pos.x, 0, pos.z).rotateRad(Vector3.Y, bk.heading()).rotateRad(Vector3.Z, -bk.lean);
            riderVisible = !hideRider;
            updateRiderPose(bk.lean, bk.speed, bk.effThrottle);
            syncRagdoll(false);
        }
        instance.transform.set(leanWorld);
        figure.instance.transform.set(leanWorld);
        wheelSpin += bk.speed / 0.37f * dt * (bk.state == Bike.State.CRASHED ? 0.3f : 1);
        front.translation.set(0, 0.37f, 0.76f);
        front.rotation.setFromAxisRad(Vector3.X, wheelSpin);
        rear.translation.set(0, 0.38f, -0.66f);
        rear.rotation.setFromAxisRad(Vector3.X, wheelSpin * (1 + bk.throttleIn * 0.25f));
        instance.calculateTransforms();
    }

    private void updateRiderPose(float lean, float speed, float throttle) {
        float k = MathUtils.clamp((lean - 0.12f) / 0.45f, 0, 1) * Math.min(1, speed / 8);
        float tuck = 0.04f * throttle;
        pose.pelvis.set(0.05f * k, 1.0f, -0.3f + tuck);
        pose.chest.set(0.12f * k, 1.42f - tuck, -0.02f + tuck * 2);
        pose.head.set(0.14f * k, 1.64f - tuck, 0.13f + tuck * 2);
        float fx = 0.16f + 0.42f * k;
        float groundY = fx * (float) Math.tan(Math.max(0, lean)) + 0.06f;
        pose.footL.set(fx, MathUtils.lerp(0.34f, groundY, k), -0.06f + 0.62f * k);
        pose.kneePoleL.set(0.5f + 0.8f * k, 1.0f + 0.4f * k, 1.5f - 1.2f * k);
        pose.footFwdL.set(0.25f * k, 0, 1);
        figure.pose(pose);
    }

    // ---------- ragdoll visuals
    private void clearRagdoll() {
        for (Model m : ragModels) m.dispose();
        ragModels.clear();
        ragInstances.clear();
        lastRagdoll = null;
    }

    private Ragdoll lastRagdoll;

    private void syncRagdoll(boolean show) {
        Ragdoll rd = bike.ragdoll;
        if (!show || rd == null || rd.parts.size == 0) {
            ragVisible = false;
            return;
        }
        if (rd != lastRagdoll) {
            if (ragInstances.size == 0) {
                for (Ragdoll.Part p : rd.parts) {
                    ModelBuilder mb = new ModelBuilder();
                    mb.begin();
                    mb.node();
                    if (p.sphere) {
                        Figure.buildHelmet(mb, helmet);
                    } else {
                        Vector3 he = p.size;
                        float r = Math.min(he.x, he.z) * (p.name.equals("torso") ? 1.2f : 1.05f);
                        MeshPartBuilder b = mb.part(p.name, GL20.GL_TRIANGLES, ATTR, suitMat);
                        if (p.name.equals("torso")) at(b, new Matrix4().scale(he.x / r, 1, he.z / r * 1.1f));
                        b.capsule(r, Math.max(he.y * 2 + r, 2 * r + 0.01f), 10);
                    }
                    Model m = mb.end();
                    ragModels.add(m);
                    ragInstances.add(new ModelInstance(m));
                }
            }
            lastRagdoll = rd;
        }
        for (int i = 0; i < rd.parts.size && i < ragInstances.size; i++) rd.parts.get(i).body.getWorldTransform(ragInstances.get(i).transform);
        ragVisible = true;
    }

    private boolean ragVisible;

    public void render(ModelBatch batch) {
        batch.render(instance);
        if (riderVisible) batch.render(figure.instance);
        if (ragVisible) for (ModelInstance mi : ragInstances) batch.render(mi);
    }

    /** World position of the rear tyre contact patch (for roost particles). */
    public Vector3 rearContact(Vector3 out) {
        return out.set(0, 0.05f, -0.75f).mul(leanWorld);
    }

    @Override
    public void dispose() {
        model.dispose();
        figure.dispose();
        clearRagdoll();
    }
}
