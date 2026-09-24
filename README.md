# X3GalaxySim

**X3GalaxySim** is a native Android OpenGL ES experience for the RayNeo X3 Pro: a stereoscopic, on-rails science tour from Earth orbit through the Solar System and onward into the wider galaxy.

The simulation is built around scientific context rather than arcade fantasy. It uses real Solar System textures where available, a star catalog backdrop, inverse-square sunlight, planet and moon waypoints, telemetry readouts, arrival callouts, and narration about astronomy, spaceflight, climate history, conflict, cooperation, and humanity's long road toward a Kardashev Type I future.

## Screenshots

<p>
  <img src="images/station.png" width="45%" alt="Ship docked at Earth Station Aurora with telemetry HUD">
  <img src="images/earth.png" width="45%" alt="Project Pale Blue title over an Earth close-up">
</p>

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
- **Jump to any segment** — pick a stop from the settings menu and the ship makes a visible light-speed run there (5–20 s, scaled to distance) instead of teleporting.
- **Triple-tap to recalibrate** — re-centers head tracking and restores the camera framing the script intends at your current point in the tour, in one gesture.

## Why every planet shows its lit face

Earlier builds rendered planets with true directional sunlight, which meant that after a flyby — or on a close pass viewed side-on — you'd often be looking at the unlit hemisphere. On a normal display that's a dim, textured surface. On the X3 Pro's waveguide, it isn't: the optics are **additive** — the lens adds light on top of whatever the real world is doing, it can't subtract it. There is no way to display "dark but detailed," only "some added light" or "no added light at all." A physically dim or unlit patch of texture doesn't render as a shadowed sphere; it renders as a hole in the sky.

Two changes fix this, deliberately trading strict physical accuracy for a texture you can actually see through the glasses:

1. **Solar-side tracking** — each body's effective light source swings toward the camera as your viewing angle passes through side-on or anti-solar, so the hemisphere you're looking at stays the one that's lit. It fades in gradually and never engages on approaches where the real geometry already favors you, so it isn't visible as a "trick" in normal viewing.
2. **Full display exposure** — sunlight no longer dims by the inverse-square law all the way to the physically correct (and optically invisible) near-black; it's held in a narrow band that keeps a fully-lit face just under clipping. The emotional arc of "brighter near the Sun, dimmer at Pluto" mostly survives in color and mood, not in raw output level.

The result: every planet always shows a lit, textured face, which is the right trade for a real-time science tour on this hardware — even though it means the rig no longer tracks true day/night lighting the way it would on a conventional screen.

## APK

A debug APK is published as a GitHub release asset:

```text
X3GalaxySim-debug.apk
```

Install with:

```bash
adb install -r X3GalaxySim-debug.apk
```


