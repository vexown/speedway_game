package com.broadside.speedway.render;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.*;
import com.badlogic.gdx.graphics.g3d.*;
import com.badlogic.gdx.graphics.g3d.attributes.*;
import com.badlogic.gdx.graphics.g3d.utils.RenderContext;
import com.badlogic.gdx.graphics.g3d.utils.ShaderProvider;
import com.badlogic.gdx.graphics.glutils.FrameBuffer;
import com.badlogic.gdx.graphics.glutils.ShaderProgram;
import com.badlogic.gdx.math.Matrix3;
import com.badlogic.gdx.math.Matrix4;
import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.utils.Disposable;
import com.badlogic.gdx.utils.GdxRuntimeException;

/**
 * Custom lighting: per-pixel sun + hemisphere ambient, sky-gradient reflections (cheap IBL),
 * clearcoat, PCF shadow map, fog, ACES tone mapping. Also provides the shadow depth pass and sky.
 */
public class Lighting implements Disposable {

    /** Material surface parameters (metalness, roughness, clearcoat). */
    public static class Pbr extends Attribute {
        public static final String ALIAS = "pbr";
        public static final long TYPE = register(ALIAS);
        public float metal, rough, clearcoat;
        public Pbr(float metal, float rough, float clearcoat) {
            super(TYPE);
            this.metal = metal; this.rough = rough; this.clearcoat = clearcoat;
        }
        @Override public Attribute copy() { return new Pbr(metal, rough, clearcoat); }
        @Override public int compareTo(Attribute o) { return (int) (type - o.type); }
    }

    /** Marks crowd meshes: vertex shader bounces spectators using phase stored in uv.x. */
    public static class Crowd extends Attribute {
        public static final String ALIAS = "crowd";
        public static final long TYPE = register(ALIAS);
        public Crowd() { super(TYPE); }
        @Override public Attribute copy() { return new Crowd(); }
        @Override public int compareTo(Attribute o) { return (int) (type - o.type); }
    }

    // Scene lighting parameters
    public final Vector3 sunDir = new Vector3(-0.55f, 0.75f, 0.38f).nor(); // towards the sun
    public final Vector3 sunColor = new Vector3(1.9f, 1.62f, 1.32f);
    public final Vector3 skyTop = new Vector3(0.043f, 0.118f, 0.271f);
    public final Vector3 skyMid = new Vector3(0.24f, 0.37f, 0.62f);
    public final Vector3 skyHorizon = new Vector3(0.95f, 0.62f, 0.36f);
    public final Vector3 groundColor = new Vector3(0.29f, 0.23f, 0.16f);
    public final Vector3 fogColor = new Vector3(0.55f, 0.48f, 0.52f);
    public float fogNear = 160, fogFar = 520, envIntensity = 0.8f, ambient = 1.3f, exposure = 0.8f;
    public float time, crowdExcite;
    public boolean shadows = true;

    // Shadow map
    public final OrthographicCamera lightCam = new OrthographicCamera();
    private FrameBuffer shadowFbo;
    private final int shadowSize = 2048;
    public final ModelBatch depthBatch;
    public final ModelBatch batch;
    private final Texture white;

    static final String COMMON = ""
        + "#ifdef GL_ES\nprecision highp float;\n#endif\n";

    static final String SKY_FN = ""
        + "uniform vec3 u_skyTop; uniform vec3 u_skyMid; uniform vec3 u_skyHorizon; uniform vec3 u_ground;\n"
        + "vec3 skyColor(vec3 d){ float h = d.y;\n"
        + "  if (h < 0.0) return mix(u_skyHorizon*0.5, u_ground, smoothstep(0.0, 0.25, -h));\n"
        + "  vec3 c = mix(u_skyHorizon, u_skyMid, smoothstep(0.0, 0.18, h));\n"
        + "  return mix(c, u_skyTop, smoothstep(0.18, 0.7, h)); }\n"
        + "vec3 skyLin(vec3 d){ return pow(skyColor(d), vec3(2.2)); }\n";

    static final String LIT_VS = COMMON
        + "attribute vec3 a_position; attribute vec3 a_normal; attribute vec2 a_texCoord0;\n"
        + "#ifdef VCOLOR\nattribute vec4 a_color;\nvarying vec4 v_color;\n#endif\n"
        + "uniform mat4 u_projView; uniform mat4 u_world; uniform mat3 u_normalMatrix; uniform mat4 u_shadowMat;\n"
        + "uniform float u_time; uniform float u_excite;\n"
        + "varying vec3 v_pos; varying vec3 v_normal; varying vec2 v_uv; varying vec4 v_shadow;\n"
        + "void main(){\n"
        + "  vec3 p = a_position;\n"
        + "#ifdef CROWD\n  p.y += max(0.0, sin(u_time*9.0 + a_color.a*6.2831)) * 0.35 * u_excite;\n#endif\n"
        + "  vec4 wp = u_world * vec4(p, 1.0);\n"
        + "  v_pos = wp.xyz; v_normal = normalize(u_normalMatrix * a_normal); v_uv = a_texCoord0;\n"
        + "#ifdef VCOLOR\n  v_color = a_color;\n#endif\n"
        + "#ifdef CROWD\n  v_color.a = 1.0;\n#endif\n"
        + "  v_shadow = u_shadowMat * wp;\n"
        + "  gl_Position = u_projView * wp; }\n";

    static final String LIT_FS = COMMON + SKY_FN
        + "uniform sampler2D u_tex; uniform sampler2D u_shadowTex; uniform float u_useShadow; uniform float u_shadowTexel;\n"
        + "uniform vec4 u_diffuse; uniform vec3 u_emissive; uniform vec3 u_pbr; uniform float u_opacity;\n"
        + "uniform vec3 u_camPos; uniform vec3 u_sunDir; uniform vec3 u_sunColor; uniform float u_ambient; uniform float u_env;\n"
        + "uniform vec3 u_fogColor; uniform vec2 u_fog; uniform float u_twoSided; uniform float u_exposure;\n"
        + "varying vec3 v_pos; varying vec3 v_normal; varying vec2 v_uv; varying vec4 v_shadow;\n"
        + "#ifdef VCOLOR\nvarying vec4 v_color;\n#endif\n"
        + "float unpack(vec4 c){ return dot(c, vec4(1.0, 1.0/255.0, 1.0/65025.0, 1.0/16581375.0)); }\n"
        + "float shadowAt(vec2 uv, float z){ return z - 0.0015 > unpack(texture2D(u_shadowTex, uv)) ? 0.0 : 1.0; }\n"
        + "float shadow(){ if (u_useShadow < 0.5) return 1.0;\n"
        + "  vec3 s = v_shadow.xyz / v_shadow.w * 0.5 + 0.5;\n"
        + "  if (s.x < 0.0 || s.x > 1.0 || s.y < 0.0 || s.y > 1.0 || s.z > 1.0) return 1.0;\n"
        + "  float sum = 0.0; for (int x=-1; x<=1; x++) for (int y=-1; y<=1; y++) sum += shadowAt(s.xy + vec2(x,y)*u_shadowTexel, s.z);\n"
        + "  return sum / 9.0; }\n"
        + "vec3 aces(vec3 x){ return clamp((x*(2.51*x+0.03))/(x*(2.43*x+0.59)+0.14), 0.0, 1.0); }\n"
        + "void main(){\n"
        + "  vec4 tex = texture2D(u_tex, v_uv);\n"
        + "  vec4 base = tex * u_diffuse;\n"
        + "#ifdef VCOLOR\n  base *= v_color;\n#endif\n"
        + "  float alpha = base.a * u_opacity; if (alpha < 0.02) discard;\n"
        + "  vec3 albedo = pow(base.rgb, vec3(2.2));\n"
        + "  vec3 N = normalize(v_normal); vec3 V = normalize(u_camPos - v_pos);\n"
        + "  if (u_twoSided > 0.5 && dot(N, V) < 0.0) N = -N;\n"
        + "  float metal = u_pbr.x, rough = clamp(u_pbr.y, 0.04, 1.0), coat = u_pbr.z;\n"
        + "  vec3 L = u_sunDir; float ndl = max(dot(N, L), 0.0) * shadow();\n"
        + "  vec3 amb = mix(pow(u_ground, vec3(2.2))*1.5, pow(u_skyMid, vec3(2.2)) + pow(u_skyHorizon, vec3(2.2))*0.35, N.y*0.5+0.5) * u_ambient;\n"
        + "  vec3 F0 = mix(vec3(0.04), albedo, metal);\n"
        + "  vec3 H = normalize(L + V); float nh = max(dot(N, H), 0.0); float nv = max(dot(N, V), 0.001);\n"
        + "  float shin = 2.0 / max(rough*rough*rough*rough, 0.0005) - 2.0;\n"
        + "  float spec = pow(nh, shin) * (shin + 8.0) / 25.13;\n"
        + "  vec3 fres = F0 + (1.0 - F0) * pow(1.0 - nv, 5.0);\n"
        + "  vec3 R = reflect(-V, N); vec3 env = skyLin(R);\n"
        + "  vec3 col = albedo * (1.0 - metal) * (ndl * u_sunColor + amb);\n"
        + "  col += fres * spec * u_sunColor * ndl;\n"
        + "  col += env * fres * (1.0 - rough) * (1.0 - rough) * u_env * (0.4 + 0.6*metal);\n"
        + "  if (coat > 0.0) { float fc = 0.04 + 0.96*pow(1.0 - nv, 5.0);\n"
        + "    col += coat * (env * fc * u_env + pow(nh, 400.0) * 3.0 * u_sunColor * ndl * 0.3); }\n"
        + "  col += u_emissive;\n"
        + "  float dist = length(u_camPos - v_pos);\n"
        + "  col = mix(col, pow(u_fogColor, vec3(2.2)) * 1.4, smoothstep(u_fog.x, u_fog.y, dist));\n"
        + "  col = aces(col * u_exposure);\n"
        + "  gl_FragColor = vec4(pow(col, vec3(1.0/2.2)), alpha); }\n";

    static final String DEPTH_VS = COMMON
        + "attribute vec3 a_position; attribute vec2 a_texCoord0;\n#ifdef CROWD\nattribute vec4 a_color;\n#endif\n"
        + "uniform mat4 u_projView; uniform mat4 u_world;\n"
        + "uniform float u_time; uniform float u_excite; varying float v_depth;\n"
        + "void main(){ vec3 p = a_position;\n"
        + "#ifdef CROWD\n  p.y += max(0.0, sin(u_time*9.0 + a_color.a*6.2831)) * 0.35 * u_excite;\n#endif\n"
        + "  vec4 pos = u_projView * u_world * vec4(p, 1.0); v_depth = pos.z / pos.w * 0.5 + 0.5; gl_Position = pos; }\n";

    static final String DEPTH_FS = COMMON
        + "varying float v_depth;\n"
        + "vec4 pack(float d){ vec4 e = vec4(1.0, 255.0, 65025.0, 16581375.0) * d; e = fract(e);\n"
        + "  e -= e.yzww * vec4(1.0/255.0, 1.0/255.0, 1.0/255.0, 0.0); return e; }\n"
        + "void main(){ gl_FragColor = pack(clamp(v_depth, 0.0, 0.9999)); }\n";

    static final String SKY_VS = COMMON
        + "attribute vec3 a_position; uniform mat4 u_projView; uniform vec3 u_camPos; varying vec3 v_dir;\n"
        + "void main(){ v_dir = normalize(a_position); gl_Position = u_projView * vec4(a_position + u_camPos, 1.0); }\n";
    static final String SKY_FS = COMMON + SKY_FN
        + "varying vec3 v_dir; vec3 aces(vec3 x){ return clamp((x*(2.51*x+0.03))/(x*(2.43*x+0.59)+0.14), 0.0, 1.0); }\n"
        + "void main(){ gl_FragColor = vec4(skyColor(normalize(v_dir)), 1.0); }\n";

    private final ShaderProgram skyProgram;
    private final Mesh skyMesh;

    public Lighting() {
        ShaderProgram.pedantic = false;
        Pixmap px = new Pixmap(1, 1, Pixmap.Format.RGBA8888);
        px.setColor(Color.WHITE);
        px.fill();
        white = new Texture(px);
        px.dispose();
        batch = new ModelBatch(new LitProvider(false));
        depthBatch = new ModelBatch(new LitProvider(true));
        shadowFbo = new FrameBuffer(Pixmap.Format.RGBA8888, shadowSize, shadowSize, true);
        skyProgram = compile(SKY_VS, SKY_FS);
        skyMesh = buildSkyMesh();
    }

    static ShaderProgram compile(String vs, String fs) {
        ShaderProgram p = new ShaderProgram(vs, fs);
        if (!p.isCompiled()) throw new GdxRuntimeException("Shader compile failed: " + p.getLog());
        return p;
    }

    private Mesh buildSkyMesh() {
        int seg = 32, rings = 16;
        float r = 800;
        float[] v = new float[(seg + 1) * (rings + 1) * 3];
        short[] idx = new short[seg * rings * 6];
        int k = 0;
        for (int i = 0; i <= rings; i++) {
            double phi = Math.PI * i / rings;
            for (int j = 0; j <= seg; j++) {
                double th = 2 * Math.PI * j / seg;
                v[k++] = (float) (r * Math.sin(phi) * Math.cos(th));
                v[k++] = (float) (r * Math.cos(phi));
                v[k++] = (float) (r * Math.sin(phi) * Math.sin(th));
            }
        }
        k = 0;
        for (int i = 0; i < rings; i++) for (int j = 0; j < seg; j++) {
            short a = (short) (i * (seg + 1) + j), b = (short) (a + seg + 1);
            idx[k++] = a; idx[k++] = b; idx[k++] = (short) (a + 1);
            idx[k++] = (short) (a + 1); idx[k++] = b; idx[k++] = (short) (b + 1);
        }
        Mesh m = new Mesh(true, v.length / 3, idx.length, new VertexAttribute(VertexAttributes.Usage.Position, 3, "a_position"));
        m.setVertices(v);
        m.setIndices(idx);
        return m;
    }

    private void skyUniforms(ShaderProgram p) {
        p.setUniformf("u_skyTop", skyTop);
        p.setUniformf("u_skyMid", skyMid);
        p.setUniformf("u_skyHorizon", skyHorizon);
        p.setUniformf("u_ground", groundColor);
    }

    public void renderSky(Camera cam) {
        Gdx.gl.glDepthMask(false);
        Gdx.gl.glDisable(GL20.GL_CULL_FACE);
        Gdx.gl.glDisable(GL20.GL_DEPTH_TEST);
        skyProgram.bind();
        skyProgram.setUniformMatrix("u_projView", cam.combined);
        skyProgram.setUniformf("u_camPos", cam.position);
        skyUniforms(skyProgram);
        skyMesh.render(skyProgram, GL20.GL_TRIANGLES);
        Gdx.gl.glDepthMask(true);
    }

    /** Position the shadow camera around a focus point (keeps shadows crisp near the action). */
    public void updateShadowCamera(Vector3 focus, float extent) {
        lightCam.viewportWidth = extent * 2;
        lightCam.viewportHeight = extent * 2;
        lightCam.near = 1;
        lightCam.far = 300;
        lightCam.position.set(focus).mulAdd(sunDir, 120);
        lightCam.direction.set(sunDir).scl(-1);
        lightCam.up.set(0, 1, 0);
        if (Math.abs(lightCam.direction.y) > 0.99f) lightCam.up.set(0, 0, 1);
        lightCam.update();
    }

    public void beginShadowPass() {
        shadowFbo.begin();
        Gdx.gl.glClearColor(1, 1, 1, 1);
        Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT | GL20.GL_DEPTH_BUFFER_BIT);
        depthBatch.begin(lightCam);
    }

    public void endShadowPass() {
        depthBatch.end();
        shadowFbo.end();
    }

    @Override
    public void dispose() {
        batch.dispose();
        depthBatch.dispose();
        shadowFbo.dispose();
        skyProgram.dispose();
        skyMesh.dispose();
        white.dispose();
    }

    // ------------------------------------------------------------------ shaders

    private class LitProvider implements ShaderProvider {
        private final boolean depth;
        private final LitShader[] cache = new LitShader[4];
        LitProvider(boolean depth) { this.depth = depth; }

        @Override
        public Shader getShader(Renderable r) {
            int key = variant(r);
            if (cache[key] == null) {
                cache[key] = new LitShader(depth, key);
                cache[key].init();
            }
            return cache[key];
        }

        @Override
        public void dispose() {
            for (LitShader s : cache) if (s != null) s.dispose();
        }
    }

    static int variant(Renderable r) {
        boolean vc = r.meshPart.mesh.getVertexAttribute(VertexAttributes.Usage.ColorPacked) != null
            || r.meshPart.mesh.getVertexAttribute(VertexAttributes.Usage.ColorUnpacked) != null;
        boolean crowd = r.material.has(Crowd.TYPE);
        return (vc ? 1 : 0) | (crowd ? 2 : 0);
    }

    private class LitShader implements Shader {
        final boolean depth;
        final int key;
        ShaderProgram program;
        RenderContext ctx;
        Camera cam;
        final Matrix3 normalMat = new Matrix3();
        final Matrix4 tmpMat = new Matrix4();
        final Vector3 emissive = new Vector3();

        LitShader(boolean depth, int key) { this.depth = depth; this.key = key; }

        @Override
        public void init() {
            String defs = ((key & 1) != 0 ? "#define VCOLOR\n" : "") + ((key & 2) != 0 ? "#define CROWD\n" : "");
            program = depth ? compile(defs + DEPTH_VS, defs + DEPTH_FS) : compile(defs + LIT_VS, defs + LIT_FS);
        }

        @Override public int compareTo(Shader other) { return 0; }
        @Override public boolean canRender(Renderable r) { return variant(r) == key; }

        @Override
        public void begin(Camera camera, RenderContext context) {
            this.cam = camera;
            this.ctx = context;
            program.bind();
            program.setUniformMatrix("u_projView", camera.combined);
            program.setUniformf("u_time", time);
            program.setUniformf("u_excite", crowdExcite);
            ctx.setDepthTest(GL20.GL_LEQUAL);
            ctx.setDepthMask(true);
            if (depth) {
                ctx.setCullFace(0);
                return;
            }
            program.setUniformf("u_camPos", camera.position);
            program.setUniformf("u_sunDir", sunDir);
            program.setUniformf("u_sunColor", sunColor);
            program.setUniformf("u_ambient", ambient);
            program.setUniformf("u_env", envIntensity);
            program.setUniformf("u_fogColor", fogColor);
            program.setUniformf("u_fog", fogNear, fogFar);
            program.setUniformf("u_exposure", exposure);
            skyUniforms(program);
            program.setUniformMatrix("u_shadowMat", lightCam.combined);
            program.setUniformf("u_useShadow", shadows ? 1f : 0f);
            program.setUniformf("u_shadowTexel", 1f / shadowSize);
            int unit = ctx.textureBinder.bind(shadowFbo.getColorBufferTexture());
            program.setUniformi("u_shadowTex", unit);
        }

        @Override
        public void render(Renderable r) {
            program.setUniformMatrix("u_world", r.worldTransform);
            Material m = r.material;
            if (depth) {
                if (m.has(BlendingAttribute.Type)) return; // transparent things don't cast shadows
                r.meshPart.render(program);
                return;
            }
            normalMat.set(tmpMat.set(r.worldTransform).toNormalMatrix());
            program.setUniformMatrix("u_normalMatrix", normalMat);
            ColorAttribute diff = (ColorAttribute) m.get(ColorAttribute.Diffuse);
            if (diff != null) program.setUniformf("u_diffuse", diff.color);
            else program.setUniformf("u_diffuse", 1, 1, 1, 1);
            ColorAttribute em = (ColorAttribute) m.get(ColorAttribute.Emissive);
            if (em != null) emissive.set(em.color.r, em.color.g, em.color.b).scl(em.color.a * 4f);
            else emissive.setZero();
            program.setUniformf("u_emissive", emissive);
            Pbr pbr = (Pbr) m.get(Pbr.TYPE);
            if (pbr != null) program.setUniformf("u_pbr", pbr.metal, pbr.rough, pbr.clearcoat);
            else program.setUniformf("u_pbr", 0f, 0.8f, 0f);
            TextureAttribute ta = (TextureAttribute) m.get(TextureAttribute.Diffuse);
            int unit = ctx.textureBinder.bind(ta != null ? ta.textureDescription.texture : white);
            program.setUniformi("u_tex", unit);
            BlendingAttribute ba = (BlendingAttribute) m.get(BlendingAttribute.Type);
            if (ba != null && ba.blended) {
                ctx.setBlending(true, ba.sourceFunction, ba.destFunction);
                ctx.setDepthMask(false);
                program.setUniformf("u_opacity", ba.opacity);
            } else {
                ctx.setBlending(false, GL20.GL_SRC_ALPHA, GL20.GL_ONE_MINUS_SRC_ALPHA);
                ctx.setDepthMask(true);
                program.setUniformf("u_opacity", 1f);
            }
            IntAttribute cull = (IntAttribute) m.get(IntAttribute.CullFace);
            boolean twoSided = cull != null && cull.value == 0;
            ctx.setCullFace(twoSided ? 0 : GL20.GL_BACK);
            program.setUniformf("u_twoSided", twoSided ? 1f : 0f);
            r.meshPart.render(program);
        }

        @Override public void end() {}
        @Override public void dispose() { program.dispose(); }
    }
}
