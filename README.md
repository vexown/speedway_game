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

## Requirements

| What | Details |
| --- | --- |
| **JDK 17–24** (21 recommended) | A full JDK, not just a JRE. JDK 25+ is too new for the bundled Gradle 8.14. Any vendor works, e.g. [Eclipse Temurin 21](https://adoptium.net/temurin/releases/?version=21). |
| **Gradle** | Nothing to install: the committed wrapper (`gradlew` / `gradlew.bat`) downloads Gradle 8.14.3. |
| **Internet (first build only)** | Gradle downloads itself plus libGDX 1.14.2, gdx-bullet, gdx-freetype and LWJGL from Maven Central (about 100 MB, cached in `~/.gradle`). Later builds work offline. |
| **Graphics** | A GPU and driver with OpenGL 2.0 or newer (any machine from the last ~10 years). |
| **OS** | Windows, Linux or macOS (x64 or arm64; libGDX bundles the native libraries). Developed and tested on Linux x64. |
| Sound | Optional: if there's no audio device the game runs silently. |

Installing a JDK:

- **Windows:** `winget install EclipseAdoptium.Temurin.21.JDK` (or use the installer from adoptium.net)
- **macOS:** `brew install --cask temurin@21`
- **Debian/Ubuntu:** `sudo apt install openjdk-21-jdk`
- **Fedora:** `sudo dnf install java-21-openjdk-devel`
- **No admin rights:** download the Temurin 21 `.tar.gz`/`.zip`, unpack it, and point `JAVA_HOME`
  at it. On Linux/macOS, `run.sh` also finds a JDK unpacked under `~/.local/jdk/`.

Check with `java -version`. It should report 17–24; if several are installed, set `JAVA_HOME`.

## Run

**Linux / macOS**

```bash
./run.sh                    # build & play (the first run takes a minute while Gradle downloads)
```

**Windows** (Command Prompt or PowerShell, in the project folder)

```bat
gradlew.bat lwjgl3:run
```

**Standalone jar** (one file with all native libraries; copy it anywhere and run it with Java 17+):

```bash
./gradlew dist              # Windows: gradlew.bat dist
java -jar lwjgl3/build/libs/broadside-speedway.jar
```

On macOS the launcher automatically relaunches itself with `-XstartOnFirstThread`, which LWJGL
requires there. If that fails, run `java -XstartOnFirstThread -jar broadside-speedway.jar`.
(The macOS paths are untested; only Linux has been verified.)

**Headless checks** (no window or GPU needed): `./gradlew sim`, or
`./gradlew sim -PappArgs="handling 3"` (modes: `race`, `handling`, `difficulty`).

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
