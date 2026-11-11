package com.broadside.speedway.render;

import com.badlogic.gdx.graphics.Camera;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.g2d.TextureRegion;
import com.badlogic.gdx.graphics.g3d.decals.CameraGroupStrategy;
import com.badlogic.gdx.graphics.g3d.decals.Decal;
import com.badlogic.gdx.graphics.g3d.decals.DecalBatch;
import com.badlogic.gdx.math.MathUtils;
import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.utils.Array;
import com.badlogic.gdx.utils.Disposable;
import com.broadside.speedway.Bike;

/** Shale roost from the rear wheel, dust clouds, impact bursts and floodlight halos (camera-facing decals). */
public class Effects implements Disposable {
    private static final class Particle {
        final Decal decal;
        final Vector3 vel = new Vector3();
        float life, maxLife, size, growth, gravity, drag, baseAlpha, r, g, b;
        Particle(TextureRegion region) { decal = Decal.newDecal(1, 1, region, GL20.GL_SRC_ALPHA, GL20.GL_ONE_MINUS_SRC_ALPHA); }
    }

    private final Particle[] roost = new Particle[2400];
    private final Particle[] dust = new Particle[700];
    private int roostCursor, dustCursor;
    private final DecalBatch batch;
    private final Array<Decal> halos = new Array<>();
    private final float[] acc = new float[8];
    private final Vector3 tmp = new Vector3(), vel = new Vector3();

    public Effects(Texture dot, Camera cam, Array<Vector3> lampPositions) {
        TextureRegion region = new TextureRegion(dot);
        batch = new DecalBatch(4000, new CameraGroupStrategy(cam));
        for (int i = 0; i < roost.length; i++) roost[i] = new Particle(region);
        for (int i = 0; i < dust.length; i++) dust[i] = new Particle(region);
        for (Vector3 p : lampPositions) {
            Decal d = Decal.newDecal(11, 11, region, GL20.GL_SRC_ALPHA, GL20.GL_ONE);
            d.setPosition(p);
            d.setColor(1f, 0.95f, 0.82f, 0.55f);
            halos.add(d);
        }
    }

    private void emit(Particle[] pool, boolean isDust, float x, float y, float z, float vx, float vy, float vz,
                      float life, float size, float growth, float r, float g, float b) {
        int i;
        if (isDust) { i = dustCursor; dustCursor = (dustCursor + 1) % pool.length; }
        else { i = roostCursor; roostCursor = (roostCursor + 1) % pool.length; }
        Particle p = pool[i];
        p.decal.setPosition(x, y, z);
        p.vel.set(vx, vy, vz);
        p.life = p.maxLife = life;
        p.size = size; p.growth = growth;
        p.gravity = isDust ? 0.25f : -11f;
        p.drag = isDust ? 1.2f : 0.6f;
        p.baseAlpha = isDust ? 0.2f : 0.95f;
        p.r = r; p.g = g; p.b = b;
    }

    /** Spray shale behind a bike based on throttle, slide and speed. */
    public void bike(BikeView view, float dt) {
        Bike bk = view.bike;
        if (bk.state != Bike.State.RIDING) return;
        float intensity = bk.throttleIn * (0.25f + bk.slide) * Math.min(1, bk.speed / 6 + 0.2f);
        if (intensity < 0.05f) return;
        float a = acc[bk.id] + intensity * dt * 240;
        view.rearContact(tmp);
        float th = bk.heading();
        float fx = MathUtils.sin(th), fz = MathUtils.cos(th);
        float lx = MathUtils.cos(th), lz = -MathUtils.sin(th);
        bk.velocity(vel);
        while (a >= 1) {
            a -= 1;
            float back = 4 + MathUtils.random() * 9 * intensity;
            float side = -(1 + MathUtils.random() * 5) * Math.signum(bk.lean == 0 ? 1 : bk.lean) * Math.min(1, Math.abs(bk.lean) * 2 + 0.2f);
            emit(roost, false, tmp.x + (MathUtils.random() - 0.5f) * 0.2f, tmp.y + 0.1f, tmp.z + (MathUtils.random() - 0.5f) * 0.2f,
                vel.x * 0.3f - fx * back + lx * side + (MathUtils.random() - 0.5f), 1.2f + MathUtils.random() * 3.5f * intensity,
                vel.z * 0.3f - fz * back + lz * side + (MathUtils.random() - 0.5f),
                0.5f + MathUtils.random() * 0.6f, 0.05f + MathUtils.random() * 0.07f, 0,
                0.36f + MathUtils.random() * 0.15f, 0.21f + MathUtils.random() * 0.08f, 0.12f + MathUtils.random() * 0.05f);
            if (MathUtils.random() < 0.18f) {
                emit(dust, true, tmp.x, tmp.y + 0.3f, tmp.z,
                    vel.x * 0.2f - fx * 2 + (MathUtils.random() - 0.5f) * 2, 0.4f + MathUtils.random() * 0.6f,
                    vel.z * 0.2f - fz * 2 + (MathUtils.random() - 0.5f) * 2,
                    2.2f + MathUtils.random() * 1.8f, 0.6f + MathUtils.random() * 0.5f, 1.2f, 0.46f, 0.35f, 0.27f);
            }
        }
        acc[bk.id] = a;
    }

    public void burst(float x, float y, float z, float strength) {
        int n = (int) (30 * strength);
        for (int i = 0; i < n; i++) {
            float ang = MathUtils.random() * MathUtils.PI2, s = 2 + MathUtils.random() * 5 * strength;
            emit(roost, false, x, y + 0.2f, z, MathUtils.cos(ang) * s, 2 + MathUtils.random() * 4, MathUtils.sin(ang) * s,
                0.8f, 0.07f, 0, 0.4f, 0.25f, 0.14f);
        }
        for (int i = 0; i < n / 5; i++)
            emit(dust, true, x, y + 0.4f, z, (MathUtils.random() - 0.5f) * 3, 0.5f + MathUtils.random(), (MathUtils.random() - 0.5f) * 3,
                2.5f, 0.8f, 1.0f, 0.46f, 0.35f, 0.27f);
    }

    private void step(Particle[] pool, float dt, Camera cam, boolean render) {
        for (Particle p : pool) {
            if (p.life <= 0) continue;
            p.life -= dt;
            float damp = (float) Math.exp(-p.drag * dt);
            p.vel.x *= damp; p.vel.z *= damp;
            p.vel.y = p.vel.y * damp + p.gravity * dt;
            Vector3 pos = p.decal.getPosition();
            float nx = pos.x + p.vel.x * dt, ny = pos.y + p.vel.y * dt, nz = pos.z + p.vel.z * dt;
            if (ny < 0.03f) { ny = 0.03f; p.vel.y *= -0.2f; p.vel.x *= 0.5f; p.vel.z *= 0.5f; }
            p.decal.setPosition(nx, ny, nz);
            p.size += p.growth * dt;
            float t = p.life / p.maxLife;
            float alpha = p.baseAlpha * Math.min(1, t * 3) * Math.min(1, (1 - t) * 8 + 0.2f);
            if (!render) continue;
            // Fade out particles close to the camera so dust never blankets the view.
            float camDist = cam.position.dst(nx, ny, nz);
            alpha *= MathUtils.clamp((camDist - 1.5f) / 7f, 0, 1);
            if (alpha < 0.01f) continue;
            float size = Math.min(p.size, 3.5f);
            p.decal.setDimensions(size * 2, size * 2);
            p.decal.setColor(p.r, p.g, p.b, alpha);
            p.decal.lookAt(cam.position, cam.up);
            batch.add(p.decal);
        }
    }

    public void update(float dt, Camera cam) {
        step(roost, dt, cam, false);
        step(dust, dt, cam, false);
    }

    public void render(Camera cam) {
        step(roost, 0, cam, true);
        step(dust, 0, cam, true);
        for (Decal h : halos) { h.lookAt(cam.position, cam.up); batch.add(h); }
        batch.flush();
    }

    @Override
    public void dispose() { batch.dispose(); }
}
