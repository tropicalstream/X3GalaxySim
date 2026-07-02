# X3GalaxySim

**X3GalaxySim** is a native Android OpenGL ES experience for the RayNeo X3 Pro: a stereoscopic, on-rails science tour from Earth orbit through the Solar System and onward into the wider galaxy.

The simulation is built around scientific context rather than arcade fantasy. It uses real Solar System textures where available, a star catalog backdrop, inverse-square sunlight, planet and moon waypoints, telemetry readouts, arrival callouts, and narration about astronomy, spaceflight, climate history, conflict, cooperation, and humanity's long road toward a Kardashev Type I future.

## Highlights

- RayNeo X3 Pro stereo rendering with Mercury SDK registration.
- 3DoF gaze camera tuned for the X3 Pro optical axis.
- One-hour guided route with telemetry for velocity, Lorentz factor, next point of interest, distance, ETA, hull temperature, and shields.
- Point-of-interest arrival narration that tells the viewer where to look and what they are seeing.
- Earth-orbit intro with the player's ship docked at Earth Station Aurora.
- Procedural space traffic, satellites, probes, rocks, debris, allied ships, and visible alien encounters.
- Offline crew dialogue, SFX, and ambient audio at runtime.
- Science-history chronicle inspired by Carl Sagan's “pale blue dot” perspective.
- Settings menu with save, restart confirmation, subtitles, audio mix, and recenter controls.

## APK

A debug APK is published as a GitHub release asset:

```text
X3GalaxySim-debug.apk
```

Install with:

```bash
adb install -r X3GalaxySim-debug.apk
```

## Build

Requirements:

- Android Studio or Android Gradle Plugin compatible CLI environment
- JDK 17
- RayNeo SDK AARs in `app/libs/`

Build:

```bash
./gradlew :app:assembleDebug
```

The output APK will be:

```text
app/build/outputs/apk/debug/app-debug.apk
```

## Voice Assets And Security

The app runs fully offline and does **not** include the Fish Audio API key. Voice clips are pre-rendered into `app/src/main/assets/voice/`.

The local development file below is intentionally ignored and must not be committed:

```text
app/tools/fish.config
```

If new dialogue is added, generate voices locally, then rotate the API key afterward:

```bash
python3 app/tools/generate_dialogue.py
```

## Runtime Controls

- Single tap: start/resume or select the highlighted settings item.
- Forward/back swipe in settings: navigate menu.
- Double tap: open or exit settings.
- Long press: quick audio mix / close settings fallback.

## Notes

This repository is a research and experience prototype for RayNeo X3 Pro spatial display. It is designed to make the scale of space legible: distances, light, orbital infrastructure, planetary environments, stellar nurseries, exoplanets, and the galactic center are treated as scientific landmarks rather than backdrop decoration.
