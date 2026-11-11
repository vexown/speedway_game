package com.broadside.speedway;

import com.badlogic.gdx.math.MathUtils;
import com.badlogic.gdx.math.Matrix4;
import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.physics.bullet.collision.btBoxShape;
import com.badlogic.gdx.physics.bullet.collision.btCollisionShape;
import com.badlogic.gdx.physics.bullet.collision.btSphereShape;
import com.badlogic.gdx.physics.bullet.dynamics.btConeTwistConstraint;
import com.badlogic.gdx.physics.bullet.dynamics.btRigidBody;
import com.badlogic.gdx.physics.bullet.dynamics.btTypedConstraint;
import com.badlogic.gdx.physics.bullet.linearmath.btMotionState;
import com.badlogic.gdx.utils.Array;
import com.badlogic.gdx.utils.Disposable;

/** Fallen rider: torso, head, arms and legs joined by cone-twist constraints. */
public class Ragdoll implements Disposable {
    public static final class Part {
        public final String name;
        public final btRigidBody body;
        public final btCollisionShape shape;
        /** half extents (box) or radius in x (sphere) */
        public final Vector3 size;
        public final boolean sphere;
        Part(String name, btRigidBody body, btCollisionShape shape, Vector3 size, boolean sphere) {
            this.name = name; this.body = body; this.shape = shape; this.size = size; this.sphere = sphere;
        }
    }

    public final Array<Part> parts = new Array<>();
    private final Array<btTypedConstraint> constraints = new Array<>();
    private final PhysicsWorld pw;
    public Part torso;

    public Ragdoll(PhysicsWorld pw, Bike bike) {
        this.pw = pw;
        float th = bike.heading();
        Vector3 base = bike.position(new Vector3());
        base.y = 0;
        Vector3 vel = bike.velocity(new Vector3());
        int tag = Phys.TAG_RAGDOLL + bike.id;

        torso = mk("torso", false, new Vector3(0.2f, 0.3f, 0.13f), 32, th, base, 0, 1.45f, -0.1f, vel, tag);
        Part head = mk("head", true, new Vector3(0.15f, 0.15f, 0.15f), 6, th, base, 0, 1.95f, -0.05f, vel, tag);
        Part armL = mk("armL", false, new Vector3(0.06f, 0.28f, 0.06f), 4, th, base, 0.3f, 1.45f, -0.1f, vel, tag);
        Part armR = mk("armR", false, new Vector3(0.06f, 0.28f, 0.06f), 4, th, base, -0.3f, 1.45f, -0.1f, vel, tag);
        Part legL = mk("legL", false, new Vector3(0.08f, 0.42f, 0.08f), 10, th, base, 0.12f, 0.75f, -0.1f, vel, tag);
        Part legR = mk("legR", false, new Vector3(0.08f, 0.42f, 0.08f), 10, th, base, -0.12f, 0.75f, -0.1f, vel, tag);

        join(torso, head, 0, 0.33f, 0, 0, -0.17f, 0, 0.6f);
        join(torso, armL, 0.27f, 0.25f, 0, 0, 0.3f, 0, 1.8f);
        join(torso, armR, -0.27f, 0.25f, 0, 0, 0.3f, 0, 1.8f);
        join(torso, legL, 0.12f, -0.32f, 0, 0, 0.44f, 0, 1.3f);
        join(torso, legR, -0.12f, -0.32f, 0, 0, 0.44f, 0, 1.3f);
    }

    private Part mk(String name, boolean sphere, Vector3 size, float mass, float th, Vector3 base,
                    float lx, float ly, float lz, Vector3 vel, int tag) {
        btCollisionShape shape = sphere ? new btSphereShape(size.x) : new btBoxShape(size);
        Vector3 inertia = new Vector3();
        shape.calculateLocalInertia(mass, inertia);
        btRigidBody.btRigidBodyConstructionInfo info =
            new btRigidBody.btRigidBodyConstructionInfo(mass, (btMotionState) null, shape, inertia);
        btRigidBody b = new btRigidBody(info);
        info.dispose();
        Matrix4 m = new Matrix4().setToRotationRad(Vector3.Y, th);
        Vector3 local = new Vector3(lx, ly, lz).rot(m).add(base);
        m.trn(local);
        b.setWorldTransform(m);
        b.setFriction(0.7f);
        b.setRestitution(0.3f);
        b.setDamping(0.05f, 0.3f);
        float j = 2.5f;
        b.setLinearVelocity(new Vector3(vel.x * 0.9f + (MathUtils.random() - 0.5f) * j,
            2.0f + MathUtils.random() * 2, vel.z * 0.9f + (MathUtils.random() - 0.5f) * j));
        b.setAngularVelocity(new Vector3((MathUtils.random() - 0.5f) * j * 2, (MathUtils.random() - 0.5f) * j,
            (MathUtils.random() - 0.5f) * j * 2));
        b.setUserValue(tag);
        b.setActivationState(4);
        pw.world.addRigidBody(b, Phys.G_RAGDOLL, (short) (Phys.G_GROUND | Phys.G_FENCE | Phys.G_BIKE | Phys.G_RAGDOLL));
        Part p = new Part(name, b, shape, size, sphere);
        parts.add(p);
        return p;
    }

    private void join(Part a, Part b, float ax, float ay, float az, float bx, float by, float bz, float swing) {
        // Cone-twist twist axis is local X; rotate frames so X points along the limb (Y).
        Matrix4 fa = new Matrix4().setToRotation(Vector3.Z, 90).trn(ax, ay, az);
        Matrix4 fb = new Matrix4().setToRotation(Vector3.Z, 90).trn(bx, by, bz);
        btConeTwistConstraint c = new btConeTwistConstraint(a.body, b.body, fa, fb);
        c.setLimit(swing, swing, 0.6f);
        pw.world.addConstraint(c, true);
        constraints.add(c);
    }

    @Override
    public void dispose() {
        for (btTypedConstraint c : constraints) { pw.world.removeConstraint(c); c.dispose(); }
        for (Part p : parts) { pw.world.removeRigidBody(p.body); p.body.dispose(); p.shape.dispose(); }
        constraints.clear();
        parts.clear();
    }
}
