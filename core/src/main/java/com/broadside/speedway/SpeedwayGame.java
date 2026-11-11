package com.broadside.speedway;

import com.badlogic.gdx.ApplicationAdapter;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Input;
import com.badlogic.gdx.InputAdapter;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.PerspectiveCamera;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.PixmapIO;
import com.badlogic.gdx.math.MathUtils;
import com.badlogic.gdx.math.Matrix4;
import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.utils.Array;
import com.badlogic.gdx.utils.IntArray;
import com.badlogic.gdx.utils.Pools;
import com.badlogic.gdx.utils.ScreenUtils;
import com.broadside.speedway.audio.Synth;
import com.broadside.speedway.render.*;

/** Broadside Speedway — libGDX edition. Game flow, cameras, render passes, input. */
public class SpeedwayGame extends ApplicationAdapter {
    public enum Mode { MENU, RACE, RESULTS, PAUSED }

    // Settings (menu)
    public int difficultyIdx = 1;   // Rookie / Pro / Legend
    public static final String[] DIFFICULTY_NAMES = {"Rookie", "Pro", "Legend"};
    public static final float[] DIFFICULTY = {0.9f, 1.0f, 1.07f};
    public int lapsIdx = 1;
    public static final int[] LAP_OPTIONS = {2, 4, 6};
    public boolean assisted = true;
    public boolean shadowsOn = true;
    public boolean bloomOn = true;

    // State
    public Mode mode = Mode.MENU;
    public Race race;
    public Meeting meeting;
    public int camMode;
    public final Debug debug;

    // Rendering
    public PerspectiveCamera cam;
    public Lighting lighting;
    Bloom bloom;
    Textures textures;
    Stadium stadium;
    final BikeView[] views = new BikeView[4];
    MarshalView marshal;
    Effects effects;
    public Synth synth;
    Hud hud;

    // Input
    float inThrottle, inSteer;
    AIDriver autopilot;
    private float introT, simTime, finishedT = -1;
    private int finishersSeen;
    private boolean cheeredFinish;
    private Race.Phase prevPhase;

    /** Meeting: 5 heats, 3-2-1-0 scoring, gates rotate; heat 5 gates by points. */
    public static class Meeting {
        public int heat;
        public final int[] points = new int[4];
        public final Array<int[]> history = new Array<>(); // per heat: rider ids in finishing order
        public final IntArray historyHeat = new IntArray();
    }

    /** Debug/automation options (system properties), e.g. for screenshots. */
    public static class Debug {
        public String autostart;     // "quick" | "meeting"
        public boolean autopilot;
        public int cam = -1;
        public String shotPrefix;    // write PNGs as <prefix>_<n>.png
        public float[] shotTimes = new float[0];
        public int[] shotCams = new int[0];
        public float crashAt = -1;   // force a player crash at this race time
        public float quitAfter = -1;
        public int laps = -1;
        public boolean showRiderCloseup;

        public static Debug fromSystemProperties() {
            Debug d = new Debug();
            d.autostart = System.getProperty("broadside.autostart");
            d.autopilot = System.getProperty("broadside.autopilot") != null;
            d.cam = Integer.parseInt(System.getProperty("broadside.cam", "-1"));
            d.shotPrefix = System.getProperty("broadside.shots");
            String times = System.getProperty("broadside.shotTimes", "");
            if (!times.isEmpty()) {
                String[] parts = times.split(",");
                d.shotTimes = new float[parts.length];
                d.shotCams = new int[parts.length];
                for (int i = 0; i < parts.length; i++) {
                    String[] tc = parts[i].split("@");
                    d.shotTimes[i] = Float.parseFloat(tc[0]);
                    d.shotCams[i] = tc.length > 1 ? Integer.parseInt(tc[1]) : -1;
                }
            }
            d.crashAt = Float.parseFloat(System.getProperty("broadside.crashAt", "-1"));
            d.quitAfter = Float.parseFloat(System.getProperty("broadside.quitAfter", "-1"));
            d.laps = Integer.parseInt(System.getProperty("broadside.laps", "-1"));
            return d;
        }
    }

    public SpeedwayGame(Debug debug) {
        this.debug = debug == null ? new Debug() : debug;
    }

    @Override
    public void create() {
        PhysicsWorld.initBullet();
        cam = new PerspectiveCamera(62, Gdx.graphics.getWidth(), Gdx.graphics.getHeight());
        cam.near = 0.1f;
        cam.far = 1500;
        cam.position.set(0, 30, 90);
        cam.lookAt(0, 0, 0);
        cam.update();
        lighting = new Lighting();
        bloom = new Bloom();
        if ("off".equals(System.getProperty("broadside.bloom"))) bloomOn = false;
        textures = new Textures();
        stadium = new Stadium(textures);
        for (int i = 0; i < 4; i++) {
            Race.RiderDef r = Race.RIDERS[i];
            views[i] = new BikeView(textures, i, r.color, r.helmet);
        }
        marshal = new MarshalView(textures);
        effects = new Effects(textures.softDot(), cam, stadium.lampPositions);
        synth = new Synth();
        hud = new Hud(this);
        Gdx.input.setInputProcessor(new InputAdapter() {
            @Override public boolean keyDown(int keycode) { return onKey(keycode); }
            @Override public boolean touchDown(int x, int y, int pointer, int button) { hud.click(x, y); return true; }
            @Override public boolean mouseMoved(int x, int y) { hud.hover(x, y); return false; }
        });
        if (debug.cam >= 0) camMode = debug.cam;
        if (debug.laps > 0) for (int i = 0; i < LAP_OPTIONS.length; i++) if (LAP_OPTIONS[i] == debug.laps) lapsIdx = i;
        if (debug.autostart != null) startGame(debug.autostart.equals("meeting"));
    }

    // ------------------------------------------------------------------ flow
    public void startGame(boolean isMeeting) {
        synth.start();
        meeting = isMeeting ? new Meeting() : null;
        lighting.shadows = shadowsOn;
        startHeat();
    }

    int[] gatesForHeat(int heat) {
        if (meeting == null) {
            IntArray g = new IntArray(new int[]{0, 1, 2, 3});
            g.shuffle();
            return g.toArray();
        }
        if (heat < Race.HEAT_GATES.length) return Race.HEAT_GATES[heat].clone();
        Integer[] order = {0, 1, 2, 3};
        java.util.Arrays.sort(order, (a, b) -> meeting.points[b] - meeting.points[a]);
        int[] gates = new int[4];
        for (int i = 0; i < 4; i++) gates[order[i]] = i;
        return gates;
    }

    public void startHeat() {
        if (race != null) race.dispose();
        int heat = meeting != null ? meeting.heat : 0;
        int laps = LAP_OPTIONS[lapsIdx];
        race = new Race(gatesForHeat(heat), laps, DIFFICULTY[difficultyIdx], false);
        for (int i = 0; i < 4; i++) views[i].bind(race.bikes.get(i));
        autopilot = debug.autopilot ? new AIDriver(race.player, new AIDriver.Persona()) : null;
        race.player.assist = assisted && autopilot == null;
        mode = Mode.RACE;
        introT = 0;
        finishedT = -1;
        finishersSeen = 0;
        cheeredFinish = false;
        prevPhase = null;
    }

    public void toMenu() {
        if (race != null) { race.dispose(); race = null; }
        synth.silenceEngines();
        mode = Mode.MENU;
    }

    public void togglePause() {
        if (mode == Mode.RACE) { mode = Mode.PAUSED; synth.silenceEngines(); }
        else if (mode == Mode.PAUSED) mode = Mode.RACE;
    }

    void showResults() {
        Array<Bike> order = race.standings();
        mode = Mode.RESULTS;
        synth.silenceEngines();
        if (meeting != null) {
            int[] ids = new int[4];
            for (int i = 0; i < order.size; i++) { meeting.points[order.get(i).id] += Race.POINTS[i]; ids[i] = order.get(i).id; }
            meeting.history.add(ids);
            meeting.historyHeat.add(meeting.heat);
        }
        hud.results(order);
    }

    public void nextHeat() {
        if (meeting != null && meeting.heat < 4) { meeting.heat++; startHeat(); }
        else if (meeting != null) toMenu();
        else startHeat();
    }

    public void rerunHeat() {
        if (meeting != null && meeting.history.size > 0) {
            int[] last = meeting.history.pop();
            meeting.heat = meeting.historyHeat.pop();
            for (int i = 0; i < 4; i++) meeting.points[last[i]] -= Race.POINTS[i];
        }
        startHeat();
    }

    private boolean onKey(int key) {
        if (key == Input.Keys.C && (mode == Mode.RACE || mode == Mode.PAUSED)) { camMode = (camMode + 1) % 3; return true; }
        if (key == Input.Keys.M) { synth.setMuted(!synth.isMuted()); return true; }
        if ((key == Input.Keys.ESCAPE || key == Input.Keys.P) && (mode == Mode.RACE || mode == Mode.PAUSED)) { togglePause(); return true; }
        if (key == Input.Keys.R && (mode == Mode.RACE || mode == Mode.PAUSED)) { startHeat(); return true; }
        return hud.key(key);
    }

    // ------------------------------------------------------------------ input
    private void pollInput(float dt) {
        boolean up = Gdx.input.isKeyPressed(Input.Keys.UP) || Gdx.input.isKeyPressed(Input.Keys.W);
        boolean feather = Gdx.input.isKeyPressed(Input.Keys.SHIFT_LEFT) || Gdx.input.isKeyPressed(Input.Keys.SHIFT_RIGHT);
        boolean left = Gdx.input.isKeyPressed(Input.Keys.LEFT) || Gdx.input.isKeyPressed(Input.Keys.A);
        boolean right = Gdx.input.isKeyPressed(Input.Keys.RIGHT) || Gdx.input.isKeyPressed(Input.Keys.D);
        float tT = up ? (feather ? 0.55f : 1) : 0;
        float sT = (left ? 1 : 0) - (right ? 1 : 0);
        float tRate = tT > inThrottle ? 6 : 9;
        inThrottle += Math.signum(tT - inThrottle) * Math.min(Math.abs(tT - inThrottle), tRate * dt);
        float sRate = Math.abs(sT) < Math.abs(inSteer) || Math.signum(sT) != Math.signum(inSteer) ? 9 : 5;
        inSteer += Math.signum(sT - inSteer) * Math.min(Math.abs(sT - inSteer), sRate * dt);
    }

    // ------------------------------------------------------------------ main loop
    private float wallTime;
    private int shotIndex;

    @Override
    public void render() {
        float dt = MathUtils.clamp(Gdx.graphics.getDeltaTime(), 0, 0.05f);
        wallTime += Gdx.graphics.getDeltaTime();
        simTime += dt;
        pollInput(dt);

        if (race != null && mode == Mode.RACE) {
            Bike p = race.player;
            if (autopilot != null) autopilot.update(dt, race.bikes, race, true);
            else { p.throttleIn = inThrottle; p.steerIn = inSteer; }
            if (debug.crashAt >= 0 && race.phase == Race.Phase.RACING && race.time > debug.crashAt) {
                debug.crashAt = -1;
                p.crash("test crash");
            }
            race.step(dt);
            handleEvents();
            if (race.finishOrder.size > finishersSeen) {
                if (finishersSeen == 0) marshal.waveFlag("chequered", 14);
                finishersSeen = race.finishOrder.size;
            }
            if (p.finished && !cheeredFinish) { cheeredFinish = true; synth.cheer(1); stadium.crowdExcite = 1; }
            if (race.phase == Race.Phase.DONE) {
                if (finishedT < 0) { finishedT = 0; synth.cheer(1); stadium.crowdExcite = 1; }
                finishedT += dt;
                if (finishedT > 1.8f) showResults();
            }
            updateAudio();
        }
        stadium.crowdExcite = Math.max(0.08f, stadium.crowdExcite - dt * 0.15f);

        // Visual sync
        if (race != null) {
            for (BikeView v : views) {
                v.sync(dt, v.bike.isPlayer && camMode == 2);
                if (mode == Mode.RACE) effects.bike(v, dt);
            }
            stadium.updateGate(race.phase, race.tapeHeight);
            stadium.drawBoard(boardTitle(), boardRows(), boardColors());
        }
        if (mode != Mode.PAUSED) {
            effects.update(dt, cam);
            marshal.update(dt, race);
        }
        updateCamera(dt);
        renderScene();
        bloom.enabled = bloomOn;
        bloom.apply();
        hud.render(dt);
        debugShots();
    }

    private String boardTitle() {
        Bike leader = race.standings().first();
        int lap = Math.min(race.laps, Math.max(1, leader.lap));
        return (meeting != null ? "HEAT " + (meeting.heat + 1) : "HEAT") + "  -  LAP " + lap;
    }

    private String[] boardRows() {
        Array<Bike> o = race.standings();
        String[] rows = new String[o.size];
        for (int i = 0; i < o.size; i++) rows[i] = (i + 1) + " " + o.get(i).name.toUpperCase();
        return rows;
    }

    private int[] boardColors() {
        Array<Bike> o = race.standings();
        int[] c = new int[o.size];
        for (int i = 0; i < o.size; i++) c[i] = BikeView.helmetColor(o.get(i).helmet);
        return c;
    }

    private void handleEvents() {
        Vector3 pos = new Vector3();
        for (Race.GameEvent e : race.gameEvents) {
            if (e.type.equals("tapes")) { synth.tapes(); stadium.crowdExcite = Math.max(stadium.crowdExcite, 0.8f); continue; }
            if (e.bike == null) continue;
            e.bike.position(pos);
            float near = e.bike.isPlayer ? 1 : Math.max(0.15f, 1 - cam.position.dst(pos) / 80);
            if (e.type.equals("crash")) {
                marshal.waveFlag("yellow", 3.5f);
                synth.crash(near);
                synth.cheer(0.6f);
                effects.burst(pos.x, 0, pos.z, 1.4f);
                stadium.crowdExcite = Math.max(stadium.crowdExcite, 0.6f);
            } else if ((e.type.equals("fence") || e.type.equals("bike")) && e.impact > 2.5f) {
                synth.thump(Math.min(1, e.impact / 10) * near);
                if (e.impact > 4) effects.burst(pos.x, 0.5f, pos.z, Math.min(1, e.impact / 10));
            }
        }
        race.gameEvents.clear();
        if (race.phase != prevPhase) {
            if (race.phase == Race.Phase.READY) synth.beep(660, 0.2f);
            prevPhase = race.phase;
        }
    }

    private void updateAudio() {
        Vector3 right = new Vector3(cam.direction).crs(cam.up).nor();
        Vector3 pos = new Vector3();
        for (Bike b : race.bikes) {
            b.position(pos);
            float dx = pos.x - cam.position.x, dz = pos.z - cam.position.z;
            float dist = (float) Math.hypot(dx, dz);
            float vol = b.isPlayer && camMode != 1 ? 1 : Math.min(1, 14 / (dist + 6));
            float pan = b.isPlayer ? 0 : (dx * right.x + dz * right.z) / (dist + 1);
            synth.setEngine(b.id, b.isPlayer, b.rpm, b.state == Bike.State.CRASHED ? 0 : b.throttleIn, vol, pan);
        }
    }

    // ------------------------------------------------------------------ camera
    private final Vector3 camPos = new Vector3(0, 30, 90), camLook = new Vector3(), target = new Vector3(), tmp = new Vector3();
    public float orbitOffset;

    private void updateCamera(float dt) {
        cam.viewportWidth = Gdx.graphics.getWidth();
        cam.viewportHeight = Gdx.graphics.getHeight();
        float fov = 62;
        if (race == null) {
            float t = simTime * 0.08f;
            cam.position.set(MathUtils.cos(t) * 95, 32, MathUtils.sin(t) * 70);
            cam.up.set(0, 1, 0);
            cam.lookAt(0, 0, 0);
            cam.fieldOfView = fov;
            cam.update();
            return;
        }
        Bike p = race.player;
        p.position(target);
        target.y = 0.8f;
        if (p.state == Bike.State.CRASHED && p.ragdoll != null && p.ragdoll.torso != null) {
            Matrix4 m = new Matrix4();
            p.ragdoll.torso.body.getWorldTransform(m);
            m.getTranslation(target);
        }
        float th = p.heading();
        float fx = MathUtils.sin(th), fz = MathUtils.cos(th);
        cam.up.set(0, 1, 0);

        if (camMode == 4) { cam.update(); return; } // manual (debug)
        if (camMode == 3) {
            float a = simTime * 0.4f + orbitOffset;
            cam.position.set(target.x + MathUtils.sin(a) * 3.0f, 1.5f, target.z + MathUtils.cos(a) * 3.0f);
            cam.lookAt(target.x, 0.85f, target.z);
            cam.fieldOfView = 50;
            cam.update();
            return;
        }
        if (race.phase == Race.Phase.INTRO && camMode == 0) {
            introT += dt;
            float k = Math.min(1, introT / 1.8f);
            float mid = Track.INNER_RADIUS + Track.WIDTH / 2;
            cam.position.set(18 - 10 * k, 3 + k, mid + 9 - 4 * k);
            camPos.set(cam.position);
            cam.lookAt(-1, 0.8f, mid);
            cam.fieldOfView = fov;
            cam.update();
            return;
        }
        if (camMode == 0) {
            Vector3 v = p.velocity(tmp);
            float sp = (float) Math.hypot(v.x, v.z);
            float dx = fx, dz = fz;
            if (sp > 3 && p.state == Bike.State.RIDING) {
                dx = fx * 0.4f + v.x / sp * 0.6f;
                dz = fz * 0.4f + v.z / sp * 0.6f;
                float l = (float) Math.hypot(dx, dz); dx /= l; dz /= l;
            }
            float dist = 6.2f + Math.min(sp, 30) * 0.05f;
            Vector3 want = new Vector3(target.x - dx * dist, target.y + 2.4f + sp * 0.02f, target.z - dz * dist);
            camPos.lerp(want, 1 - (float) Math.exp(-dt * 6));
            cam.position.set(camPos);
            cam.lookAt(target.x + dx * 4, target.y + 0.7f, target.z + dz * 4);
        } else if (camMode == 1) {
            cam.position.set(0, 22, 12);
            camLook.lerp(target, 1 - (float) Math.exp(-dt * 8));
            cam.lookAt(camLook);
            float dist = cam.position.dst(target);
            fov = MathUtils.clamp(2 * MathUtils.atan2(9, dist) * MathUtils.radiansToDegrees, 12, 62);
        } else {
            BikeView v = views[p.id];
            Vector3 eye = new Vector3(0, 1.62f, 0.22f).mul(v.leanWorld);
            Vector3 look = new Vector3(0, 1.2f, 6).mul(v.leanWorld);
            Vector3 up = new Vector3(0, 1, 0).rot(v.leanWorld).add(0, 1, 0).nor(); // half the bike's roll
            cam.position.set(eye);
            cam.up.set(up);
            cam.direction.set(look).sub(eye).nor();
        }
        cam.fieldOfView = fov;
        cam.update();
    }

    // ------------------------------------------------------------------ rendering
    private void renderScene() {
        lighting.time = simTime;
        lighting.crowdExcite = stadium.crowdExcite;
        lighting.shadows = shadowsOn;
        Vector3 focus = race != null ? race.player.position(new Vector3()) : new Vector3();
        if (shadowsOn) {
            lighting.updateShadowCamera(focus, race != null ? 40 : 110);
            lighting.beginShadowPass();
            for (com.badlogic.gdx.graphics.g3d.ModelInstance mi : stadium.casters) lighting.depthBatch.render(mi);
            if (race != null) for (BikeView v : views) v.render(lighting.depthBatch);
            marshal.render(lighting.depthBatch);
            lighting.endShadowPass();
        }
        Gdx.gl.glViewport(0, 0, Gdx.graphics.getBackBufferWidth(), Gdx.graphics.getBackBufferHeight());
        ScreenUtils.clear(0.04f, 0.05f, 0.08f, 1, true);
        lighting.renderSky(cam);
        lighting.batch.begin(cam);
        for (com.badlogic.gdx.graphics.g3d.ModelInstance mi : stadium.instances) lighting.batch.render(mi);
        if (race != null) for (BikeView v : views) v.render(lighting.batch);
        marshal.render(lighting.batch);
        lighting.batch.end();
        effects.render(cam);
    }

    private void debugShots() {
        if (debug.shotPrefix != null && shotIndex < debug.shotTimes.length) {
            float t = debug.shotTimes[shotIndex];
            if (debug.shotCams[shotIndex] >= 0 && wallTime > t - 0.7f) camMode = debug.shotCams[shotIndex];
            if (wallTime >= t) {
                Pixmap px = Pixmap.createFromFrameBuffer(0, 0, Gdx.graphics.getBackBufferWidth(), Gdx.graphics.getBackBufferHeight());
                // flip vertically
                Pixmap flipped = new Pixmap(px.getWidth(), px.getHeight(), Pixmap.Format.RGBA8888);
                for (int y = 0; y < px.getHeight(); y++) flipped.drawPixmap(px, 0, y, px.getWidth(), 1, 0, px.getHeight() - 1 - y, px.getWidth(), 1);
                PixmapIO.writePNG(Gdx.files.absolute(debug.shotPrefix + "_" + shotIndex + ".png"), flipped);
                Gdx.app.log("shot", debug.shotPrefix + "_" + shotIndex + ".png  phase=" + (race != null ? race.phase : "menu")
                    + " fps=" + Gdx.graphics.getFramesPerSecond());
                px.dispose();
                flipped.dispose();
                shotIndex++;
            }
        }
        if (debug.quitAfter > 0 && wallTime > debug.quitAfter) Gdx.app.exit();
    }

    @Override
    public void resize(int width, int height) {
        hud.resize(width, height);
    }

    @Override
    public void dispose() {
        synth.dispose();
        if (race != null) race.dispose();
        for (BikeView v : views) v.dispose();
        marshal.dispose();
        effects.dispose();
        stadium.dispose();
        textures.dispose();
        lighting.dispose();
        bloom.dispose();
        hud.dispose();
        Pools.get(Vector3.class).clear();
    }
}
