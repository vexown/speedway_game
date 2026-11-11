package com.broadside.speedway.lwjgl3;

import com.badlogic.gdx.math.MathUtils;
import com.badlogic.gdx.math.Vector3;
import com.broadside.speedway.*;

/**
 * Headless simulation (no window/GPU): ./gradlew sim  [-PappArgs="race 3" | "handling 3" | "difficulty"]
 * - race:       AI-only races, lap times and incidents
 * - handling:   keyboard-style bots on the player's bike, Classic vs Assisted steering
 * - difficulty: AI finishing times per difficulty level
 */
public class SimMain {
    public static void main(String[] args) {
        PhysicsWorld.initBullet();
        String mode = args.length > 0 ? args[0] : "all";
        int runs = args.length > 1 ? Integer.parseInt(args[1]) : 3;
        if (mode.equals("race") || mode.equals("all")) races(runs);
        if (mode.equals("handling") || mode.equals("all")) handling(runs);
        if (mode.equals("difficulty") || mode.equals("all")) difficulty();
    }

    static void races(int runs) {
        int[][] g = {{0, 1, 2, 3}, {2, 3, 0, 1}, {1, 0, 3, 2}};
        for (int r = 0; r < runs; r++) {
            Race race = new Race(g[r % 3], Track.LAPS, 1f, true);
            float t = 0, minD = 99, maxD = -99, maxSpeed = 0;
            StringBuilder crashes = new StringBuilder();
            while (race.phase != Race.Phase.DONE && t < 300) {
                race.step(1 / 60f);
                t += 1 / 60f;
                for (Race.GameEvent e : race.gameEvents)
                    if (e.type.equals("crash")) crashes.append(String.format("%s@%.1fs:%s | ", e.bike.name, race.time, e.reason));
                race.gameEvents.clear();
                for (Bike b : race.bikes) if (b.state == Bike.State.RIDING && race.phase == Race.Phase.RACING) {
                    minD = Math.min(minD, b.d); maxD = Math.max(maxD, b.d); maxSpeed = Math.max(maxSpeed, b.speed);
                }
            }
            System.out.printf("--- race %d: race time %.1fs, track %.0fm%n", r + 1, race.time, Track.LENGTH);
            int i = 1;
            for (Bike b : race.standings())
                System.out.printf("  %d. %-11s %s falls %d%n", i++, b.name,
                    b.finished ? String.format("%.2fs", b.finishTime) : String.format("DNF %.0fm", b.progress), b.crashCount);
            System.out.printf("  d range [%.2f, %.2f] maxSpeed %.0f km/h%n  crashes: %s%n", minD, maxD, maxSpeed * 3.6f,
                crashes.length() == 0 ? "none" : crashes);
            race.dispose();
        }
    }

    /** Keyboard-style bot: on/off keys with the same smoothing as the game's input. */
    static String rideRace(boolean assist, boolean careful) {
        Race race = new Race(new int[]{0, 1, 2, 3}, Track.LAPS, 0.9f, false);
        Bike p = race.player;
        p.assist = assist;
        float thr = 0, st = 0, dT = 0, t = 0;
        boolean up = false, kl = false, kr = false;
        int falls = 0, fence = 0;
        Vector3 pos = new Vector3(), vel = new Vector3();
        Track.Proj proj = new Track.Proj();
        Track.Point tp = new Track.Point();
        float dt = 1 / 60f;
        while (race.phase != Race.Phase.DONE && t < 220) {
            t += dt; dT -= dt;
            if (dT <= 0) {
                dT = 0.15f;
                p.position(pos); p.velocity(vel);
                float sp = (float) Math.hypot(vel.x, vel.z);
                Track.project(pos.x, pos.z, proj);
                Track.point(proj.s + 9 + sp * 0.45f, 4.5f, tp);
                float want = (float) Math.atan2(tp.x - pos.x, tp.z - pos.z);
                float velAng = sp > 2 ? (float) Math.atan2(vel.x, vel.z) : p.heading();
                float err = Track.wrapAngle(want - velAng);
                kl = err > 0.05f; kr = err < -0.12f;
                float S0 = Track.STRAIGHT, B0 = Track.BEND_LENGTH, s0 = proj.s;
                float toBend = s0 < S0 ? S0 - s0 : (s0 >= S0 + B0 && s0 < 2 * S0 + B0) ? 2 * S0 + B0 - s0 : 0;
                boolean u = true;
                if (careful) u = !((toBend > 0 && toBend < 20 && sp > 21) || (toBend == 0 && sp > 20.5f && proj.d > 7));
                if (proj.d > Track.WIDTH - 2.2f && sp > 16) u = false;
                up = race.phase == Race.Phase.RACING && u;
            }
            float tT = up ? 1 : 0, sT = (kl ? 1 : 0) - (kr ? 1 : 0);
            thr += Math.signum(tT - thr) * Math.min(Math.abs(tT - thr), (tT > thr ? 6 : 9) * dt);
            float sRate = Math.abs(sT) < Math.abs(st) || Math.signum(sT) != Math.signum(st) ? 9 : 5;
            st += Math.signum(sT - st) * Math.min(Math.abs(sT - st), sRate * dt);
            p.throttleIn = thr; p.steerIn = st;
            race.step(dt);
            for (Race.GameEvent e : race.gameEvents) {
                if (e.bike != p) continue;
                if (e.type.equals("crash")) falls++;
                if (e.type.equals("fence") && e.impact > 3) fence++;
            }
            race.gameEvents.clear();
        }
        String res = String.format("%s P%d falls%d fence%d", p.finished ? String.format("%.1fs", p.finishTime) : "DNF",
            race.standings().indexOf(p, true) + 1, falls, fence);
        race.dispose();
        return res;
    }

    static void handling(int runs) {
        for (boolean careful : new boolean[]{false, true})
            for (boolean assist : new boolean[]{false, true}) {
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < runs; i++) sb.append(rideRace(assist, careful)).append(" | ");
                System.out.printf("%-7s %s: %s%n", careful ? "careful" : "naive", assist ? "ASSISTED" : "classic ", sb);
            }
    }

    static void difficulty() {
        for (float diff : new float[]{0.9f, 1.0f, 1.07f}) {
            StringBuilder sb = new StringBuilder();
            int falls = 0;
            for (int r = 0; r < 3; r++) {
                Race race = new Race(new int[]{0, 1, 2, 3}, Track.LAPS, diff, false);
                // Park the player far away so it doesn't interfere.
                race.pw.world.removeRigidBody(race.player.body);
                float t = 0;
                while (race.phase != Race.Phase.DONE && t < 260) {
                    race.player.progress = -1e6f;
                    race.step(1 / 60f); t += 1 / 60f; race.gameEvents.clear();
                    if (race.phase == Race.Phase.RACING) {
                        boolean aiDone = true;
                        for (Bike b : race.bikes) if (!b.isPlayer) aiDone &= b.finished;
                        if (aiDone) break;
                    }
                }
                for (Bike b : race.bikes) if (!b.isPlayer) {
                    sb.append(b.finished ? String.format("%.1f", b.finishTime) : "DNF").append(',');
                    falls += b.crashCount;
                }
                race.pw.world.addRigidBody(race.player.body);
                race.dispose();
            }
            System.out.printf("difficulty %.2f times %s falls %d%n", diff, sb, falls);
        }
        MathUtils.random();
    }
}
