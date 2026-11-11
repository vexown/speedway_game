package com.broadside.speedway.render;

import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.g2d.BitmapFont;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.badlogic.gdx.graphics.g3d.*;
import com.badlogic.gdx.graphics.g3d.attributes.ColorAttribute;
import com.badlogic.gdx.graphics.g3d.attributes.TextureAttribute;
import com.badlogic.gdx.graphics.g3d.model.Node;
import com.badlogic.gdx.graphics.g3d.utils.MeshPartBuilder;
import com.badlogic.gdx.graphics.g3d.utils.ModelBuilder;
import com.badlogic.gdx.graphics.glutils.FrameBuffer;
import com.badlogic.gdx.graphics.glutils.ShapeRenderer;
import com.badlogic.gdx.math.Matrix4;
import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.utils.Array;
import com.badlogic.gdx.utils.Disposable;
import com.broadside.speedway.Race;
import com.broadside.speedway.Track;

import java.util.Random;

import static com.broadside.speedway.render.MeshKit.*;

/** Stadium: track, air fence, start gate & tapes, stands with crowd, floodlights, scoreboard, infield. */
public class Stadium implements Disposable {
    public final Array<ModelInstance> instances = new Array<>();
    /** Instances that cast shadows (everything except ground/track surfaces & far stuff). */
    public final Array<ModelInstance> casters = new Array<>();
    public final Array<Vector3> lampPositions = new Array<>();
    private final Array<Model> models = new Array<>();
    private final Textures tex;

    private ModelInstance gate;
    private Node tapesNode;
    private Material gateLightMat;
    private FrameBuffer boardFbo;
    private String boardKey = "";
    public float crowdExcite = 0.08f;

    public Stadium(Textures tex) {
        this.tex = tex;
        buildGroundAndTrack();
        buildFence();
        buildGate();
        buildStands();
        buildFloodlights();
        buildScoreboard();
        buildInfield();
    }

    private ModelInstance add(Model m, boolean caster) {
        models.add(m);
        ModelInstance mi = new ModelInstance(m);
        instances.add(mi);
        if (caster) casters.add(mi);
        return mi;
    }

    private void buildGroundAndTrack() {
        ModelBuilder mb = new ModelBuilder();
        mb.begin();
        Texture grass = tex.grass();
        Material grassMat = texMat(grass, 0.95f);
        MeshPartBuilder b = mb.part("ground", GL20.GL_TRIANGLES, ATTR, grassMat);
        b.setUVRange(0, 0, 30, 30);
        b.rect(-350, 0, 350, 350, 0, 350, 350, 0, -350, -350, 0, -350, 0, 1, 0);

        Material shale = texMat(tex.shale(), 0.97f);
        b = mb.part("track", GL20.GL_TRIANGLES, ATTR, shale);
        ribbon(b, -0.3f, Track.WIDTH + 0.9f, 0.02f, 12, 8, 1f);
        b = mb.part("apron", GL20.GL_TRIANGLES, ATTR, mat(0x5a4a35, 0, 1, 0));
        ribbon(b, -2.2f, -0.3f, 0.015f, 12, 2, 1f);
        b = mb.part("whiteLine", GL20.GL_TRIANGLES, ATTR, mat(0xf4f4ee, 0, 0.8f, 0));
        ribbon(b, -0.55f, -0.25f, 0.035f, 12, 1, 1f);
        b = mb.part("kerb", GL20.GL_TRIANGLES, ATTR, mat(0xdedede));
        wall(b, -2.3f, 0, 0.18f, 6, false, 1f, false);
        b = mb.part("startLine", GL20.GL_TRIANGLES, ATTR, mat(0xffffff, 0, 0.7f, 0));
        at(b, tr(0, 0.04f, Track.INNER_RADIUS + Track.WIDTH / 2 + 0.2f));
        b.box(0.35f, 0.01f, Track.WIDTH + 0.8f);
        add(mb.end(), false);
    }

    private void buildFence() {
        ModelBuilder mb = new ModelBuilder();
        mb.begin();
        float d = Track.WIDTH + 0.45f;
        MeshPartBuilder b = mb.part("face", GL20.GL_TRIANGLES, ATTR, texMat(tex.fencePanels(), 0.6f));
        wall(b, d - 0.45f, 0, 1.4f, 32, true, 1f, true);
        b = mb.part("top", GL20.GL_TRIANGLES, ATTR, mat(0x1d3f8f, 0, 0.5f, 0.2f));
        ribbon(b, d - 0.5f, d + 0.45f, 1.4f, 6, 2, 1f);
        Material concrete = texMat(tex.concrete(), 0.95f);
        b = mb.part("wall", GL20.GL_TRIANGLES, ATTR, concrete);
        wall(b, d + 0.5f, 0, 1.6f, 8, false, 1f, true);
        b = mb.part("posts", GL20.GL_TRIANGLES, ATTR, mat(0x777777, 0.6f, 0.4f, 0));
        int n = (int) Math.ceil(Track.LENGTH / 6);
        Track.Point p = new Track.Point();
        for (int i = 0; i < n; i++) {
            Track.point(i / (float) n * Track.LENGTH, d + 0.55f, p);
            at(b, tr(p.x, 2.9f, p.z));
            b.cylinder(0.1f, 2.6f, 0.1f, 6);
        }
        at(b, new Matrix4());
        b = mb.part("net", GL20.GL_TRIANGLES, ATTR, twoSided(transparent(mat(0x333333), 0.18f)));
        wall(b, d + 0.55f, 1.6f, 4.2f, 8, false, 1f, true);
        add(mb.end(), true);
    }

    private void buildGate() {
        ModelBuilder mb = new ModelBuilder();
        mb.begin();
        float x = 0.05f;
        float zIn = Track.INNER_RADIUS - 0.9f, zOut = Track.INNER_RADIUS + Track.WIDTH + 0.2f;
        mb.node().id = "frame";
        MeshPartBuilder b = mb.part("posts", GL20.GL_TRIANGLES, ATTR, mat(0xe8e8e8, 0.3f, 0.5f, 0));
        for (float z : new float[]{zIn, zOut}) {
            at(b, tr(x, 1.6f, z)); b.box(0.2f, 3.2f, 0.2f);
        }
        at(b, tr(x, 3.2f, (zIn + zOut) / 2)); b.box(0.15f, 0.15f, zOut - zIn);
        b = mb.part("boxes", GL20.GL_TRIANGLES, ATTR, mat(0x222222));
        for (float z : new float[]{zIn, zOut}) { at(b, tr(x, 3.4f, z)); b.box(0.4f, 0.5f, 0.4f); }
        Node lights = mb.node();
        lights.id = "lights";
        gateLightMat = emissive(0x330000, 0);
        b = mb.part("lamps", GL20.GL_TRIANGLES, ATTR, gateLightMat);
        for (float z : new float[]{zIn, zOut}) { at(b, tr(x - 0.21f, 3.4f, z)); b.sphere(0.26f, 0.26f, 0.26f, 12, 8); }
        Node tapes = mb.node();
        tapes.id = "tapes";
        b = mb.part("tapes", GL20.GL_TRIANGLES, ATTR, emissive(0xffffff, 0.6f));
        for (float h : new float[]{0.55f, 0.85f, 1.15f}) { at(b, tr(x, h, (zIn + zOut) / 2)); b.box(0.03f, 0.05f, zOut - zIn); }
        gate = add(mb.end(), true);
        tapesNode = gate.getNode("tapes");
        gateLightMat = gate.getNode("lights").parts.first().material;
    }

    /** Gate lights & tapes follow the race phase. */
    public void updateGate(Race.Phase phase, float tapeHeight) {
        tapesNode.translation.set(0, tapeHeight * 2.4f, 0);
        tapesNode.parts.first().enabled = tapeHeight < 1;
        gate.calculateTransforms();
        ColorAttribute em = (ColorAttribute) gateLightMat.get(ColorAttribute.Emissive);
        ColorAttribute df = (ColorAttribute) gateLightMat.get(ColorAttribute.Diffuse);
        if (phase == Race.Phase.READY) { em.color.set(1, 0.05f, 0.05f, 0.6f); df.color.set(1, 0.2f, 0.2f, 1); }
        else if (phase == Race.Phase.RACING) { em.color.set(0.1f, 1, 0.25f, 0.6f); df.color.set(0.2f, 1, 0.3f, 1); }
        else { em.color.set(0, 0, 0, 0); df.color.set(0.2f, 0, 0, 1); }
    }

    private void buildStands() {
        Material concrete = texMat(tex.concrete(), 0.9f);
        Material roof = twoSided(mat(0x2c3e66, 0.3f, 0.6f, 0));
        int rows = 9, seatsPerRow = 110;
        float[][] stands = {{Track.INNER_RADIUS + Track.WIDTH + 4.5f, 1, 100}, {-(Track.INNER_RADIUS + Track.WIDTH + 4.5f), -1, 70}};
        ModelBuilder mb = new ModelBuilder();
        mb.begin();
        MeshPartBuilder steps = mb.part("steps", GL20.GL_TRIANGLES, ATTR, concrete);
        MeshPartBuilder roofs = mb.part("roofs", GL20.GL_TRIANGLES, ATTR, roof);
        for (float[] st : stands) {
            float z0 = st[0], dir = st[1], len = st[2];
            for (int r = 0; r < rows; r++) {
                float h = 0.8f + r * 0.8f;
                at(steps, tr(0, h / 2, z0 + dir * (r * 1.4f + 0.7f)));
                steps.box(len, h, 1.4f);
            }
            float depth = rows * 1.4f + 2, top = rows * 0.8f + 5.5f;
            at(roofs, tr(0, top, z0 + dir * depth / 2, dir * -4.6f, 0, 0));
            roofs.box(len + 2, 0.3f, depth);
            for (float cx : new float[]{-len / 2, -len / 4, 0, len / 4, len / 2}) {
                at(roofs, tr(cx, top / 2, z0 + dir * (depth - 0.5f)));
                roofs.cylinder(0.3f, top, 0.3f, 8);
            }
            at(steps, tr(0, top / 2, z0 + dir * depth));
            steps.box(len + 2, top, 0.3f);
        }
        // Grass banks on the bends
        MeshPartBuilder bank = mb.part("banks", GL20.GL_TRIANGLES, ATTR, twoSided(mat(0x4e8a35, 0, 0.95f, 0)));
        for (int sx : new int[]{-1, 1}) {
            Matrix4 m = tr(sx * Track.STRAIGHT / 2, 0, 0);
            if (sx < 0) m.rotate(Vector3.Y, 180);
            at(bank, m);
            coneBandY(bank, 84, 78, 4, 0, (float) Math.PI, 48);
        }
        add(mb.end(), true);

        // Crowd in chunks (each part must stay under the 16-bit index limit)
        Random rnd = new Random(5);
        int[] palette = {0xd62828, 0x1f6feb, 0xf2f2f2, 0xf5c518, 0x333333, 0x7a4b2a, 0x2a9d8f, 0xe76f51, 0x6d597a};
        int[] skin = {0xf1c27d, 0xe0ac69, 0xc68642, 0x8d5524, 0xffdbac, 0x5c3a21};
        int[] hats = {0xd62828, 0x1f6feb, 0xf5c518, 0xf2f2f2, 0x222222};
        Material crowdMat = mat(0xffffff, 0, 0.9f, 0);
        crowdMat.set(new Lighting.Crowd());
        ModelBuilder cb = null;
        MeshPartBuilder cp = null;
        int inChunk = 0, chunk = 0;
        Color c = new Color();
        for (float[] st : stands) {
            float z0 = st[0], dir = st[1], len = st[2];
            int n = Math.round(seatsPerRow * len / 100);
            for (int r = 0; r < rows; r++) for (int i = 0; i < n; i++) {
                if (rnd.nextFloat() < 0.12f) continue;
                if (cb == null || inChunk >= 220) {
                    if (cb != null) add(cb.end(), true);
                    cb = new ModelBuilder();
                    cb.begin();
                    cp = cb.part("crowd" + chunk++, GL20.GL_TRIANGLES, ATTR_COLOR, crowdMat);
                    inChunk = 0;
                }
                float x = -len / 2 + (i + 0.5f) * (len / n) + (rnd.nextFloat() - 0.5f) * 0.3f;
                float y = 0.8f + r * 0.8f + 0.47f;
                float z = z0 + dir * (r * 1.4f + 0.7f);
                float phase = rnd.nextFloat();
                Color.rgb888ToColor(c, palette[rnd.nextInt(palette.length)]);
                c.mul(0.7f + rnd.nextFloat() * 0.4f);
                c.a = phase;
                cp.setColor(c);
                Matrix4 m = tr(x, y, z);
                if (dir < 0) m.rotate(Vector3.Y, 180);
                at(cp, new Matrix4(m).scale(1.15f, 1, 0.8f));
                cp.capsule(0.17f, 0.66f, 6);
                at(cp, new Matrix4(m).translate(0, 0.2f, 0).rotate(Vector3.Z, 90));
                cp.capsule(0.07f, 0.5f, 5);
                Color.rgb888ToColor(c, rnd.nextFloat() < 0.18f ? hats[rnd.nextInt(hats.length)] : skin[rnd.nextInt(skin.length)]);
                c.a = phase;
                cp.setColor(c);
                at(cp, new Matrix4(m).translate(0, 0.5f, 0));
                cp.sphere(0.23f, 0.25f, 0.23f, 8, 6);
                inChunk++;
            }
        }
        if (cb != null) add(cb.end(), true);
    }

    private void buildFloodlights() {
        ModelBuilder mb = new ModelBuilder();
        mb.begin();
        MeshPartBuilder poles = mb.part("poles", GL20.GL_TRIANGLES, ATTR, mat(0x9aa0a6, 0.7f, 0.35f, 0));
        MeshPartBuilder lamps = mb.part("lamps", GL20.GL_TRIANGLES, ATTR, emissive(0xfff4d6, 3f));
        Track.Point p = new Track.Point();
        for (float s : new float[]{0, 0.17f, 0.33f, 0.5f, 0.67f, 0.83f}) {
            Track.point(s * Track.LENGTH + 20, Track.WIDTH + 12, p);
            at(poles, tr(p.x, 13, p.z));
            poles.cylinder(0.65f, 26, 0.65f, 10);
            float yaw = (float) Math.toDegrees(Math.atan2(-p.x, -p.z));
            Matrix4 head = tr(p.x, 26.5f, p.z).rotate(Vector3.Y, yaw).rotate(Vector3.X, 20);
            at(poles, head);
            poles.box(4, 2.2f, 0.4f);
            for (int i = 0; i < 3; i++) for (int j = 0; j < 2; j++) {
                at(lamps, new Matrix4(head).translate(-1.3f + i * 1.3f, -0.5f + j * 1.0f, 0.25f));
                lamps.box(1.0f, 0.8f, 0.1f);
            }
            lampPositions.add(new Vector3(0, 0, 0.8f).mul(head));
        }
        add(mb.end(), true);
    }

    private ModelInstance board;

    private void buildScoreboard() {
        boardFbo = new FrameBuffer(Pixmap.Format.RGBA8888, 512, 256, false);
        Texture t = boardFbo.getColorBufferTexture();
        t.setFilter(Texture.TextureFilter.Linear, Texture.TextureFilter.Linear);
        ModelBuilder mb = new ModelBuilder();
        mb.begin();
        Material screen = new Material(TextureAttribute.createDiffuse(t), ColorAttribute.createEmissive(1, 1, 1, 0.12f),
            new Lighting.Pbr(0, 0.4f, 0));
        MeshPartBuilder b = mb.part("screen", GL20.GL_TRIANGLES, ATTR, screen);
        b.setUVRange(0, 0, 1, 1);
        // front face towards +z (home straight); u left->right, v top->bottom
        b.rect(-7, 4, -7.99f, 7, 4, -7.99f, 7, 11, -7.99f, -7, 11, -7.99f, 0, 0, 1);
        b = mb.part("frame", GL20.GL_TRIANGLES, ATTR, mat(0x1a1a1a));
        at(b, tr(0, 7.5f, -8.3f)); b.box(14.6f, 7.6f, 0.5f);
        at(b, tr(-5, 2.1f, -8.3f)); b.box(0.4f, 4.5f, 0.4f);
        at(b, tr(5, 2.1f, -8.3f)); b.box(0.4f, 4.5f, 0.4f);
        board = add(mb.end(), true);
        drawBoard("BROADSIDE SPEEDWAY", new String[0], new int[0]);
    }

    /** Update the infield scoreboard (only re-renders when the content changes). */
    public void drawBoard(String title, String[] rows, int[] colors) {
        StringBuilder key = new StringBuilder(title);
        for (String r : rows) key.append('|').append(r);
        if (key.toString().equals(boardKey)) return;
        boardKey = key.toString();
        tex.paintInto(boardFbo, (ShapeRenderer sh, SpriteBatch b, BitmapFont f) -> {
            sh.begin(ShapeRenderer.ShapeType.Filled);
            sh.setColor(0.02f, 0.03f, 0.05f, 1);
            sh.rect(0, 0, 512, 256);
            for (int i = 0; i < rows.length; i++) {
                sh.setColor(Textures.hex(colors[i]));
                sh.rect(28, 66 + i * 46, 26, 26);
            }
            sh.end();
            b.begin();
            Textures.text(b, f, title, 256, 30, 26, Textures.hex(0xffcc33));
            for (int i = 0; i < rows.length; i++) {
                Textures.text(b, f, rows[i], 250, 79 + i * 46, 24, Textures.hex(0xf0f0f0));
            }
            b.end();
        });
    }

    private void buildInfield() {
        ModelBuilder mb = new ModelBuilder();
        mb.begin();
        MeshPartBuilder b = mb.part("shed", GL20.GL_TRIANGLES, ATTR, mat(0x8b3a2b));
        at(b, tr(-24, 1.6f, 6)); b.box(8, 3.2f, 5);
        b = mb.part("shedRoof", GL20.GL_TRIANGLES, ATTR, mat(0x333333));
        at(b, tr(-24, 3.35f, 6)); b.box(8.6f, 0.3f, 5.6f);
        // flower bed
        Random r = new Random(9);
        b = mb.part("flowers", GL20.GL_TRIANGLES, ATTR_COLOR, mat(0xffffff));
        Color c = new Color();
        for (int i = 0; i < 160; i++) {
            float a = (float) (i / 160.0 * Math.PI * 2), rad = 5 + r.nextFloat() * 1.2f;
            c.fromHsv(r.nextFloat() * 360, 0.8f, 0.9f);
            b.setColor(c);
            at(b, tr(18 + (float) Math.cos(a) * rad, 0.18f, (float) Math.sin(a) * rad * 0.6f));
            b.sphere(0.36f, 0.36f, 0.36f, 6, 4);
        }
        // tractor
        Matrix4 base = tr(-28, 0, -10).rotate(Vector3.Y, 34);
        b = mb.part("tractorBody", GL20.GL_TRIANGLES, ATTR, mat(0x2e7d32, 0.1f, 0.5f, 0.4f));
        at(b, new Matrix4(base).translate(0, 1.1f, 0)); b.box(1.6f, 1.2f, 2.6f);
        b = mb.part("tractorCab", GL20.GL_TRIANGLES, ATTR, transparent(mat(0xcfd8dc, 0.2f, 0.1f, 0), 0.6f));
        at(b, new Matrix4(base).translate(0, 2.2f, -0.5f)); b.box(1.4f, 1.2f, 1.2f);
        b = mb.part("tractorWheels", GL20.GL_TRIANGLES, ATTR, mat(0x111111, 0, 0.9f, 0));
        float[][] wheels = {{0.9f, -0.8f, 0.75f}, {-0.9f, -0.8f, 0.75f}, {0.8f, 1.0f, 0.45f}, {-0.8f, 1.0f, 0.45f}};
        for (float[] w : wheels) {
            at(b, new Matrix4(base).translate(w[0], w[2], w[1]).rotate(Vector3.Z, 90));
            b.cylinder(w[2] * 2, 0.4f, w[2] * 2, 16);
        }
        add(mb.end(), true);
    }

    @Override
    public void dispose() {
        for (Model m : models) m.dispose();
        if (boardFbo != null) boardFbo.dispose();
    }
}
