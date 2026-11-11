package com.broadside.speedway;

import com.badlogic.gdx.math.MathUtils;
import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.utils.Array;

/** AI rider: speedway racing line, broadside control, overtaking and avoidance. */
public class AIDriver {
    public static final class Persona {
        public float bendLine = 0.3f, straightLine = 0.45f, cornerGrip = 1f, reaction = 0.12f,
            wobble = 0.3f, broadside = 0.15f;
        public Persona() {}
        public Persona(float bendLine, float straightLine, float cornerGrip, float reaction, float wobble) {
            this.bendLine = bendLine; this.straightLine = straightLine; this.cornerGrip = cornerGrip;
            this.reaction = reaction; this.wobble = wobble;
        }
    }

    public final Bike bike;
    public final Persona p;
    private float noisePhase = MathUtils.random() * 100;
    private float passOffset;
    private float revTarget = 0.5f;
    /** Where to write decisions (normally the bike's own input). */
    public float outThrottle, outSteer;

    private final Track.Proj proj = new Track.Proj();
    private final Track.Proj oproj = new Track.Proj();
    private final Track.Point ahead = new Track.Point();
    private final Track.Point here = new Track.Point();
    private final Vector3 pos = new Vector3(), vel = new Vector3(), opos = new Vector3();

    public AIDriver(Bike bike, Persona p) {
        this.bike = bike;
        this.p = p;
    }

    float lineD(float s) {
        float k = Track.bendPhase(s);
        return (p.straightLine * (1 - k) + p.bendLine * k) * Track.WIDTH;
    }

    /** Decide controls; writes into outThrottle/outSteer and (if apply) into the bike. */
    public void update(float dt, Array<Bike> bikes, Race race, boolean apply) {
        decide(dt, bikes, race);
        if (apply) {
            bike.throttleIn = outThrottle;
            bike.steerIn = outSteer;
        }
    }

    private void decide(float dt, Array<Bike> bikes, Race race) {
        if (bike.state == Bike.State.HELD) {
            noisePhase += dt;
            if (MathUtils.random() < dt * 2) revTarget = 0.3f + MathUtils.random() * 0.6f;
            outThrottle = race.phase == Race.Phase.READY ? revTarget * 0.5f : 0;
            outSteer = 0;
            return;
        }
        if (bike.state != Bike.State.RIDING || race.time < p.reaction) {
            outThrottle = 0; outSteer = 0;
            return;
        }
        bike.position(pos);
        bike.velocity(vel);
        float x = pos.x, z = pos.z;
        float speed = (float) Math.hypot(vel.x, vel.z);
        Track.project(x, z, proj);
        noisePhase += dt;

        // Lateral target with overtaking / avoidance
        float targetD = lineD(proj.s + 10) + MathUtils.sin(noisePhase * 0.7f) * p.wobble;
        float desiredPass = 0;
        for (Bike o : bikes) {
            if (o == bike) continue;
            float rel = Track.lapDelta(o.lastS, bike.lastS);
            boolean fallen = o.state == Bike.State.CRASHED;
            float reach = fallen ? 22 : 9;
            if (rel > -1.5f && rel < reach) {
                float margin = fallen ? 3.4f : 2.3f;
                float od;
                if (fallen) { o.position(opos); od = Track.project(opos.x, opos.z, oproj).d; }
                else od = o.d;
                if (Math.abs(od - (targetD + passOffset)) < margin) {
                    float roomInside = od - 0.5f;
                    float roomOutside = Track.WIDTH - 0.8f - od;
                    float side = roomOutside > roomInside + 1 ? 1 : -1;
                    desiredPass = (od + side * margin) - targetD;
                }
            }
        }
        passOffset += (desiredPass - passOffset) * Math.min(1, dt * 1.6f);
        targetD = MathUtils.clamp(targetD + passOffset, 0.8f, Track.WIDTH - 1.1f);

        // Stanley-style steering on velocity direction, then hold heading inside by the broadside angle
        Track.point(proj.s + 2 + speed * 0.28f, targetD, ahead);
        float latErr = targetD - proj.d;
        float desiredVel = ahead.heading - (float) Math.atan2(1.1f * latErr, Math.max(speed, 6));
        float heading = bike.heading();
        float velAng = speed > 1.5f ? (float) Math.atan2(vel.x, vel.z) : heading;
        float velErr = Track.wrapAngle(desiredVel - velAng);
        float kBend = Track.bendPhase(proj.s + speed * 0.4f);
        float driftTarget = kBend * p.broadside * Math.min(1, speed / 18);
        float desiredHeading = desiredVel + driftTarget + velErr * 0.35f;
        float hErr = Track.wrapAngle(desiredHeading - heading);
        Track.point(proj.s, targetD, here);
        float ff = speed * here.curvature;
        float wy = bike.body.getAngularVelocity().y;
        float yawCmd = ff + 3.2f * hErr - 0.25f * (wy - ff);
        float authority = Phys.MAX_YAW_RATE * (0.55f + 0.45f * Math.min(1, speed / 6)) / (1 + speed / 60);
        outSteer = MathUtils.clamp(yawCmd / authority, -1, 1);
        float drift = bike.drift;

        // Throttle: corner speed from the curvature ahead
        float minR = Float.MAX_VALUE;
        for (int a = 0; a <= 30; a += 5) {
            float s2 = proj.s + a + speed * 0.3f;
            if (Track.bendPhase(s2) > 0.5f) minR = Math.min(minR, Track.INNER_RADIUS + lineD(s2));
        }
        float aLat = 14.5f * p.cornerGrip;
        float vmax = minR == Float.MAX_VALUE ? 99 : (float) Math.sqrt(aLat * minR);
        float throttle = 0.65f + (vmax - speed) * 0.25f;
        float over = Math.abs(drift) - (driftTarget + 0.35f);
        if (over > 0) throttle *= Math.max(0.3f, 1 - over * 2.5f);
        if (proj.d > Track.WIDTH - 1.3f && Track.bendPhase(proj.s) > 0.3f) throttle *= 0.7f;
        outThrottle = MathUtils.clamp(throttle, 0, 1);
    }
}
