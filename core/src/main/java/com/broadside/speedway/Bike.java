package com.broadside.speedway;

import com.badlogic.gdx.math.MathUtils;
import com.badlogic.gdx.math.Matrix4;
import com.badlogic.gdx.math.Quaternion;
import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.physics.bullet.collision.btBoxShape;
import com.badlogic.gdx.physics.bullet.dynamics.btRigidBody;
import com.badlogic.gdx.physics.bullet.linearmath.btMotionState;
import com.badlogic.gdx.utils.Array;
import com.badlogic.gdx.utils.Disposable;

/** One speedway bike + rider: arcade broadside tyre model on a Bullet rigid body. */
public class Bike implements Disposable {
    public enum State { HELD, RIDING, CRASHED }

    public final int id;
    public final String name;
    public final int color;
    public final String helmet;
    public final boolean isPlayer;
    public float throttleIn, steerIn;
    public float powerMul = 1, gripMul = 1;
    public boolean assist;
    public State state = State.HELD;
    public float progress, lastS, d;
    public int lap, gate;
    public boolean finished;
    public float finishTime;
    public float crashTimer;
    public int crashCount;
    public String crashReason = "";
    public Ragdoll ragdoll;
    public float drift, slide, speed, rpm, lean, latAccel, effThrottle;
    public float stuckTime;
    String pendingCrash;
    float overslideTime;
    float prevVelAng = Float.NaN;
    public final Array<Event> events = new Array<>();

    final PhysicsWorld pw;
    public final btRigidBody body;
    private final btBoxShape shape;

    public static final class Event {
        public final String type; public final float impact; public final Bike other; public final String reason;
        Event(String type, float impact, Bike other, String reason) {
            this.type = type; this.impact = impact; this.other = other; this.reason = reason;
        }
    }

    private final Matrix4 xf = new Matrix4();
    private final Vector3 v = new Vector3();
    private final Vector3 tmp = new Vector3();
    private final Track.Proj proj = new Track.Proj();
    private final Track.Point tp = new Track.Point();
    public final Vector3 preVel = new Vector3();

    public Bike(PhysicsWorld pw, int id, String name, int color, String helmet, boolean isPlayer) {
        this.pw = pw;
        this.id = id;
        this.name = name;
        this.color = color;
        this.helmet = helmet;
        this.isPlayer = isPlayer;
        shape = new btBoxShape(new Vector3(0.34f, 0.55f, 1.0f));
        Vector3 inertia = new Vector3();
        shape.calculateLocalInertia(Phys.MASS, inertia);
        btRigidBody.btRigidBodyConstructionInfo info =
            new btRigidBody.btRigidBodyConstructionInfo(Phys.MASS, (btMotionState) null, shape, inertia);
        body = new btRigidBody(info);
        info.dispose();
        body.setFriction(0.4f);
        body.setRestitution(0.4f);
        body.setDamping(0f, 0.1f);
        body.setActivationState(4); // DISABLE_DEACTIVATION
        body.setUserValue(Phys.TAG_BIKE + id);
        pw.world.addRigidBody(body, Phys.G_BIKE, Phys.MASK_BIKE);
        setRiding();
    }

    void setMask(short mask) {
        pw.world.removeRigidBody(body);
        pw.world.addRigidBody(body, Phys.G_BIKE, mask);
    }

    void setRiding() {
        body.setLinearFactor(new Vector3(1, 0, 1));
        body.setAngularFactor(new Vector3(0, 1, 0));
        float h = heading();
        body.getWorldTransform(xf);
        xf.getTranslation(tmp);
        xf.setToRotationRad(Vector3.Y, h).trn(tmp.x, Phys.RIDE_HEIGHT, tmp.z);
        body.setWorldTransform(xf);
        body.setAngularVelocity(Vector3.Zero);
        Vector3 lv = body.getLinearVelocity();
        body.setLinearVelocity(tmp.set(lv.x, 0, lv.z));
    }

    public float heading() {
        body.getWorldTransform(xf);
        return (float) Math.atan2(xf.val[Matrix4.M02], xf.val[Matrix4.M22]);
    }

    public Vector3 position(Vector3 out) {
        body.getWorldTransform(xf);
        return xf.getTranslation(out);
    }

    public Matrix4 transform(Matrix4 out) {
        body.getWorldTransform(out);
        return out;
    }

    public Vector3 velocity(Vector3 out) {
        return out.set(body.getLinearVelocity());
    }

    public void placeAt(float s, float dd, float prog) {
        Track.point(s, dd, tp);
        xf.setToRotationRad(Vector3.Y, tp.heading).trn(tp.x, Phys.RIDE_HEIGHT, tp.z);
        body.setWorldTransform(xf);
        body.setLinearVelocity(Vector3.Zero);
        body.setAngularVelocity(Vector3.Zero);
        body.clearForces();
        lastS = Track.project(tp.x, tp.z, proj).s;
        prevVelAng = Float.NaN;
        progress = prog;
        d = dd;
        setRiding();
    }

    void onImpact(boolean fence, Bike other, boolean ragdollHit, float impact) {
        if (state != State.RIDING) return;
        if (fence) {
            events.add(new Event("fence", impact, null, null));
            if (impact > Phys.FENCE_IMPACT_CRASH) pendingCrash = "hit the fence";
        } else if (other != null) {
            events.add(new Event("bike", impact, other, null));
            boolean otherRiding = other.state == State.RIDING;
            float threshold = otherRiding ? Phys.BIKE_IMPACT_CRASH : Phys.BIKE_IMPACT_CRASH * 0.7f;
            if (impact > threshold) pendingCrash = otherRiding
                ? "tangled with " + other.name : "hit " + other.name + "'s fallen bike";
        } else if (ragdollHit) {
            events.add(new Event("rider", impact, null, null));
        }
    }

    /** Apply tyre/engine forces and steering. Called before each fixed step. */
    public void update(float dt) {
        body.getWorldTransform(xf);
        float px = xf.val[Matrix4.M03], pz = xf.val[Matrix4.M23];
        v.set(body.getLinearVelocity());
        Track.project(px, pz, proj);
        float ds = Track.lapDelta(proj.s, lastS);
        lastS = proj.s;
        d = proj.d;

        if (state == State.HELD) {
            body.setLinearVelocity(Vector3.Zero);
            body.setAngularVelocity(Vector3.Zero);
            rpm += ((0.15f + throttleIn * 0.85f) - rpm) * Math.min(1, dt * 6);
            speed = 0; slide = 0;
            return;
        }
        if (state == State.CRASHED) {
            crashTimer -= dt;
            if (crashTimer < Phys.REMOUNT_TIME - 0.6f && !maskRestored) {
                setMask(Phys.MASK_BIKE);
                maskRestored = true;
            }
            rpm += (0.08f - rpm) * Math.min(1, dt * 3);
            speed = (float) Math.hypot(v.x, v.z);
            slide = 0;
            return;
        }

        progress += ds;
        float th = (float) Math.atan2(xf.val[Matrix4.M02], xf.val[Matrix4.M22]);
        float fx = MathUtils.sin(th), fz = MathUtils.cos(th);
        float lx = MathUtils.cos(th), lz = -MathUtils.sin(th);
        float vf = v.x * fx + v.z * fz;
        float vl = v.x * lx + v.z * lz;
        float spd = (float) Math.hypot(v.x, v.z);
        speed = spd;

        float throttle = finished ? Math.min(throttleIn, 0.25f) : throttleIn;
        float steer = MathUtils.clamp(steerIn, -1, 1);
        if (assist) {
            float fast = MathUtils.clamp((spd - Phys.FEATHER_SPEED) / 7f, 0, 1);
            throttle *= 1 - Phys.ASSIST_FEATHER * Math.abs(steer) * fast;
        }
        effThrottle = throttle;
        float mg = Phys.MASS * Phys.GRAVITY;

        // Engine / longitudinal
        float vfPos = Math.max(vf, 2.5f);
        float drive = throttle * powerMul * Math.min(Phys.MAX_DRIVE, Phys.POWER / vfPos);
        float resist = Phys.DRAG * vf * Math.abs(vf) + Phys.ROLLING * Math.signum(vf)
            + (1 - throttle) * Phys.ENGINE_BRAKE * Math.min(1, Math.max(vf, 0) / 3);
        float infield = 0;
        if (proj.d < -0.4f) infield = Phys.INFIELD_DRAG * Math.min(1, (-proj.d - 0.4f) / 1.5f);
        if (proj.d > Track.WIDTH + 3) infield = Phys.INFIELD_DRAG;

        // Friction circle
        float gripCap = Phys.GRIP * gripMul * (assist ? Phys.ASSIST_GRIP_BOOST : 1) * mg
            * (1 - Phys.THROTTLE_GRIP_LOSS * throttle);
        drive = Math.min(drive, gripCap * 0.92f);
        float latCap = (float) Math.sqrt(Math.max(gripCap * gripCap - drive * drive, 0)) + mg * 0.15f;
        float latF = -latCap * (float) Math.tanh(vl / Phys.SLIP_SPEED);
        float fLong = drive - resist - infield * vf;
        body.applyCentralForce(tmp.set(fx * fLong + lx * latF, 0, fz * fLong + lz * latF));

        // Steering
        float authority = Phys.MAX_YAW_RATE * (0.55f + 0.45f * Math.min(1, spd / 6)) / (1 + spd / 60);
        float velAng = spd > 1.5f ? (float) Math.atan2(v.x, v.z) : th;
        float curDrift = spd > 1.5f ? Track.wrapAngle(th - velAng) : 0;
        float velTurn = Float.isNaN(prevVelAng) ? 0 : Track.wrapAngle(velAng - prevVelAng) / dt;
        prevVelAng = velAng;
        float targetYaw;
        if (assist) {
            float k = MathUtils.clamp((spd - 2) / 8, 0, 1);
            float speedDrift = Phys.ASSIST_SPEED_DRIFT * Math.max(0, spd - 17) / 10;
            float driftTarget = steer * Math.min(1.0f, Phys.ASSIST_BASE_DRIFT + Phys.ASSIST_THROTTLE_DRIFT * throttle + speedDrift);
            float slideYaw = MathUtils.clamp(velTurn, -3, 3) + Phys.ASSIST_HOLD_GAIN * (driftTarget - curDrift);
            targetYaw = k * slideYaw + (1 - k) * steer * authority;
        } else {
            targetYaw = steer * authority;
            float excess = Math.abs(curDrift) - Phys.ASSIST_DRIFT;
            if (excess > 0) targetYaw -= Math.signum(curDrift) * excess * Phys.ASSIST_GAIN;
        }
        Vector3 w = body.getAngularVelocity();
        float wy = w.y + (targetYaw - w.y) * Math.min(1, dt * 10);
        body.setAngularVelocity(tmp.set(0, wy, 0));

        // Derived state
        drift = curDrift;
        slide = Math.min(1, Math.abs(vl) / 4 + throttle * Math.min(1, spd / 8) * 0.4f);
        latAccel = spd * wy;
        float targetLean = MathUtils.clamp((float) Math.atan2(latAccel, Phys.GRAVITY) * 0.7f + drift * 0.3f, -0.85f, 0.85f);
        lean += (targetLean - lean) * Math.min(1, dt * 7);
        float targetRpm = 0.25f + 0.75f * Math.min(1, spd / 30) * (0.6f + 0.4f * throttle) + throttle * Math.abs(vl) * 0.04f;
        rpm += (Math.min(1.05f, targetRpm) - rpm) * Math.min(1, dt * 8);

        if (spd > 9 && Math.abs(drift) > Phys.OVERSLIDE_ANGLE) overslideTime += dt;
        else overslideTime = Math.max(0, overslideTime - dt * 2);
        if (pendingCrash == null && overslideTime > 0.3f) pendingCrash = "overslid and fell";
    }

    private boolean maskRestored = true;

    /** After the world step: resolve falls, keep riding body at ride height. */
    public void postStep() {
        if (pendingCrash != null && state == State.RIDING) crash(pendingCrash);
        pendingCrash = null;
        if (state == State.RIDING) {
            body.getWorldTransform(xf);
            if (Math.abs(xf.val[Matrix4.M13] - Phys.RIDE_HEIGHT) > 1e-3f) {
                xf.val[Matrix4.M13] = Phys.RIDE_HEIGHT;
                body.setWorldTransform(xf);
            }
        }
    }

    public void crash(String reason) {
        state = State.CRASHED;
        crashTimer = Phys.REMOUNT_TIME;
        crashCount++;
        crashReason = reason;
        body.setLinearFactor(new Vector3(1, 1, 1));
        body.setAngularFactor(new Vector3(1, 1, 1));
        float th = heading();
        float fx = MathUtils.sin(th), fz = MathUtils.cos(th);
        float roll = (lean >= 0 ? -1 : 1) * (2.5f + MathUtils.random() * 2);
        Vector3 w = body.getAngularVelocity();
        float wy = w.y + (MathUtils.random() - 0.5f) * 3;
        body.setAngularVelocity(tmp.set(fx * roll, wy, fz * roll));
        Vector3 lv = body.getLinearVelocity();
        body.setLinearVelocity(tmp.set(lv.x, 1.2f + MathUtils.random(), lv.z));
        // Don't let the bike explode against its own rider while they separate.
        setMask(Phys.MASK_BIKE_NO_RAGDOLL);
        maskRestored = false;
        ragdoll = new Ragdoll(pw, this);
        events.add(new Event("crash", 0, null, reason));
    }

    /** Rejoin at the current track position. */
    public void remount() {
        if (ragdoll != null) { ragdoll.dispose(); ragdoll = null; }
        body.getWorldTransform(xf);
        Track.project(xf.val[Matrix4.M03], xf.val[Matrix4.M23], proj);
        float dd = MathUtils.clamp(proj.d, 1.2f, Track.WIDTH - 1.5f);
        float adj = Track.lapDelta(proj.s, lastS);
        placeAt(proj.s, dd, progress + adj);
        state = State.RIDING;
        if (!maskRestored) { setMask(Phys.MASK_BIKE); maskRestored = true; }
        lean = 0;
        events.add(new Event("remount", 0, null, null));
    }

    public void release() {
        if (state == State.HELD) state = State.RIDING;
    }

    /** Orientation helper for rendering. */
    public Quaternion rotation(Quaternion out) {
        body.getWorldTransform(xf);
        return xf.getRotation(out, true);
    }

    @Override
    public void dispose() {
        if (ragdoll != null) { ragdoll.dispose(); ragdoll = null; }
        pw.world.removeRigidBody(body);
        body.dispose();
        shape.dispose();
    }
}
