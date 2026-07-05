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


