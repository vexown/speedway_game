package com.broadside.speedway.render;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.*;
import com.badlogic.gdx.graphics.g2d.BitmapFont;
import com.badlogic.gdx.graphics.g2d.GlyphLayout;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.badlogic.gdx.graphics.g2d.freetype.FreeTypeFontGenerator;
import com.badlogic.gdx.graphics.glutils.FrameBuffer;
import com.badlogic.gdx.graphics.glutils.ShapeRenderer;
import com.badlogic.gdx.math.Matrix4;
import com.badlogic.gdx.utils.Align;
import com.badlogic.gdx.utils.Array;
import com.badlogic.gdx.utils.Disposable;

import java.util.Random;

/** Procedural textures (no image assets) and text rendered into textures via FreeType. */
public class Textures implements Disposable {
    private final Array<Disposable> owned = new Array<>();
    private final BitmapFont texFont;     // y-down (flipped) font for drawing into texture FBOs
    private final SpriteBatch batch = new SpriteBatch();
    private final ShapeRenderer shapes = new ShapeRenderer();

    public Textures() {
        FreeTypeFontGenerator gen = new FreeTypeFontGenerator(Gdx.files.internal("fonts/DejaVuSans-Bold.ttf"));
        FreeTypeFontGenerator.FreeTypeFontParameter p = new FreeTypeFontGenerator.FreeTypeFontParameter();
        p.size = 72;
        p.flip = true;
        p.minFilter = Texture.TextureFilter.Linear;
        p.magFilter = Texture.TextureFilter.Linear;
        texFont = gen.generateFont(p);
        gen.dispose();
        owned.add(texFont);
        owned.add(batch);
        owned.add(shapes);
    }

    private Texture own(Texture t) { owned.add(t); return t; }

    private static int rgba(float r, float g, float b, float a) {
        return ((int) (Math.min(1, Math.max(0, r)) * 255) << 24) | ((int) (Math.min(1, Math.max(0, g)) * 255) << 16)
            | ((int) (Math.min(1, Math.max(0, b)) * 255) << 8) | (int) (Math.min(1, Math.max(0, a)) * 255);
    }

    private Texture finish(Pixmap px, boolean repeatU, boolean repeatV) {
        Texture t = new Texture(px, true);
        px.dispose();
        t.setFilter(Texture.TextureFilter.MipMapLinearLinear, Texture.TextureFilter.Linear);
        t.setWrap(repeatU ? Texture.TextureWrap.Repeat : Texture.TextureWrap.ClampToEdge,
            repeatV ? Texture.TextureWrap.Repeat : Texture.TextureWrap.ClampToEdge);
        t.setAnisotropicFilter(8f);
        return own(t);
    }

    private static void hsl(float h, float s, float l, float[] out) {
        h = ((h % 360) + 360) % 360 / 360f;
        float q = l < 0.5f ? l * (1 + s) : l + s - l * s, p = 2 * l - q;
        out[0] = hue(p, q, h + 1 / 3f); out[1] = hue(p, q, h); out[2] = hue(p, q, h - 1 / 3f);
    }

    private static float hue(float p, float q, float t) {
        if (t < 0) t += 1; if (t > 1) t -= 1;
        if (t < 1 / 6f) return p + (q - p) * 6 * t;
        if (t < 1 / 2f) return q;
        if (t < 2 / 3f) return p + (q - p) * (2 / 3f - t) * 6;
        return p;
    }

    /** Shale track surface: x along the track (repeats), y across (white line -> fence). */
    public Texture shale() {
        int W = 512, H = 512;
        Pixmap px = new Pixmap(W, H, Pixmap.Format.RGBA8888);
        Random r = new Random(7);
        float[][] stops = {{0f, 0x6e, 0x41, 0x27}, {0.12f, 0x5e, 0x35, 0x20}, {0.35f, 0x6a, 0x3d, 0x24},
            {0.6f, 0x86, 0x52, 0x2f}, {0.85f, 0x9a, 0x62, 0x38}, {1f, 0xa4, 0x6a, 0x3e}};
        for (int y = 0; y < H; y++) {
            float t = y / (float) H;
            int k = 0;
            while (k < stops.length - 2 && t > stops[k + 1][0]) k++;
            float f = (t - stops[k][0]) / (stops[k + 1][0] - stops[k][0]);
            float cr = (stops[k][1] + (stops[k + 1][1] - stops[k][1]) * f) / 255f;
            float cg = (stops[k][2] + (stops[k + 1][2] - stops[k][2]) * f) / 255f;
            float cb = (stops[k][3] + (stops[k + 1][3] - stops[k][3]) * f) / 255f;
            px.setColor(cr, cg, cb, 1);
            px.drawLine(0, y, W - 1, y);
        }
        px.setBlending(Pixmap.Blending.SourceOver);
        float[] c = new float[3];
        for (int i = 0; i < 26000; i++) {
            int x = r.nextInt(W), y = r.nextInt(H);
            float loose = y / (float) H;
            hsl(18 + r.nextFloat() * 12, 0.4f + r.nextFloat() * 0.25f, (25 + r.nextFloat() * 35 * (0.5f + loose)) / 100f, c);
            px.setColor(c[0], c[1], c[2], 0.25f + r.nextFloat() * 0.5f);
            int s = (int) (1 + r.nextFloat() * (1 + loose * 2.2f));
            px.fillRectangle(x, y, s, s);
        }
        for (int i = 0; i < 260; i++) {
            int y = (int) (H * (0.05f + Math.pow(r.nextFloat(), 1.6) * 0.75f));
            int x = r.nextInt(W);
            int len = 40 + r.nextInt(200);
            px.setColor((40 + r.nextFloat() * 30) / 255f, (20 + r.nextFloat() * 15) / 255f, 10 / 255f, 0.08f + r.nextFloat() * 0.12f);
            int th = 1 + r.nextInt(3);
            for (int k = 0; k < th; k++) {
                px.drawLine(x, y + k, x + len, y + k + r.nextInt(5) - 2);
                if (x + len > W) px.drawLine(x - W, y + k, x - W + len, y + k);
            }
        }
        return finish(px, true, false);
    }

    public Texture grass() {
        int W = 512, H = 512;
        Pixmap px = new Pixmap(W, H, Pixmap.Format.RGBA8888);
        Random r = new Random(11);
        for (int i = 0; i < 8; i++) {
            px.setColor(i % 2 == 1 ? new Color(0x3f7a2cff) : new Color(0x478a31ff));
            px.fillRectangle(0, i * H / 8, W, H / 8);
        }
        px.setBlending(Pixmap.Blending.SourceOver);
        float[] c = new float[3];
        for (int i = 0; i < 30000; i++) {
            hsl(95 + r.nextFloat() * 20, 0.4f + r.nextFloat() * 0.2f, (20 + r.nextFloat() * 25) / 100f, c);
            px.setColor(c[0], c[1], c[2], 0.35f);
            px.fillRectangle(r.nextInt(W), r.nextInt(H), 1, 1 + r.nextInt(2));
        }
        return finish(px, true, true);
    }

    public Texture concrete() {
        Pixmap px = new Pixmap(256, 256, Pixmap.Format.RGBA8888);
        Random r = new Random(3);
        px.setColor(new Color(0x8d8d88ff));
        px.fill();
        px.setBlending(Pixmap.Blending.SourceOver);
        for (int i = 0; i < 9000; i++) {
            px.setColor(r.nextFloat() * 0.24f, r.nextFloat() * 0.24f, r.nextFloat() * 0.24f, 0.12f);
            px.fillRectangle(r.nextInt(256), r.nextInt(256), 2, 2);
        }
        return finish(px, true, true);
    }

    /** Soft round sprite for particles and light halos. */
    public Texture softDot() {
        Pixmap px = new Pixmap(64, 64, Pixmap.Format.RGBA8888);
        px.setBlending(Pixmap.Blending.None);
        for (int y = 0; y < 64; y++) for (int x = 0; x < 64; x++) {
            float d = (float) Math.hypot(x - 31.5, y - 31.5) / 32f;
            float a = d >= 1 ? 0 : d < 0.4f ? 1 - d * 0.75f : 0.7f * (1 - (d - 0.4f) / 0.6f);
            px.drawPixel(x, y, rgba(1, 1, 1, a));
        }
        Texture t = new Texture(px, true);
        px.dispose();
        t.setFilter(Texture.TextureFilter.MipMapLinearLinear, Texture.TextureFilter.Linear);
        return own(t);
    }

    // ------------------------------------------------------------------ text textures (FBO)

    /** Draw callback in a y-down pixel space of size w×h. */
    public interface Painter {
        void paint(ShapeRenderer shapes, SpriteBatch batch, BitmapFont font);
    }

    /** Renders shapes + text into a new texture. The result uses pixmap-style UVs (v=0 is the top). */
    public Texture paint(int w, int h, Painter painter, boolean repeatU) {
        FrameBuffer fbo = new FrameBuffer(Pixmap.Format.RGBA8888, w, h, false);
        paintInto(fbo, painter);
        Texture t = fbo.getColorBufferTexture();
        t.setFilter(Texture.TextureFilter.Linear, Texture.TextureFilter.Linear);
        if (repeatU) t.setWrap(Texture.TextureWrap.Repeat, Texture.TextureWrap.ClampToEdge);
        owned.add(fbo);
        return t;
    }

    public void paintInto(FrameBuffer fbo, Painter painter) {
        int w = fbo.getWidth(), h = fbo.getHeight();
        // FBO row 0 is texture v=0 (= "top" in pixmap convention), so design space y=0 must map to
        // NDC -1: a standard ortho. The font is generated flipped so text reads top-down.
        Matrix4 proj = new Matrix4().setToOrtho2D(0, 0, w, h);
        fbo.begin();
        Gdx.gl.glViewport(0, 0, w, h);
        Gdx.gl.glClearColor(0, 0, 0, 0);
        Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT);
        Gdx.gl.glDisable(GL20.GL_DEPTH_TEST);
        Gdx.gl.glDisable(GL20.GL_CULL_FACE);
        Gdx.gl.glEnable(GL20.GL_BLEND);
        Gdx.gl.glBlendFunc(GL20.GL_SRC_ALPHA, GL20.GL_ONE_MINUS_SRC_ALPHA);
        shapes.setProjectionMatrix(proj);
        batch.setProjectionMatrix(proj);
        painter.paint(shapes, batch, texFont);
        fbo.end();
    }

    /** Draw text centred at (cx, cy) with a given pixel height. */
    public static void text(SpriteBatch batch, BitmapFont font, String s, float cx, float cy, float height, Color color) {
        float scale = height / font.getCapHeight();
        font.getData().setScale(scale);
        font.setColor(color);
        GlyphLayout gl = new GlyphLayout(font, s);
        font.draw(batch, s, cx - gl.width / 2f, cy - height / 2f, gl.width, Align.left, false);
        font.getData().setScale(1);
    }

    public static Color hex(int rgb) { return MeshKit.rgbColor(rgb); }

    /** Air fence panel artwork. */
    public Texture fencePanels() {
        final String[][] pal = {
            {"1d3f8f", "ffffff", "BROADSIDE"}, {"c4161c", "ffffff", "SHALE CITY"}, {"f2b705", "111111", "FULL THROTTLE"},
            {"0f7b47", "ffffff", "NO BRAKES"}, {"ffffff", "1d3f8f", "SPEEDWAY"}, {"222222", "f2b705", "GATE 1"},
            {"6b2fa3", "ffffff", "LEFT ONLY"}, {"e86a10", "ffffff", "4 LAPS"}};
        return paint(1024, 128, (sh, b, f) -> {
            float pw = 1024f / pal.length;
            sh.begin(ShapeRenderer.ShapeType.Filled);
            for (int i = 0; i < pal.length; i++) {
                sh.setColor(hex(Integer.parseInt(pal[i][0], 16)));
                sh.rect(i * pw, 0, pw, 128);
                sh.setColor(0, 0, 0, 0.25f);
                sh.rect(i * pw, 0, 3, 128);
            }
            // padding seam shading
            sh.rect(0, 0, 1024, 64, new Color(1, 1, 1, 0.18f), new Color(1, 1, 1, 0.18f), new Color(1, 1, 1, 0f), new Color(1, 1, 1, 0f));
            sh.end();
            b.begin();
            for (int i = 0; i < pal.length; i++) {
                String txt = pal[i][2];
                float hgt = Math.min(30, (pw - 16) / (txt.length() * 0.78f));
                text(b, f, txt, i * pw + pw / 2, 64, hgt, hex(Integer.parseInt(pal[i][1], 16)));
            }
            b.end();
            Gdx.gl.glEnable(GL20.GL_BLEND);
            sh.begin(ShapeRenderer.ShapeType.Filled);
            sh.rect(0, 64, 1024, 64, new Color(0, 0, 0, 0f), new Color(0, 0, 0, 0f), new Color(0, 0, 0, 0.3f), new Color(0, 0, 0, 0.3f));
            sh.end();
        }, true);
    }

    /** Race bib wrapped round the torso: number on the back (u=0.5) and front (u=0/1). */
    public Texture bib(int color, int number) {
        return paint(512, 256, (sh, b, f) -> {
            sh.begin(ShapeRenderer.ShapeType.Filled);
            sh.setColor(hex(color));
            sh.rect(0, 0, 512, 256);
            sh.setColor(0, 0, 0, 0.25f);
            sh.rect(0, 230, 512, 26);
            sh.rect(0, 0, 512, 14);
            sh.setColor(Color.WHITE);
            sh.circle(256, 123, 78, 40);
            sh.circle(0, 123, 52, 40);
            sh.circle(512, 123, 52, 40);
            sh.end();
            b.begin();
            text(b, f, String.valueOf(number), 256, 125, 82, hex(0x111111));
            text(b, f, String.valueOf(number), 0, 125, 54, hex(0x111111));
            text(b, f, String.valueOf(number), 512, 125, 54, hex(0x111111));
            b.end();
        }, false);
    }

    public Texture numberPlate(int number) {
        return paint(128, 96, (sh, b, f) -> {
            sh.begin(ShapeRenderer.ShapeType.Filled);
            sh.setColor(hex(0xf4f4f4));
            sh.rect(0, 0, 128, 96);
            sh.end();
            b.begin();
            text(b, f, String.valueOf(number), 64, 50, 60, hex(0x111111));
            b.end();
        }, false);
    }

    /** Kevlar leathers: dark base, coloured racing stripes and white piping. */
    public Texture suit(int base, int accent) {
        return paint(256, 64, (sh, b, f) -> {
            sh.begin(ShapeRenderer.ShapeType.Filled);
            sh.setColor(hex(base));
            sh.rect(0, 0, 256, 64);
            sh.setColor(hex(accent));
            sh.rect(256 * 0.18f, 0, 256 * 0.14f, 64);
            sh.rect(256 * 0.68f, 0, 256 * 0.14f, 64);
            sh.setColor(hex(0xf2f2f2));
            for (float x : new float[]{0.17f, 0.32f, 0.67f, 0.82f}) sh.rect(256 * x, 0, 2, 64);
            sh.end();
        }, false);
    }

    public Texture hiVis() {
        return paint(256, 128, (sh, b, f) -> {
            sh.begin(ShapeRenderer.ShapeType.Filled);
            sh.setColor(hex(0xff7a00));
            sh.rect(0, 0, 256, 128);
            sh.setColor(hex(0xe8e8e8));
            sh.rect(0, 40, 256, 12);
            sh.rect(0, 80, 256, 12);
            sh.end();
            b.begin();
            text(b, f, "START", 128, 66, 20, hex(0x111111));
            b.end();
        }, false);
    }

    public Texture flag(String kind) {
        return paint(128, 96, (sh, b, f) -> {
            sh.begin(ShapeRenderer.ShapeType.Filled);
            if (kind.equals("chequered")) {
                for (int i = 0; i < 8; i++) for (int j = 0; j < 6; j++) {
                    sh.setColor((i + j) % 2 == 1 ? hex(0x111111) : hex(0xf5f5f5));
                    sh.rect(i * 16, j * 16, 16, 16);
                }
            } else {
                sh.setColor(hex(kind.equals("yellow") ? 0xffd400 : 0x1db954));
                sh.rect(0, 0, 128, 96);
            }
            sh.end();
        }, false);
    }

    @Override
    public void dispose() {
        for (Disposable d : owned) d.dispose();
    }
}
