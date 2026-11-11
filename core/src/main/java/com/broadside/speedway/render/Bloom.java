package com.broadside.speedway.render;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.*;
import com.badlogic.gdx.graphics.glutils.FrameBuffer;
import com.badlogic.gdx.graphics.glutils.ShaderProgram;
import com.badlogic.gdx.utils.Disposable;
import com.badlogic.gdx.utils.GdxRuntimeException;

/**
 * Bloom post-process. The scene is rendered normally to the (multisampled) back buffer, copied
 * into a texture (the copy resolves MSAA), bright areas are extracted with a soft threshold,
 * blurred at 1/2, 1/4 and 1/8 resolution, then added back on top. Uses only GL ES 2.0 features.
 */
public class Bloom implements Disposable {
    public float threshold = 0.76f;   // luminance (0..1, display space) where glow starts
    public float knee = 0.22f;        // soft transition width
    public float intensity = 0.75f;
    public boolean enabled = true;

    private static final int LEVELS = 3;
    private final FrameBuffer[] a = new FrameBuffer[LEVELS], b = new FrameBuffer[LEVELS];
    private Texture sceneTex;
    private int width, height;
    private final Mesh quad;
    private final ShaderProgram bright, blur, copy, composite;

    private static final String VS = ""
        + "attribute vec2 a_position; varying vec2 v_uv;\n"
        + "void main(){ v_uv = a_position * 0.5 + 0.5; gl_Position = vec4(a_position, 0.0, 1.0); }\n";
    private static final String HDR = "#ifdef GL_ES\nprecision mediump float;\n#endif\n";

    /** Bright pass with a 4-tap box downsample (stable, no sparkle). */
    private static final String BRIGHT_FS = HDR
        + "uniform sampler2D u_tex; uniform vec2 u_texel; uniform float u_threshold; uniform float u_knee; varying vec2 v_uv;\n"
        + "vec3 pick(vec2 uv){ vec3 c = texture2D(u_tex, uv).rgb; float l = max(c.r, max(c.g, c.b));\n"
        + "  float soft = clamp(l - u_threshold + u_knee, 0.0, 2.0 * u_knee); soft = soft * soft / (4.0 * u_knee + 1e-4);\n"
        + "  float w = max(soft, l - u_threshold) / max(l, 1e-4); return c * w; }\n"
        + "void main(){ vec2 o = u_texel * 0.5;\n"
        + "  vec3 c = pick(v_uv + vec2(-o.x, -o.y)) + pick(v_uv + vec2(o.x, -o.y)) + pick(v_uv + vec2(-o.x, o.y)) + pick(v_uv + o);\n"
        + "  gl_FragColor = vec4(c * 0.25, 1.0); }\n";

    /** 9-tap gaussian using 5 bilinear fetches. */
    private static final String BLUR_FS = HDR
        + "uniform sampler2D u_tex; uniform vec2 u_dir; varying vec2 v_uv;\n"
        + "void main(){\n"
        + "  vec3 c = texture2D(u_tex, v_uv).rgb * 0.2270270;\n"
        + "  vec2 o1 = u_dir * 1.3846154, o2 = u_dir * 3.2307692;\n"
        + "  c += (texture2D(u_tex, v_uv + o1).rgb + texture2D(u_tex, v_uv - o1).rgb) * 0.3162162;\n"
        + "  c += (texture2D(u_tex, v_uv + o2).rgb + texture2D(u_tex, v_uv - o2).rgb) * 0.0702703;\n"
        + "  gl_FragColor = vec4(c, 1.0); }\n";

    private static final String COPY_FS = HDR
        + "uniform sampler2D u_tex; uniform vec2 u_texel; varying vec2 v_uv;\n"
        + "void main(){ vec2 o = u_texel * 0.5;\n"
        + "  vec3 c = texture2D(u_tex, v_uv + vec2(-o.x, -o.y)).rgb + texture2D(u_tex, v_uv + vec2(o.x, -o.y)).rgb\n"
        + "         + texture2D(u_tex, v_uv + vec2(-o.x, o.y)).rgb + texture2D(u_tex, v_uv + o).rgb;\n"
        + "  gl_FragColor = vec4(c * 0.25, 1.0); }\n";

    private static final String COMPOSITE_FS = HDR
        + "uniform sampler2D u_l0; uniform sampler2D u_l1; uniform sampler2D u_l2; uniform float u_intensity; varying vec2 v_uv;\n"
        + "void main(){\n"
        + "  vec3 c = texture2D(u_l0, v_uv).rgb * 0.45 + texture2D(u_l1, v_uv).rgb * 0.75 + texture2D(u_l2, v_uv).rgb * 1.1;\n"
        + "  gl_FragColor = vec4(c * u_intensity, 1.0); }\n";

    public Bloom() {
        quad = new Mesh(true, 4, 6, new VertexAttribute(VertexAttributes.Usage.Position, 2, "a_position"));
        quad.setVertices(new float[]{-1, -1, 1, -1, 1, 1, -1, 1});
        quad.setIndices(new short[]{0, 1, 2, 2, 3, 0});
        bright = compile(BRIGHT_FS);
        blur = compile(BLUR_FS);
        copy = compile(COPY_FS);
        composite = compile(COMPOSITE_FS);
    }

    private static ShaderProgram compile(String fs) {
        ShaderProgram p = new ShaderProgram(VS, fs);
        if (!p.isCompiled()) throw new GdxRuntimeException("Bloom shader: " + p.getLog());
        return p;
    }

    private void ensureSize(int w, int h) {
        if (w == width && h == height && sceneTex != null) return;
        disposeTargets();
        width = w;
        height = h;
        Pixmap px = new Pixmap(w, h, Pixmap.Format.RGB888);
        sceneTex = new Texture(px);
        px.dispose();
        sceneTex.setFilter(Texture.TextureFilter.Linear, Texture.TextureFilter.Linear);
        sceneTex.setWrap(Texture.TextureWrap.ClampToEdge, Texture.TextureWrap.ClampToEdge);
        for (int i = 0; i < LEVELS; i++) {
            int lw = Math.max(1, w >> (i + 1)), lh = Math.max(1, h >> (i + 1));
            a[i] = target(lw, lh);
            b[i] = target(lw, lh);
        }
    }

    private static FrameBuffer target(int w, int h) {
        FrameBuffer f = new FrameBuffer(Pixmap.Format.RGB888, w, h, false);
        f.getColorBufferTexture().setFilter(Texture.TextureFilter.Linear, Texture.TextureFilter.Linear);
        f.getColorBufferTexture().setWrap(Texture.TextureWrap.ClampToEdge, Texture.TextureWrap.ClampToEdge);
        return f;
    }

    private void pass(ShaderProgram p, Texture src, FrameBuffer dst) {
        dst.begin();
        src.bind(0);
        p.bind();
        p.setUniformi("u_tex", 0);
        quad.render(p, GL20.GL_TRIANGLES);
        dst.end();
    }

    private void blurLevel(int i) {
        float tw = 1f / a[i].getWidth(), th = 1f / a[i].getHeight();
        blur.bind();
        blur.setUniformf("u_dir", tw, 0);
        pass(blur, a[i].getColorBufferTexture(), b[i]);
        blur.bind();
        blur.setUniformf("u_dir", 0, th);
        pass(blur, b[i].getColorBufferTexture(), a[i]);
    }

    /** Call after the 3D scene has been drawn to the back buffer and before the HUD. */
    public void apply() {
        if (!enabled) return;
        int w = Gdx.graphics.getBackBufferWidth(), h = Gdx.graphics.getBackBufferHeight();
        if (w <= 0 || h <= 0) return;
        ensureSize(w, h);
        GL20 gl = Gdx.gl;
        gl.glDisable(GL20.GL_DEPTH_TEST);
        gl.glDisable(GL20.GL_CULL_FACE);
        gl.glDisable(GL20.GL_BLEND);
        gl.glDepthMask(false);

        // 1) copy the (resolved) back buffer into a texture
        sceneTex.bind(0);
        gl.glCopyTexSubImage2D(GL20.GL_TEXTURE_2D, 0, 0, 0, 0, 0, w, h);

        // 2) bright pass + downsample to 1/2
        bright.bind();
        bright.setUniformf("u_texel", 1f / w, 1f / h);
        bright.setUniformf("u_threshold", threshold);
        bright.setUniformf("u_knee", knee);
        pass(bright, sceneTex, a[0]);
        blurLevel(0);
        // 3) further levels
        for (int i = 1; i < LEVELS; i++) {
            copy.bind();
            copy.setUniformf("u_texel", 1f / a[i - 1].getWidth(), 1f / a[i - 1].getHeight());
            pass(copy, a[i - 1].getColorBufferTexture(), a[i]);
            blurLevel(i);
        }

        // 4) additive composite onto the back buffer
        gl.glViewport(0, 0, w, h);
        gl.glEnable(GL20.GL_BLEND);
        gl.glBlendFunc(GL20.GL_ONE, GL20.GL_ONE);
        a[0].getColorBufferTexture().bind(0);
        a[1].getColorBufferTexture().bind(1);
        a[2].getColorBufferTexture().bind(2);
        gl.glActiveTexture(GL20.GL_TEXTURE0);
        composite.bind();
        composite.setUniformi("u_l0", 0);
        composite.setUniformi("u_l1", 1);
        composite.setUniformi("u_l2", 2);
        composite.setUniformf("u_intensity", intensity);
        quad.render(composite, GL20.GL_TRIANGLES);
        gl.glBlendFunc(GL20.GL_SRC_ALPHA, GL20.GL_ONE_MINUS_SRC_ALPHA);
        gl.glDepthMask(true);
    }

    private void disposeTargets() {
        if (sceneTex != null) sceneTex.dispose();
        for (int i = 0; i < LEVELS; i++) {
            if (a[i] != null) a[i].dispose();
            if (b[i] != null) b[i].dispose();
            a[i] = b[i] = null;
        }
        sceneTex = null;
    }

    @Override
    public void dispose() {
        disposeTargets();
        quad.dispose();
        bright.dispose();
        blur.dispose();
        copy.dispose();
        composite.dispose();
    }
}
