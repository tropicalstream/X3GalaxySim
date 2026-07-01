# SpaceX3Tour

**A stereoscopic Milky Way flight sim for the RayNeo X3 Pro AR glasses.** Fly a
starship on a grand tour from Earth to Pluto, in real 3D across both lenses, with
a talking bridge crew.

> ⚠️ **ALPHA — proof of concept.** Expect rough edges. The **crew voices are AI
> text‑to‑speech (Fish Audio) and will change** as the project evolves — voices,
> lines, and timing are all still being tuned.

---

## 🎮 Controls (right‑arm touchpad)

| Action | What it does |
| --- | --- |
| **Tap** | **Switch camera view** — cycles Bridge → External → Engineering → Lounge |
| **Swipe forward** | **Speed up** — increases warp factor (up to 9) |
| **Swipe back** | **Slow down** — decreases warp factor (down to 1) |

### The four views
- **Bridge (Helm):** from the captain's seat at the main viewscreen, looking ahead.
- **External / Chase:** a camera trailing the ship, following it through each turn.
- **Engineering:** beside the pulsing warp core.
- **Observation Lounge:** a calm, meditative view with soft ambient tones.

### On‑screen telemetry
Ship name, current view, the planet you departed and the one you're approaching,
leg progress, **real‑distance ETA**, warp factor and heading — always visible,
refreshed every 10 seconds.

### The crew (voice announcements)
- **Navigation** — steady helm chatter, and calls out every planetary approach.
- **Science** — reports a real fact about the nearest world every ~30 seconds
  (temperatures, moons, oddities).
- **Engineering** — announces the new warp factor whenever you change speed.

Navigation and engineering take priority; science waits its turn so nobody gets
cut off.

---

## 📦 Install (no build required)
Download the latest **`SpaceX3Tour.apk`** from the
[Releases](../../releases) page and sideload it onto the glasses:

```bash
adb install -r SpaceX3Tour.apk
```

## 🛠 Build from source
Requirements: Android Studio / Android SDK, JDK 17.

```bash
./gradlew :app:assembleDebug
# output: app/build/outputs/apk/debug/SpaceX3Tour-debug.apk
adb install -r app/build/outputs/apk/debug/SpaceX3Tour-debug.apk
```

The RayNeo Mercury / IPC SDKs are bundled under `app/libs/`. The Fish Audio voice
config lives in `app/tools/fish_audio.config`; you can regenerate the bundled
voice clips with `python3 app/tools/generate_fish_audio.py`.

---

## Notes & credits
- Renders with **native OpenGL ES 2.0** (no Unity), side‑by‑side stereo for the
  X3 Pro lenses, via `BinocularSbsLayout` + the RayNeo Mercury SDK.
- Planet spacing is proportional to real cumulative astronomical‑unit distances;
  travel time is distance‑based. Gravity curves the flight path around each world.
- The starship is an **original, stylized design** — this project is not
  affiliated with or endorsed by any franchise.
- **RayNeo** — X3 Pro hardware and AR SDKs. **Fish Audio** — text‑to‑speech voices.

A personal, non‑commercial project.
