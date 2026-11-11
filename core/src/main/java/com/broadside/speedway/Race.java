package com.broadside.speedway;

import com.badlogic.gdx.math.MathUtils;
import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.utils.Array;
import com.badlogic.gdx.utils.Disposable;

/** Race manager: tapes start procedure, laps, positions, falls, results. */
public class Race implements Disposable {
    public enum Phase { INTRO, READY, RACING, DONE }

    public static final class RiderDef {
        public final String name, helmet; public final int color; public final boolean player;
        public final AIDriver.Persona persona;
        RiderDef(String name, int color, String helmet, boolean player, AIDriver.Persona persona) {
            this.name = name; this.color = color; this.helmet = helmet; this.player = player; this.persona = persona;
        }
    }

    public static final RiderDef[] RIDERS = {
        new RiderDef("You", 0xd62828, "red", true, null),
        new RiderDef("K. Varga", 0x1f6feb, "blue", false, new AIDriver.Persona(0.28f, 0.4f, 1.0f, 0.14f, 0.25f)),
        new RiderDef("M. Holm", 0xf2f2f2, "white", false, new AIDriver.Persona(0.5f, 0.55f, 1.05f, 0.18f, 0.35f)),
        new RiderDef("T. Brennan", 0xf5c518, "yellow", false, new AIDriver.Persona(0.38f, 0.35f, 0.96f, 0.1f, 0.3f)),
    };
    public static final int[] POINTS = {3, 2, 1, 0};
    /** Gate allocation for a 5-heat meeting (row = heat, value per rider = gate 0..3); heat 5 by points. */
    public static final int[][] HEAT_GATES = {{0, 1, 2, 3}, {2, 3, 0, 1}, {1, 0, 3, 2}, {3, 2, 1, 0}};

    public static float gateD(int gate) { return 1.7f + gate * 3.0f; }

    public static final class Message {
        public final String text, kind; public final float duration; public float t;
        Message(String text, String kind, float duration) { this.text = text; this.kind = kind; this.duration = duration; }
    }

    /** Game-level event for audio/fx. */
    public static final class GameEvent {
        public final String type; public final Bike bike; public final float impact; public final String reason;
        GameEvent(String type, Bike bike, float impact, String reason) {
            this.type = type; this.bike = bike; this.impact = impact; this.reason = reason;
        }
    }

    public final PhysicsWorld pw;
    public final Array<Bike> bikes = new Array<>();
    public final Array<AIDriver> ais = new Array<>();
    public final Bike player;
    public final int laps;
    public final float raceDistance;
    public Phase phase = Phase.INTRO;
    public float phaseTime, time;
    public final Array<Bike> finishOrder = new Array<>();
    public final Array<Message> messages = new Array<>();
    public final Array<GameEvent> gameEvents = new Array<>();
    public boolean tapesUp;
    public float tapeHeight;
    public int tapeOffences;
    private float readyDelay, tapeTouchTimer, accum;
    private final int[] gates;
    private final float[] handicap = new float[4];
    private final Vector3[] preVel = new Vector3[4];
    private final Vector3 zero = new Vector3();
    private final Vector3 tmp = new Vector3();

    public Race(int[] gates, int laps, float difficulty, boolean aiOnly) {
        pw = new PhysicsWorld();
        this.laps = laps;
        this.raceDistance = laps * Track.LENGTH;
        this.gates = gates;
        Bike pl = null;
        int lvl = difficulty < 0.95f ? 0 : difficulty < 1.05f ? 1 : 2;
        for (int i = 0; i < RIDERS.length; i++) {
            RiderDef r = RIDERS[i];
            boolean isPlayer = r.player && !aiOnly;
            Bike b = new Bike(pw, i, r.name, r.color, r.helmet, isPlayer);
            bikes.add(b);
            preVel[i] = new Vector3();
            if (isPlayer) pl = b;
            else {
                AIDriver.Persona base = r.persona != null ? r.persona : RIDERS[1].persona;
                AIDriver.Persona persona = new AIDriver.Persona(base.bendLine, base.straightLine, base.cornerGrip, base.reaction, base.wobble);
                b.gripMul = new float[]{0.84f, 0.93f, 1.0f}[lvl];
                b.powerMul = new float[]{0.9f, 0.96f, 1.0f}[lvl];
                if (aiOnly) { b.gripMul = 1; b.powerMul = 1; }
                ais.add(new AIDriver(b, persona));
            }
        }
        player = pl;
        lineUp();
    }

    private void lineUp() {
        for (int i = 0; i < bikes.size; i++) {
            Bike b = bikes.get(i);
            float back = handicap[i];
            b.placeAt(Track.START_S - 1.3f - back, gateD(gates[i]), -1.3f - back);
            b.state = Bike.State.HELD;
            b.gate = gates[i];
        }
        phase = Phase.INTRO;
        phaseTime = 0;
        tapesUp = false;
        tapeHeight = 0;
        readyDelay = 1.6f + MathUtils.random() * 2.2f;
        tapeTouchTimer = 0;
    }

    public void say(String text, String kind, float duration) {
        messages.add(new Message(text, kind, duration));
    }

    public void step(float frameDt) {
        accum += Math.min(frameDt, 0.1f);
        int steps = 0;
        while (accum >= Phys.DT && steps < 12) {
            fixedStep(Phys.DT);
            accum -= Phys.DT;
            steps++;
        }
        for (int i = messages.size - 1; i >= 0; i--) {
            Message m = messages.get(i);
            m.t += frameDt;
            if (m.t >= m.duration) messages.removeIndex(i);
        }
    }

    private final PhysicsWorld.ImpactListener impacts = (tagA, tagB, impact, point) -> {
        handle(tagA, tagB, impact);
        handle(tagB, tagA, impact);
    };

    private void handle(int self, int other, float impact) {
        if (self < Phys.TAG_BIKE || self >= Phys.TAG_RAGDOLL) return;
        Bike b = bikes.get(self - Phys.TAG_BIKE);
        if (other == Phys.TAG_FENCE) b.onImpact(true, null, false, impact);
        else if (other >= Phys.TAG_BIKE && other < Phys.TAG_RAGDOLL) b.onImpact(false, bikes.get(other - Phys.TAG_BIKE), false, impact);
        else if (other >= Phys.TAG_RAGDOLL) b.onImpact(false, null, true, impact);
    }

    private final PhysicsWorld.VelocityLookup velocities = tag -> {
        if (tag >= Phys.TAG_BIKE && tag < Phys.TAG_RAGDOLL) return preVel[tag - Phys.TAG_BIKE];
        if (tag >= Phys.TAG_RAGDOLL) {
            Bike b = bikes.get(tag - Phys.TAG_RAGDOLL);
            if (b.ragdoll != null && b.ragdoll.torso != null) return tmp.set(b.ragdoll.torso.body.getLinearVelocity());
        }
        return zero;
    };

    private void fixedStep(float dt) {
        phaseTime += dt;
        if (phase == Phase.INTRO && phaseTime > 1.8f) {
            phase = Phase.READY;
            phaseTime = 0;
            say(player != null ? "Riders ready... wait for the tapes!" : "Riders ready...", "info", 1.8f);
        } else if (phase == Phase.READY) {
            if (player != null && player.throttleIn > 0.7f) tapeTouchTimer += dt;
            else tapeTouchTimer = Math.max(0, tapeTouchTimer - dt * 2);
            if (player != null && tapeTouchTimer > 0.35f) {
                tapeOffences++;
                handicap[player.id] += 15;
                say("TAPE TOUCHING! 15 m handicap", "warn", 3);
                lineUp();
                return;
            }
            if (phaseTime > readyDelay) {
                phase = Phase.RACING;
                phaseTime = 0;
                tapesUp = true;
                time = 0;
                for (Bike b : bikes) b.release();
                gameEvents.add(new GameEvent("tapes", null, 0, null));
            }
        }
        if (tapesUp) tapeHeight = Math.min(1, tapeHeight + dt * 4);
        if (phase == Phase.RACING || phase == Phase.DONE) time += dt;

        for (AIDriver ai : ais) ai.update(dt, bikes, this, true);
        for (Bike b : bikes) b.update(dt);
        for (int i = 0; i < bikes.size; i++) preVel[i].set(bikes.get(i).body.getLinearVelocity());
        pw.step(dt, velocities, impacts);

        for (Bike b : bikes) {
            b.postStep();
            if (b.state == Bike.State.CRASHED) {
                if (b.crashTimer <= 0) b.remount();
            }
            if (b.state == Bike.State.RIDING && tapesUp && !b.finished) {
                b.stuckTime = b.speed < 1.5f ? b.stuckTime + dt : 0;
                if (b.stuckTime > 2.5f) {
                    b.stuckTime = 0;
                    b.remount();
                    if (b.isPlayer) say("Back on track", "info", 1.5f);
                }
            }
            if (!b.finished && b.state != Bike.State.HELD && b.progress >= raceDistance) {
                b.finished = true;
                b.finishTime = time;
                finishOrder.add(b);
                int place = finishOrder.size;
                if (b.isPlayer) say(place == 1 ? "YOU WIN THE HEAT!" : "You finished " + ordinal(place), "big", 4);
            }
            b.lap = MathUtils.clamp((int) Math.floor(Math.max(0, b.progress) / Track.LENGTH) + 1, 0, laps);
            for (Bike.Event e : b.events) {
                if (e.type.equals("crash")) say((b.isPlayer ? "You" : b.name) + " " + e.reason + "!", b.isPlayer ? "warn" : "info", 2.2f);
                gameEvents.add(new GameEvent(e.type, b, e.impact, e.reason));
            }
            b.events.clear();
        }

        if (phase == Phase.RACING) {
            boolean allDone = true;
            for (Bike b : bikes) allDone &= b.finished;
            boolean playerDone = player == null || player.finished;
            Bike last = finishOrder.size > 0 ? finishOrder.peek() : null;
            if (allDone || (playerDone && last != null && time - last.finishTime > 15) || time > 240) {
                phase = Phase.DONE;
                phaseTime = 0;
            }
        }
    }

    /** Running order: finishers first, then by distance. */
    public Array<Bike> standings() {
        Array<Bike> out = new Array<>(finishOrder);
        Array<Bike> rest = new Array<>();
        for (Bike b : bikes) if (!b.finished) rest.add(b);
        rest.sort((a, b) -> Float.compare(b.progress, a.progress));
        out.addAll(rest);
        return out;
    }

    public static String ordinal(int n) {
        return n + (n == 1 ? "st" : n == 2 ? "nd" : n == 3 ? "rd" : "th");
    }

    @Override
    public void dispose() {
        for (Bike b : bikes) b.dispose();
        pw.dispose();
    }
}
