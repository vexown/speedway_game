package com.broadside.speedway;

import com.badlogic.gdx.math.Matrix4;
import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.physics.bullet.Bullet;
import com.badlogic.gdx.physics.bullet.collision.*;
import com.badlogic.gdx.physics.bullet.dynamics.*;
import com.badlogic.gdx.utils.Array;
import com.badlogic.gdx.utils.Disposable;

/** Bullet dynamics world with ground plane, air fence and impact detection. */
public class PhysicsWorld implements Disposable {
    private static boolean bulletReady;

    public static void initBullet() {
        if (!bulletReady) {
            Bullet.init();
            bulletReady = true;
        }
    }

    public final btDiscreteDynamicsWorld world;
    private final btDefaultCollisionConfiguration config;
    private final btCollisionDispatcher dispatcher;
    private final btDbvtBroadphase broadphase;
    private final btSequentialImpulseConstraintSolver solver;
    private final Array<Disposable> owned = new Array<>();
    private final Array<btRigidBody> statics = new Array<>();

    /** Listener for new contacts: tagA/tagB are userValues, impact is the closing speed along the normal. */
    public interface ImpactListener {
        void onImpact(int tagA, int tagB, float impact, Vector3 pointWorld);
    }

    public PhysicsWorld() {
        initBullet();
        config = new btDefaultCollisionConfiguration();
        dispatcher = new btCollisionDispatcher(config);
        broadphase = new btDbvtBroadphase();
        solver = new btSequentialImpulseConstraintSolver();
        world = new btDiscreteDynamicsWorld(dispatcher, broadphase, solver, config);
        world.setGravity(new Vector3(0, -Phys.GRAVITY, 0));
        world.getSolverInfo().setNumIterations(12);

        // Ground
        btStaticPlaneShape plane = new btStaticPlaneShape(new Vector3(0, 1, 0), 0);
        owned.add(plane);
        btRigidBody ground = staticBody(plane, new Matrix4(), 1.0f, 0.3f);
        ground.setUserValue(Phys.TAG_GROUND);
        world.addRigidBody(ground, Phys.G_GROUND, (short) -1);

        buildFence();
    }

    private btRigidBody staticBody(btCollisionShape shape, Matrix4 transform, float friction, float restitution) {
        btRigidBody.btRigidBodyConstructionInfo info =
            new btRigidBody.btRigidBodyConstructionInfo(0, null, shape, Vector3.Zero);
        btRigidBody b = new btRigidBody(info);
        info.dispose();
        b.setWorldTransform(transform);
        b.setFriction(friction);
        b.setRestitution(restitution);
        statics.add(b);
        return b;
    }

    /** Outer air fence: many static boxes following the oval. */
    private void buildFence() {
        float d = Track.WIDTH + 0.45f;
        float step = 2.0f;
        int n = (int) Math.ceil(Track.LENGTH / step);
        Track.Point a = new Track.Point(), b = new Track.Point();
        Matrix4 m = new Matrix4();
        for (int i = 0; i < n; i++) {
            Track.point(i / (float) n * Track.LENGTH, d, a);
            Track.point((i + 1) / (float) n * Track.LENGTH, d, b);
            float len = (float) Math.hypot(b.x - a.x, b.z - a.z);
            btBoxShape box = new btBoxShape(new Vector3(0.45f, 0.7f, len * 0.5f + 0.08f));
            owned.add(box);
            float yaw = (float) Math.atan2(b.x - a.x, b.z - a.z);
            m.setToRotationRad(Vector3.Y, yaw).trn((a.x + b.x) / 2, 0.7f, (a.z + b.z) / 2);
            btRigidBody body = staticBody(box, m, 0.2f, 0.8f);
            body.setUserValue(Phys.TAG_FENCE);
            world.addRigidBody(body, Phys.G_FENCE, (short) (Phys.G_BIKE | Phys.G_RAGDOLL));
        }
    }

    private final Vector3 tmpN = new Vector3();
    private final Vector3 tmpP = new Vector3();

    /**
     * Step the world one fixed step and report newly created contacts with their
     * closing speed computed from the pre-step velocities supplied by the caller.
     */
    public void step(float dt, VelocityLookup vel, ImpactListener listener) {
        world.stepSimulation(dt, 1, dt);
        if (listener == null) return;
        int nm = dispatcher.getNumManifolds();
        for (int i = 0; i < nm; i++) {
            btPersistentManifold m = dispatcher.getManifoldByIndexInternal(i);
            int nc = m.getNumContacts();
            if (nc == 0) continue;
            int tagA = m.getBody0().getUserValue();
            int tagB = m.getBody1().getUserValue();
            float best = 0;
            boolean fresh = false;
            for (int j = 0; j < nc; j++) {
                btManifoldPoint p = m.getContactPoint(j);
                if (p.getLifeTime() > 2) continue;
                fresh = true;
                p.getNormalWorldOnB(tmpN);
                // relative velocity of A w.r.t. B along normal (pointing from B to A)
                Vector3 va = vel.velocity(tagA), vb = vel.velocity(tagB);
                float closing = -((va.x - vb.x) * tmpN.x + (va.y - vb.y) * tmpN.y + (va.z - vb.z) * tmpN.z);
                if (Math.abs(closing) > best) { best = Math.abs(closing); p.getPositionWorldOnB(tmpP); }
            }
            if (fresh) listener.onImpact(tagA, tagB, best, tmpP);
        }
    }

    public interface VelocityLookup {
        /** Pre-step linear velocity of the object with this tag (zero for statics). */
        Vector3 velocity(int tag);
    }

    @Override
    public void dispose() {
        for (btRigidBody b : statics) { world.removeRigidBody(b); b.dispose(); }
        world.dispose();
        solver.dispose();
        broadphase.dispose();
        dispatcher.dispose();
        config.dispose();
        for (Disposable d : owned) d.dispose();
    }
}
