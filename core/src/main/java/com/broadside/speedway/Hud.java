package com.broadside.speedway;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Input;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.g2d.BitmapFont;
import com.badlogic.gdx.graphics.g2d.GlyphLayout;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.badlogic.gdx.graphics.g2d.freetype.FreeTypeFontGenerator;
import com.badlogic.gdx.graphics.glutils.ShapeRenderer;
import com.badlogic.gdx.math.Matrix4;
import com.badlogic.gdx.math.Rectangle;
import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.utils.Align;
import com.badlogic.gdx.utils.Array;
import com.badlogic.gdx.utils.Disposable;
import com.broadside.speedway.render.BikeView;

/** HUD (position, laps, timer, minimap, order, speed, meters, messages) and immediate-mode menus. */
public class Hud implements Disposable {
    private final SpeedwayGame game;
    private final SpriteBatch batch = new SpriteBatch();
    private final ShapeRenderer shapes = new ShapeRenderer();
    private final BitmapFont f14, f18, f24, f34, f60, fTitle;
    private final GlyphLayout layout = new GlyphLayout();
    private float W, H, S = 1;
    private static final Color ACCENT = new Color(0xffb703ff), FG = new Color(0xf4f1eaff),
        PANEL = new Color(0.047f, 0.055f, 0.086f, 0.88f), LINE = new Color(1, 1, 1, 0.14f), WARN = new Color(0xff6b6bff);

    // immediate-mode UI state
    private int focus;
    private int widgetCount;
    private float clickX = -1, clickY = -1, hoverX = -1, hoverY = -1;
    private boolean enterPressed;
    private int leftRight;
    private Array<Bike> results;
    private String resultsTitle = "";

    public Hud(SpeedwayGame game) {
        this.game = game;
        FreeTypeFontGenerator bold = new FreeTypeFontGenerator(Gdx.files.internal("fonts/DejaVuSans-Bold.ttf"));
        FreeTypeFontGenerator reg = new FreeTypeFontGenerator(Gdx.files.internal("fonts/DejaVuSans.ttf"));
        f14 = font(reg, 14);
        f18 = font(reg, 18);
        f24 = font(bold, 24);
        f34 = font(bold, 34);
        f60 = font(bold, 60);
        fTitle = font(bold, 72);
        bold.dispose();
        reg.dispose();
        resize(Gdx.graphics.getWidth(), Gdx.graphics.getHeight());
    }

    private static BitmapFont font(FreeTypeFontGenerator g, int size) {
        FreeTypeFontGenerator.FreeTypeFontParameter p = new FreeTypeFontGenerator.FreeTypeFontParameter();
        p.size = size;
        p.minFilter = com.badlogic.gdx.graphics.Texture.TextureFilter.Linear;
        p.magFilter = com.badlogic.gdx.graphics.Texture.TextureFilter.Linear;
        p.shadowOffsetX = 0;
        p.shadowOffsetY = Math.max(1, size / 14);
        p.shadowColor = new Color(0, 0, 0, 0.75f);
        return g.generateFont(p);
    }

    public void resize(int w, int h) {
        W = w; H = h;
        S = Math.max(0.7f, Math.min(1.6f, h / 720f));
        Matrix4 proj = new Matrix4().setToOrtho2D(0, 0, w / S, h / S);
        batch.setProjectionMatrix(proj);
        shapes.setProjectionMatrix(proj);
        W = w / S; H = h / S;
    }

    // ------------------------------------------------------------------ input hooks
    public void click(int x, int y) { clickX = x / S; clickY = (Gdx.graphics.getHeight() - y) / S; }
    public void hover(int x, int y) { hoverX = x / S; hoverY = (Gdx.graphics.getHeight() - y) / S; }

    public boolean key(int key) {
        if (game.mode == SpeedwayGame.Mode.RACE) return false;
        switch (key) {
            case Input.Keys.UP: case Input.Keys.W: focus = Math.max(0, focus - 1); return true;
            case Input.Keys.DOWN: case Input.Keys.S: focus = Math.min(widgetCount - 1, focus + 1); return true;
            case Input.Keys.LEFT: case Input.Keys.A: leftRight = -1; return true;
            case Input.Keys.RIGHT: case Input.Keys.D: leftRight = 1; return true;
            case Input.Keys.ENTER: case Input.Keys.SPACE: enterPressed = true; return true;
            default: return false;
        }
    }

    public void results(Array<Bike> order) {
        results = order;
        focus = 0;
        SpeedwayGame.Meeting m = game.meeting;
        resultsTitle = m != null ? "Heat " + (m.heat + 1) + " result" : "Heat result";
        if (m != null && m.heat >= 4) {
            int best = 0;
            for (int i = 1; i < 4; i++) if (m.points[i] > m.points[best]) best = i;
            resultsTitle = Race.RIDERS[best].player ? "You win the meeting!" : "Meeting won by " + Race.RIDERS[best].name;
        }
    }

    // ------------------------------------------------------------------ drawing helpers
    private void text(BitmapFont f, String s, float x, float y, Color c, int align, float width) {
        f.setColor(c);
        f.draw(batch, s, x, y, width, align, false);
    }

    private float textWidth(BitmapFont f, String s) { layout.setText(f, s); return layout.width; }

    private void beginShapes() {
        Gdx.gl.glEnable(GL20.GL_BLEND);
        Gdx.gl.glBlendFunc(GL20.GL_SRC_ALPHA, GL20.GL_ONE_MINUS_SRC_ALPHA);
        shapes.begin(ShapeRenderer.ShapeType.Filled);
    }

    private void panel(float x, float y, float w, float h) {
        shapes.setColor(PANEL);
        shapes.rect(x, y, w, h);
        shapes.setColor(LINE);
        shapes.rect(x, y, w, 1); shapes.rect(x, y + h - 1, w, 1); shapes.rect(x, y, 1, h); shapes.rect(x + w - 1, y, 1, h);
    }

    private boolean inside(float px, float py, float x, float y, float w, float h) {
        return px >= x && px <= x + w && py >= y && py <= y + h;
    }

    /** Immediate-mode button. Returns true when activated by click or Enter while focused. */
    private boolean button(String label, float x, float y, float w, float h, boolean primary) {
        int id = widgetCount++;
        boolean hovered = inside(hoverX, hoverY, x, y, w, h);
        if (hovered) focus = id;
        boolean focused = focus == id;
        beginShapes();
        if (primary) shapes.setColor(focused ? new Color(0xffc933ff) : new Color(0xfb8500ff));
        else shapes.setColor(focused ? new Color(0x3d4b6eff) : new Color(0x263046ff));
        shapes.rect(x, y, w, h);
        if (focused) { shapes.setColor(FG); shapes.rect(x, y, w, 2); }
        shapes.end();
        batch.begin();
        text(f24, label, x, y + h / 2 + f24.getCapHeight() / 2, primary ? new Color(0x1b1200ff) : FG, Align.center, w);
        batch.end();
        boolean clicked = inside(clickX, clickY, x, y, w, h);
        if (clicked) { focus = id; clickX = -1; }
        if (focused && enterPressed) { enterPressed = false; return true; }
        return clicked;
    }

    /** Option row with < value >. Returns -1/0/+1 change. */
    private int option(String label, String value, float x, float y, float w, float h) {
        int id = widgetCount++;
        boolean hovered = inside(hoverX, hoverY, x, y, w, h);
        if (hovered) focus = id;
        boolean focused = focus == id;
        beginShapes();
        shapes.setColor(focused ? new Color(0x2e3a58ff) : new Color(0x1c2436ff));
        shapes.rect(x, y, w, h);
        shapes.end();
        batch.begin();
        text(f18, label, x + 14, y + h / 2 + f18.getCapHeight() / 2, FG, Align.left, w);
        text(f18, "<  " + value + "  >", x, y + h / 2 + f18.getCapHeight() / 2, focused ? ACCENT : FG, Align.right, w - 14);
        batch.end();
        int change = 0;
        if (inside(clickX, clickY, x, y, w, h)) { change = clickX > x + w / 2 ? 1 : -1; clickX = -1; focus = id; }
        if (focused && leftRight != 0) { change = leftRight; leftRight = 0; }
        if (focused && enterPressed) { change = 1; enterPressed = false; }
        return change;
    }

    // ------------------------------------------------------------------ main draw
    public void render(float dt) {
        widgetCount = 0;
        switch (game.mode) {
            case MENU: menu(); break;
            case RACE: hud(); break;
            case PAUSED: hud(); pause(); break;
            case RESULTS: hud(); resultsScreen(); break;
        }
        if (focus >= widgetCount && widgetCount > 0) focus = widgetCount - 1;
        clickX = -1;
        enterPressed = false;
        leftRight = 0;
    }

    private void dim() {
        beginShapes();
        shapes.setColor(0.02f, 0.025f, 0.04f, 0.55f);
        shapes.rect(0, 0, W, H);
        shapes.end();
    }

    private void menu() {
        float pw = Math.min(760, W - 32), ph = Math.min(640, H - 32);
        float px = (W - pw) / 2, py = (H - ph) / 2;
        dim();
        beginShapes(); panel(px, py, pw, ph); shapes.end();
        float top = py + ph - 28;
        batch.begin();
        text(fTitle, "BROADSIDE", px + 34, top, ACCENT, Align.left, pw);
        text(f34, "S P E E D W A Y", px + 38, top - 86, FG, Align.left, pw);
        text(f18, "Four riders. Four laps. No brakes.  (libGDX edition)", px + 36, top - 138, new Color(1, 1, 1, 0.75f), Align.left, pw);
        batch.end();
        float by = top - 210;
        if (button("Quick Heat", px + 34, by, 220, 52, true)) game.startGame(false);
        if (button("Meeting - 5 Heats", px + 270, by, 280, 52, true)) game.startGame(true);
        float ow = (pw - 68 - 14) / 2, oh = 40;
        float oy = by - 60;
        int c = option("Difficulty", SpeedwayGame.DIFFICULTY_NAMES[game.difficultyIdx], px + 34, oy, ow, oh);
        game.difficultyIdx = (game.difficultyIdx + c + 3) % 3;
        c = option("Laps", String.valueOf(SpeedwayGame.LAP_OPTIONS[game.lapsIdx]), px + 34 + ow + 14, oy, ow, oh);
        game.lapsIdx = (game.lapsIdx + c + 3) % 3;
        c = option("Steering", game.assisted ? "Assisted" : "Classic", px + 34, oy - 50, ow, oh);
        if (c != 0) game.assisted = !game.assisted;
        c = option("Shadows", game.shadowsOn ? "On" : "Off", px + 34 + ow + 14, oy - 50, ow, oh);
        if (c != 0) game.shadowsOn = !game.shadowsOn;
        c = option("Bloom", game.bloomOn ? "On" : "Off", px + 34, oy - 100, ow, oh);
        if (c != 0) game.bloomOn = !game.bloomOn;
        String[] help = {
            "UP / W  throttle - wait for the tapes! Full throttle at the tapes = tape touching (15 m handicap).",
            "LEFT / A  turn in. Racing is anti-clockwise: all bends are left-handers. Assisted steering",
            "   holds a controlled broadside - hold LEFT (and throttle) to slide more; let go to straighten.",
            "RIGHT / D  straighten.  SHIFT  feather the throttle.  No brakes - roll off to scrub speed.",
            "C camera   M mute   ESC pause   R restart heat.   Menus: arrows + ENTER, or the mouse.",
        };
        batch.begin();
        float hy = oy - 130;
        for (String h : help) { text(f14, h, px + 36, hy, new Color(1, 1, 1, 0.82f), Align.left, pw - 60); hy -= 24; }
        batch.end();
    }

    private void pause() {
        dim();
        float pw = 420, ph = 250, px = (W - pw) / 2, py = (H - ph) / 2;
        beginShapes(); panel(px, py, pw, ph); shapes.end();
        batch.begin(); text(f34, "Paused", px, py + ph - 26, FG, Align.center, pw); batch.end();
        if (button("Resume", px + 40, py + 110, pw - 80, 48, true)) game.togglePause();
        if (button("Restart heat", px + 40, py + 60, (pw - 90) / 2, 40, false)) game.startHeat();
        if (button("Quit to menu", px + 50 + (pw - 90) / 2, py + 60, (pw - 90) / 2, 40, false)) game.toMenu();
    }

    private void resultsScreen() {
        if (results == null) return;
        SpeedwayGame.Meeting m = game.meeting;
        dim();
        float pw = Math.min(760, W - 32), ph = m != null ? 560 : 360;
        float px = (W - pw) / 2, py = (H - ph) / 2;
        beginShapes(); panel(px, py, pw, ph); shapes.end();
        float y = py + ph - 28;
        batch.begin();
        text(f34, resultsTitle, px + 30, y, FG, Align.left, pw);
        y -= 66;
        float[] cols = {30, 100, 330, 410, 560, 650};
        String[] hdr = {"POS", "RIDER", "GATE", "TIME", "FALLS", "PTS"};
        for (int i = 0; i < hdr.length; i++) text(f14, hdr[i], px + cols[i], y, new Color(1, 1, 1, 0.6f), Align.left, 200);
        y -= 30;
        Race race = game.race;
        float winnerTime = results.first().finished ? results.first().finishTime : 0;
        for (int i = 0; i < results.size; i++) {
            Bike b = results.get(i);
            Color c = b.isPlayer ? ACCENT : FG;
            String time = b.finished ? (i == 0 ? fmtTime(b.finishTime) : String.format("+%.2fs", b.finishTime - winnerTime))
                : Math.max(0, (int) (b.progress / Track.LENGTH)) + " laps";
            String[] row = {Race.ordinal(i + 1), b.name, String.valueOf(b.gate + 1), time, String.valueOf(b.crashCount), String.valueOf(Race.POINTS[i])};
            for (int k = 0; k < row.length; k++) text(f18, row[k], px + cols[k] + (k == 1 ? 24 : 0), y, c, Align.left, 220);
            y -= 32;
        }
        batch.end();
        beginShapes();
        float yy = y + 32 * results.size + 14;
        for (int i = 0; i < results.size; i++) {
            shapes.setColor(MeshColor(BikeView.helmetColor(results.get(i).helmet)));
            shapes.circle(px + 112, yy - i * 32 - 22, 7, 16);
        }
        shapes.end();
        if (m != null) {
            batch.begin();
            y -= 16;
            text(f24, "Meeting standings", px + 30, y, FG, Align.left, pw);
            y -= 40;
            String[] mh = {"#", "RIDER", "H1", "H2", "H3", "H4", "H5", "TOTAL"};
            float[] mc = {30, 70, 300, 360, 420, 480, 540, 620};
            for (int i = 0; i < mh.length; i++) text(f14, mh[i], px + mc[i], y, new Color(1, 1, 1, 0.6f), Align.left, 100);
            y -= 28;
            Integer[] ranking = {0, 1, 2, 3};
            java.util.Arrays.sort(ranking, (a, b) -> m.points[b] - m.points[a]);
            for (int r = 0; r < 4; r++) {
                int id = ranking[r];
                Color c = Race.RIDERS[id].player ? ACCENT : FG;
                text(f18, String.valueOf(r + 1), px + mc[0], y, c, Align.left, 60);
                text(f18, Race.RIDERS[id].name, px + mc[1], y, c, Align.left, 220);
                for (int h = 0; h < 5; h++) {
                    String cell = "-";
                    for (int k = 0; k < m.history.size; k++) if (m.historyHeat.get(k) == h) {
                        int[] ord = m.history.get(k);
                        for (int q = 0; q < 4; q++) if (ord[q] == id) cell = String.valueOf(Race.POINTS[q]);
                    }
                    text(f18, cell, px + mc[2 + h], y, c, Align.left, 60);
                }
                text(f24, String.valueOf(m.points[id]), px + mc[7], y + 2, c, Align.left, 80);
                y -= 30;
            }
            batch.end();
        }
        boolean done = m != null && m.heat >= 4;
        String next = m == null ? "Race again" : done ? "Finish" : "Next heat";
        if (button(next, px + 30, py + 24, 200, 48, true)) game.nextHeat();
        if (button("Rerun heat", px + 246, py + 24, 170, 48, false)) game.rerunHeat();
        if (button("Menu", px + 432, py + 24, 120, 48, false)) game.toMenu();
    }

    private static Color MeshColor(int rgb) { return com.broadside.speedway.render.MeshKit.rgbColor(rgb); }

    static String fmtTime(float t) {
        int m = (int) (t / 60);
        float s = t - m * 60;
        return String.format("%d:%05.2f", m, s);
    }

    // ------------------------------------------------------------------ race HUD
    private void hud() {
        Race race = game.race;
        if (race == null) return;
        Bike p = race.player;
        Array<Bike> order = race.standings();
        int pos = order.indexOf(p, true) + 1;
        batch.begin();
        float x = 22, y = H - 18;
        text(f60, String.valueOf(pos), x, y, FG, Align.left, 200);
        float pw = textWidth(f60, String.valueOf(pos));
        text(f24, Race.ordinal(pos).substring(String.valueOf(pos).length()), x + pw + 4, y - 4, FG, Align.left, 100);
        int lap = p.finished ? race.laps : Math.max(1, p.lap);
        String lapStr = p.finished ? "FINISHED" : lap == race.laps ? "LAST LAP" : "LAP " + lap + "/" + race.laps;
        text(f24, lapStr, x, y - 78, ACCENT, Align.left, 300);
        text(f18, fmtTime(race.time), x, y - 112, FG, Align.left, 300);
        String heat = game.meeting != null ? "HEAT " + (game.meeting.heat + 1) + " OF 5  -  GATE " + (p.gate + 1) : "QUICK HEAT  -  GATE " + (p.gate + 1);
        text(f14, heat, x, y - 140, new Color(1, 1, 1, 0.75f), Align.left, 400);
        batch.end();

        // minimap + order panel
        float mw = 220, mh = 140, mx = W - mw - 18, my = H - mh - 16;
        beginShapes();
        shapes.setColor(0, 0, 0, 0.35f);
        shapes.rect(mx, my, mw, mh);
        float sc = Math.min((mw - 24) / (Track.STRAIGHT + 2 * (Track.INNER_RADIUS + Track.WIDTH + 1)),
            (mh - 20) / (2 * (Track.INNER_RADIUS + Track.WIDTH + 1)));
        float cx = mx + mw / 2, cy = my + mh / 2;
        shapes.setColor(new Color(0x8a5530ff));
        Track.Point a = new Track.Point(), b = new Track.Point();
        for (int i = 0; i < 120; i++) {
            Track.point(i / 120f * Track.LENGTH, Track.WIDTH / 2, a);
            Track.point((i + 1) / 120f * Track.LENGTH, Track.WIDTH / 2, b);
            shapes.rectLine(cx + a.x * sc, cy - a.z * sc, cx + b.x * sc, cy - b.z * sc, Track.WIDTH * sc);
        }
        shapes.setColor(Color.WHITE);
        shapes.rectLine(cx, cy - Track.INNER_RADIUS * sc, cx, cy - (Track.INNER_RADIUS + Track.WIDTH) * sc, 2);
        Vector3 bp = new Vector3();
        for (Bike bk : race.bikes) {
            bk.position(bp);
            float r = bk.isPlayer ? 6 : 4.5f;
            shapes.setColor(bk.isPlayer ? ACCENT : Color.BLACK);
            shapes.circle(cx + bp.x * sc, cy - bp.z * sc, r + 1.5f, 16);
            shapes.setColor(MeshColor(BikeView.helmetColor(bk.helmet)));
            shapes.circle(cx + bp.x * sc, cy - bp.z * sc, r, 16);
        }
        float ow = 220, oh = 8 + order.size * 26, ox = W - ow - 18, oy = my - oh - 12;
        shapes.setColor(0, 0, 0, 0.35f);
        shapes.rect(ox, oy, ow, oh);
        for (int i = 0; i < order.size; i++) {
            shapes.setColor(MeshColor(BikeView.helmetColor(order.get(i).helmet)));
            shapes.circle(ox + 16, oy + oh - 17 - i * 26, 7, 14);
        }
        // bottom speed panel
        float bw = 520, bh = 64, bx = (W - bw) / 2, by = 20;
        shapes.setColor(0, 0, 0, 0.35f);
        shapes.rect(bx, by, bw, bh);
        shapes.setColor(1, 1, 1, 0.12f);
        shapes.rect(bx + 180, by + 18, 150, 10);
        shapes.rect(bx + 350, by + 18, 150, 10);
        float thr = p.throttleIn;
        shapes.rect(bx + 180, by + 18, 150 * thr, 10, new Color(0x8ecae6ff), new Color(0xfb5607ff), new Color(0xfb5607ff), new Color(0x8ecae6ff));
        float sl = Math.min(1, Math.abs(p.drift) / 1.3f);
        shapes.rect(bx + 350, by + 18, 150 * sl, 10, new Color(0x52b788ff), new Color(0xe63946ff), new Color(0xe63946ff), new Color(0x52b788ff));
        shapes.end();

        batch.begin();
        Bike leader = order.first();
        for (int i = 0; i < order.size; i++) {
            Bike bk = order.get(i);
            Color c = bk.isPlayer ? ACCENT : FG;
            float ty = oy + oh - 10 - i * 26;
            String status = bk.state == Bike.State.CRASHED ? "  (down)" : "";
            text(f14, (i + 1) + ". " + bk.name + status, ox + 30, ty, c, Align.left, 150);
            if (i > 0) {
                String gap = bk.finished ? String.format("+%.1fs", bk.finishTime - leader.finishTime)
                    : String.format("-%.0fm", Math.max(0, leader.progress - bk.progress));
                text(f14, gap, ox, ty, new Color(1, 1, 1, 0.7f), Align.right, ow - 10);
            }
        }
        text(f34, String.valueOf(Math.round(p.speed * 3.6f)), bx, by + 50, FG, Align.right, 120);
        text(f14, "km/h", bx + 126, by + 30, new Color(1, 1, 1, 0.7f), Align.left, 60);
        text(f14, "THROTTLE", bx + 180, by + 50, new Color(1, 1, 1, 0.7f), Align.left, 150);
        text(f14, "SLIDE", bx + 350, by + 50, new Color(1, 1, 1, 0.7f), Align.left, 150);
        // messages
        float my2 = H * 0.74f;
        for (Race.Message msg : race.messages) {
            BitmapFont f = msg.kind.equals("big") ? f60 : f34;
            Color c = msg.kind.equals("warn") ? WARN : msg.kind.equals("big") ? ACCENT : FG;
            text(f, msg.text, 0, my2, c, Align.center, W);
            my2 -= f.getLineHeight() + 4;
        }
        text(f14, "UP/W throttle - LEFT/A turn in - RIGHT/D straighten - SHIFT feather - C camera - ESC pause",
            22, 18, new Color(1, 1, 1, 0.5f), Align.left, 900);
        batch.end();
    }

    @Override
    public void dispose() {
        batch.dispose();
        shapes.dispose();
        f14.dispose(); f18.dispose(); f24.dispose(); f34.dispose(); f60.dispose(); fTitle.dispose();
    }
}
