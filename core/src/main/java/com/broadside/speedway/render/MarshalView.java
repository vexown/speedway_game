package com.broadside.speedway.render;

import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.Mesh;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.VertexAttribute;
import com.badlogic.gdx.graphics.VertexAttributes;
import com.badlogic.gdx.graphics.g3d.Material;
import com.badlogic.gdx.graphics.g3d.Model;
import com.badlogic.gdx.graphics.g3d.ModelBatch;
import com.badlogic.gdx.graphics.g3d.ModelInstance;
import com.badlogic.gdx.graphics.g3d.attributes.TextureAttribute;
import com.badlogic.gdx.graphics.g3d.utils.MeshPartBuilder;
import com.badlogic.gdx.graphics.g3d.utils.ModelBuilder;
import com.badlogic.gdx.math.MathUtils;
import com.badlogic.gdx.math.Matrix4;
import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.utils.Array;
import com.badlogic.gdx.utils.Disposable;
import com.broadside.speedway.Bike;
import com.broadside.speedway.Race;
import com.broadside.speedway.Track;

import static com.broadside.speedway.render.MeshKit.*;

/**
 * Start marshal: checks the riders at the tapes, raises his arm for "riders ready", snaps it
 * down as the tapes go up, then waves flags (yellow for falls, chequered at the finish).
 */
public class MarshalView implements Disposable {
    private final Figure figure;
    private final Figure.Pose p = new Figure.Pose();
    private final Model flagModel;
    private final ModelInstance flag;
    private final Mesh cloth;
    private final float[] clothBase, clothVerts;
    private final Material clothMat;
    private final Texture texYellow, texCheq;
    private final Vector3 position = new Vector3();
    private final Vector3 spotCheck = new Vector3(2.4f, 0, Track.INNER_RADIUS + 2.6f);
    private final Vector3 spotPost = new Vector3(1.4f, 0, Track.INNER_RADIUS - 3.0f);
    private float yaw, walkPhase, t, flagUntil = -1, leverT = -1;
    private String flagKind = "yellow";
    private Race.Phase prevPhase;
    private boolean flagVisible;
    private static final int CW = 12, CH = 6;

    public MarshalView(Textures tex) {
        Material trousers = texMat(tex.suit(0x20232b, 0xd9dde2), 0.8f);
        Material vest = texMat(tex.hiVis(), 0.6f);
        figure = new Figure(trousers, vest, -1, 0x1d3f8f, false, mat(0xf0f0f0, 0, 0.6f, 0));
        texYellow = tex.flag("yellow");
        texCheq = tex.flag("chequered");

        // Flag cloth: a dynamic grid mesh (position, normal, uv) animated every frame.
        int nv = (CW + 1) * (CH + 1);
        clothBase = new float[nv * 8];
        int k = 0;
        for (int j = 0; j <= CH; j++) for (int i = 0; i <= CW; i++) {
            clothBase[k++] = 0.62f * i / CW; clothBase[k++] = 0.46f * (0.5f - j / (float) CH); clothBase[k++] = 0;
            clothBase[k++] = 0; clothBase[k++] = 0; clothBase[k++] = 1;
            clothBase[k++] = i / (float) CW; clothBase[k++] = j / (float) CH;
        }
        clothVerts = clothBase.clone();
        short[] idx = new short[CW * CH * 6];
        k = 0;
        for (int j = 0; j < CH; j++) for (int i = 0; i < CW; i++) {
            short a = (short) (j * (CW + 1) + i), b = (short) (a + CW + 1);
            idx[k++] = a; idx[k++] = b; idx[k++] = (short) (a + 1);
            idx[k++] = (short) (a + 1); idx[k++] = b; idx[k++] = (short) (b + 1);
        }
        cloth = new Mesh(false, nv, idx.length,
            new VertexAttribute(VertexAttributes.Usage.Position, 3, "a_position"),
            new VertexAttribute(VertexAttributes.Usage.Normal, 3, "a_normal"),
            new VertexAttribute(VertexAttributes.Usage.TextureCoordinates, 2, "a_texCoord0"));
        cloth.setVertices(clothVerts);
        cloth.setIndices(idx);
        clothMat = twoSided(texMat(texYellow, 0.8f));
        ModelBuilder mb = new ModelBuilder();
        mb.begin();
        mb.node().id = "stick";
        MeshPartBuilder b = mb.part("stick", GL20.GL_TRIANGLES, ATTR, mat(0x6b4a2b));
        at(b, tr(0, 0.3f, 0));
        b.cylinder(0.024f, 0.75f, 0.024f, 6);
        mb.node().id = "cloth";
        mb.part("cloth", cloth, GL20.GL_TRIANGLES, 0, idx.length, clothMat);
        flagModel = mb.end();
        flag = new ModelInstance(flagModel);
        flag.getNode("cloth").translation.set(0, 0.43f, 0);
        flag.calculateTransforms();
        position.set(spotPost);
    }

    public void waveFlag(String kind, float seconds) {
        flagKind = kind;
        flagUntil = t + seconds;
    }

    private void setFlag(String kind) {
        TextureAttribute ta = (TextureAttribute) clothMat.get(TextureAttribute.Diffuse);
        ta.textureDescription.texture = kind.equals("chequered") ? texCheq : texYellow;
    }

    public void update(float dt, Race race) {
        t += dt;
        Race.Phase phase = race == null ? null : race.phase;
        if (phase != prevPhase) {
            if (phase == Race.Phase.RACING) leverT = 0;
            prevPhase = phase;
        }
        Vector3 target = spotPost;
        float faceYaw = 0;
        if (phase == Race.Phase.INTRO) { target = spotCheck; faceYaw = -MathUtils.HALF_PI; }
        else if (phase == Race.Phase.READY) faceYaw = -MathUtils.HALF_PI + 0.6f;
        else if (race != null) {
            Bike leader = race.standings().first();
            Vector3 lp = leader.position(new Vector3());
            faceYaw = MathUtils.clamp((float) Math.atan2(lp.x - position.x, lp.z - position.z), -2.2f, 2.2f);
        }
        Vector3 to = new Vector3(target).sub(position);
        float dist = to.len();
        boolean moving = false;
        if (dist > 0.05f) {
            float step = Math.min(dist, dt * (phase == Race.Phase.INTRO ? 2.2f : 4.2f));
            position.mulAdd(to.nor(), step);
            moving = true;
            faceYaw = (float) Math.atan2(to.x, to.z);
        }
        float dy = (float) Math.atan2(Math.sin(faceYaw - yaw), Math.cos(faceYaw - yaw));
        yaw += dy * Math.min(1, dt * 6);

        if (moving) walkPhase += dt * 9;
        float sw = moving ? MathUtils.sin(walkPhase) : 0;
        float bob = moving ? Math.abs(MathUtils.cos(walkPhase)) * 0.03f : MathUtils.sin(t * 1.5f) * 0.005f;
        p.pelvis.set(0, 0.97f + bob, 0);
        p.chest.set(0, 1.45f + bob, 0.03f);
        p.head.set(0, 1.68f + bob, 0.05f);
        p.fwd.set(0, 0, 1);
        p.headFwd.set(0, 0, 1);
        p.footL.set(0.11f, 0.05f + Math.max(0, sw) * 0.08f, sw * 0.28f);
        p.footR.set(-0.11f, 0.05f + Math.max(0, -sw) * 0.08f, -sw * 0.28f);
        p.kneePoleL.set(0.1f, 0.5f, 2);
        p.kneePoleR.set(-0.1f, 0.5f, 2);
        p.handL.set(0.25f, 0.86f, 0.04f - sw * 0.2f);
        p.handR.set(-0.25f, 0.86f, 0.04f + sw * 0.2f);
        p.elbowPoleL.set(0.6f, 1.0f, -1);
        p.elbowPoleR.set(-0.6f, 1.0f, -1);

        flagVisible = false;
        boolean wave = false;
        if (phase == Race.Phase.INTRO && !moving) {
            p.handL.set(0.28f, 1.35f, 0.42f); p.handR.set(-0.28f, 1.35f, 0.42f);
            p.elbowPoleL.set(1, 0.6f, 0); p.elbowPoleR.set(-1, 0.6f, 0);
        } else if (phase == Race.Phase.READY && !moving) {
            p.handR.set(-0.18f, 2.05f, 0.12f); p.elbowPoleR.set(-1, 1.5f, 0);
            p.handL.set(0.22f, 1.0f, 0.18f);
        } else if (leverT >= 0 && leverT < 0.9f) {
            leverT += dt;
            float k = Math.min(1, leverT / 0.18f);
            p.handR.set(-0.22f, 2.05f - 1.05f * k, 0.12f + 0.25f * k);
            p.elbowPoleR.set(-1, 1.0f, 0.5f);
            p.chest.z += 0.06f * k;
            p.head.z += 0.08f * k;
        }
        if (leverT >= 0.9f) leverT = -1;

        if (race != null && t < flagUntil) {
            flagVisible = true;
            wave = true;
            p.handR.set(-0.3f + MathUtils.sin(t * 7) * 0.32f, 1.95f, 0.25f);
            p.elbowPoleR.set(-1, 1.2f, 0);
            setFlag(flagKind);
        } else if (race != null && phase == Race.Phase.RACING) {
            Bike leader = race.standings().first();
            if (leader.lap >= race.laps && !leader.finished) {
                flagVisible = true;
                p.handR.set(-0.3f, 1.25f, 0.35f);
                p.elbowPoleR.set(-1, 0.5f, 0);
                setFlag("chequered");
            }
        }
        figure.pose(p);
        figure.instance.transform.setToTranslation(position).rotateRad(Vector3.Y, yaw);

        if (flagVisible) {
            Vector3 hand = new Vector3(figure.handRPos).mul(figure.instance.transform);
            flag.transform.setToTranslation(hand).rotateRad(Vector3.Y, yaw)
                .rotateRad(Vector3.Z, wave ? MathUtils.sin(t * 7) * 0.6f : 0.2f);
            float speed = wave ? 16 : 6;
            for (int i = 0; i < clothVerts.length; i += 8) {
                float x = clothBase[i];
                clothVerts[i + 2] = MathUtils.sin(x * 9 - t * speed) * 0.05f * x * 2;
            }
            cloth.setVertices(clothVerts);
        }
    }

    public void render(ModelBatch batch) {
        batch.render(figure.instance);
        if (flagVisible) batch.render(flag);
    }

    @Override
    public void dispose() {
        figure.dispose();
        flagModel.dispose(); // also disposes the cloth mesh (owned by the model)
    }
}
