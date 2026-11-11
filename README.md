# Broadside Speedway

An original 3D motorcycle **speedway** racing game for the desktop, built on
[libGDX](https://libgdx.com) 1.14 with **Bullet** physics (gdx-bullet) and FreeType fonts.
Four riders, four laps, no brakes: line up at the tapes, broadside through the left-hand bends,
and keep it off the fence.

- Collision physics: bikes bump and bounce off the air fence; hard hits throw the rider off as a
  ragdoll and send the bike tumbling.
- AI riders, Assisted/Classic steering, an animated start marshal, and quick heats or a 5-heat
  meeting with 3-2-1-0 scoring.
- Custom lighting shader (shadows, sky reflections, fog, tone mapping) and bloom.
- Everything is procedural: no image, model or sound assets. The only bundled files are the
  DejaVu fonts; see `assets/fonts/LICENSE-DejaVu.txt`.

## Run

Needs a JDK 17+. On this machine one is installed locally in `~/.local/jdk` (no sudo); `run.sh`
uses it automatically.

```bash
./run.sh                         # build & play (Gradle downloads libGDX on the first run)
./gradlew dist                   # single runnable jar -> lwjgl3/build/libs/broadside-speedway.jar
java -jar lwjgl3/build/libs/broadside-speedway.jar
./gradlew sim                    # headless physics/AI checks (no window needed)
./gradlew sim -PappArgs="handling 3"   # race | handling | difficulty
```

(Without `run.sh`: `export JAVA_HOME=~/.local/jdk/jdk-21*` first, then use `./gradlew`.)

## Controls

**↑/W** throttle, **←/A** turn in, **→/D** straighten, **Shift**
feather, **C** camera (chase / TV gantry / helmet), **M** mute, **Esc/P** pause, **R** restart
heat. Menus use the arrow keys plus Enter, or the mouse.

## Layout

```
core/src/main/java/com/broadside/speedway/
  Track, Phys, PhysicsWorld, Bike, Ragdoll   oval geometry + Bullet world, tyre model, crashes, ragdolls
  AIDriver, Race                             AI riders, tapes start, laps, falls, standings
  SpeedwayGame, Hud                          game flow, meeting, cameras, render passes, HUD & menus
  render/Lighting                            custom shader: per-pixel sun, PCF shadow map, sky reflections,
                                             clearcoat, fog, ACES tone mapping; sky dome
  render/Bloom                               bloom post-process (bright pass, 3-level blur, additive composite)
  render/Stadium, BikeView, Figure, MarshalView, Effects, Textures, MeshKit
  audio/Synth                                real-time synthesised engines/crowd/impacts via AudioDevice
lwjgl3/   desktop launcher + SimMain (headless sims)
assets/   fonts
```

## Automation / debugging

System properties (pass to `./gradlew run -D...` or `java -D... -jar`):
`broadside.autostart=quick|meeting`, `broadside.autopilot`, `broadside.laps=2|4|6`,
`broadside.cam=0..3` (3 = close-up orbit), `broadside.crashAt=<race seconds>`,
`broadside.shots=/path/prefix` with `broadside.shotTimes=1.5,9@1` (seconds, optional `@camera`)
to save screenshots, `broadside.quitAfter=<s>`, `broadside.hidden` (no visible window), `broadside.bloom=off`.

Bloom is on by default and can be toggled in the menu. Tune it with `threshold`, `knee` and
`intensity` at the top of `render/Bloom.java`.
