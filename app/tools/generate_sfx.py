#!/usr/bin/env python3
"""
Procedurally synthesizes every bridge SFX and ambient bed (numpy -> WAV ->
ffmpeg -> Ogg/Vorbis) so the app always ships with working audio. Fish's
public API is speech-only (their Sound Effects product has no API endpoint),
so the non-voice layer is synthesized here — deterministic, loopable, small.

    python3 tools/generate_sfx.py     (requires numpy + ffmpeg on PATH)
"""
import pathlib, subprocess, tempfile, wave
import numpy as np

APP = pathlib.Path(__file__).resolve().parent.parent
SFX = APP / "src/main/assets/sfx"
AMB = APP / "src/main/assets/ambient"
SR = 44100
rng = np.random.default_rng(7)

def t(dur): return np.arange(int(SR * dur)) / SR
def env(sig, a=0.01, r=0.2):
    n = len(sig); e = np.ones(n)
    na, nr = max(1, int(SR * a)), max(1, int(SR * r))
    e[:na] = np.linspace(0, 1, na); e[-nr:] = np.linspace(1, 0, nr)
    return sig * e
def lp(sig, alpha):
    out = np.empty_like(sig); acc = 0.0
    for i, s in enumerate(sig):
        acc += alpha * (s - acc); out[i] = acc
    return out
def norm(sig, peak=0.85):
    m = np.max(np.abs(sig)) or 1.0
    return sig / m * peak
def loopable(sig, fade=1.5):
    n = int(SR * fade)
    head, tail = sig[:n].copy(), sig[-n:].copy()
    w = np.linspace(0, 1, n)
    sig = sig[:-n]
    sig[:n] = head * w + tail * (1 - w)
    return sig
def save(name, folder, sig):
    folder.mkdir(parents=True, exist_ok=True)
    pcm = (np.clip(sig, -1, 1) * 32767).astype(np.int16)
    with tempfile.NamedTemporaryFile(suffix=".wav", delete=False) as f:
        wav_path = f.name
    with wave.open(wav_path, "wb") as w:
        w.setnchannels(1); w.setsampwidth(2); w.setframerate(SR)
        w.writeframes(pcm.tobytes())
    out = folder / f"{name}.ogg"
    subprocess.check_call(["ffmpeg", "-y", "-loglevel", "error", "-i", wav_path,
                           "-c:a", "libvorbis", "-q:a", "3", str(out)])
    pathlib.Path(wav_path).unlink()
    print(f"  + {out.relative_to(APP)} ({out.stat().st_size // 1024} KB)")

def sine(f, dur, ph=0.0): return np.sin(2 * np.pi * f * t(dur) + ph)
def sweep(f0, f1, dur):
    tt = t(dur); f = f0 * (f1 / f0) ** (tt / dur)
    return np.sin(2 * np.pi * np.cumsum(f) / SR)

print("Synthesizing SFX …")
# engine_ignite: sub swell + harmonic bloom
d = 3.5; tt = t(d)
sig = sweep(28, 82, d) * np.linspace(0.2, 1, len(tt)) ** 2
sig += 0.4 * sweep(56, 164, d) * np.linspace(0, 1, len(tt)) ** 3
sig += 0.25 * lp(rng.standard_normal(len(tt)), 0.03) * np.linspace(0.1, 1, len(tt))
save("engine_ignite", SFX, norm(env(sig, 0.4, 0.8)))

# warp_jump: rising shimmer sweep + boom
d = 4.0; tt = t(d)
sig = sweep(90, 1400, d * 0.6).tolist() + [0] * (len(tt) - int(SR * d * 0.6))
sig = np.array(sig[:len(tt)])
sig += 0.5 * sweep(45, 700, d) * np.exp(-tt * 0.8)
shimmer = 0.3 * sine(2200, d) * sine(3.1, d) * np.exp(-tt * 1.2)
boom = 0.9 * sine(38, d) * np.exp(-tt * 2.2)
save("warp_jump", SFX, norm(env(sig + shimmer + boom, 0.05, 1.2)))

# shield_impact: noise burst + metallic ring
d = 1.6; tt = t(d)
burst = lp(rng.standard_normal(len(tt)), 0.25) * np.exp(-tt * 9)
ring = sum(a * sine(f, d) * np.exp(-tt * r) for f, a, r in
           [(221, 0.5, 3.5), (554, 0.35, 4.5), (932, 0.25, 6), (1480, 0.15, 8)])
save("shield_impact", SFX, norm(env(burst + ring, 0.002, 0.4)))

# solar_flare: deep whoosh + crackle
d = 3.0; tt = t(d)
whoosh = lp(rng.standard_normal(len(tt)), 0.02) * np.sin(np.pi * tt / d) ** 1.5 * 2
crackle = np.zeros(len(tt))
for i in rng.integers(0, len(tt) - 400, 260):
    crackle[i:i + 400] += np.exp(-np.arange(400) / 60) * rng.uniform(0.2, 1) * np.sin(np.pi * tt[i] / d)
save("solar_flare", SFX, norm(env(whoosh + 0.35 * lp(crackle, 0.4), 0.3, 0.7)))

# ring_ping: icy chime
d = 1.3; tt = t(d)
ping = sum(a * sine(f, d) * np.exp(-tt * r) for f, a, r in
           [(1830, 0.5, 6), (2712, 0.35, 8), (3630, 0.22, 10), (5420, 0.12, 13)])
sparkle = 0.1 * rng.standard_normal(len(tt)) * np.exp(-tt * 12)
save("ring_ping", SFX, norm(env(ping + sparkle, 0.002, 0.3)))

# alarm_soft: gentle two-tone, twice
d = 2.0; tt = t(d)
sig = np.zeros(len(tt))
for k, f in [(0.0, 659), (0.35, 880), (1.0, 659), (1.35, 880)]:
    i = int(k * SR); seg = t(0.32)
    sig[i:i + len(seg)] += np.sin(2 * np.pi * f * seg) * np.sin(np.pi * seg / 0.32) * 0.6
save("alarm_soft", SFX, norm(sig, 0.6))

# whoosh_flyby: bandpassed noise falling
d = 2.4; tt = t(d)
n = lp(rng.standard_normal(len(tt)), 0.15) - lp(rng.standard_normal(len(tt)), 0.02)
sig = n * np.sin(np.pi * tt / d) ** 2 * sweep(3, 1.2, d)
save("whoosh_flyby", SFX, norm(env(sig, 0.2, 0.6)))

# glass_rain: dense high tinkles
d = 3.0; tt = t(d)
sig = 0.12 * (lp(rng.standard_normal(len(tt)), 0.6) - lp(rng.standard_normal(len(tt)), 0.3))
for i in rng.integers(0, len(tt) - 900, 420):
    f = rng.uniform(2000, 6800); seg = np.arange(900) / SR
    sig[i:i + 900] += np.sin(2 * np.pi * f * seg) * np.exp(-seg * rng.uniform(18, 40)) * rng.uniform(0.1, 0.5)
save("glass_rain", SFX, norm(env(sig, 0.15, 0.5)))

# bh_groan: tidal stress on the hull
d = 5.0; tt = t(d)
sig = (sine(27, d) + 0.8 * sine(33.5, d) + 0.5 * sine(41, d)) * (0.6 + 0.4 * sine(0.5, d))
creak = 0.3 * lp(rng.standard_normal(len(tt)), 0.01) * (0.5 + 0.5 * sine(0.33, d, 1.2))
save("bh_groan", SFX, norm(env(sig + creak, 0.8, 1.2)))

# seatbelt_chime: welcoming major arpeggio
d = 1.6; tt = t(d)
sig = np.zeros(len(tt))
for k, f in [(0.0, 523.25), (0.18, 659.25), (0.36, 783.99)]:
    i = int(k * SR); seg = t(d - k)
    sig[i:] += np.sin(2 * np.pi * f * seg) * np.exp(-seg * 3.2) * 0.45
save("seatbelt_chime", SFX, norm(sig, 0.7))

# ui_tap / ui_save
d = 0.16; sig = sine(980, d) * np.exp(-t(d) * 30)
save("ui_tap", SFX, norm(sig, 0.55))
d = 0.8; tt = t(d); sig = np.zeros(len(tt))
for k, f in [(0.0, 740), (0.22, 1108)]:
    i = int(k * SR); seg = t(0.3)
    sig[i:i + len(seg)] += np.sin(2 * np.pi * f * seg) * np.exp(-seg * 10) * 0.5
save("ui_save", SFX, norm(sig, 0.6))

# scene_chime: cinematic chapter-card swell (soft fifth + shimmer)
d = 2.6; tt = t(d)
swell = np.sin(np.pi * np.clip(tt / 1.1, 0, 1)) * 0.8
sig = (0.5 * sine(220, d) + 0.4 * sine(330, d, 0.4) + 0.25 * sine(440, d, 1.1)) * swell
sig += 0.15 * sine(1760, d) * np.exp(-tt * 2.2)
sig += 0.05 * lp(rng.standard_normal(len(tt)), 0.2) * swell
save("scene_chime", SFX, norm(env(sig, 0.15, 1.1), 0.6))

# alarm_klaxon: harsher two-tone battle-stations sweep
d = 2.4; tt = t(d)
sig = np.zeros(len(tt))
for k in (0.0, 0.8, 1.6):
    i = int(k * SR); seg = t(0.7)
    tone = np.sin(2 * np.pi * (520 + 260 * np.sin(np.pi * seg / 0.7)) * seg)
    sig[i:i + len(seg)] += tone * np.sin(np.pi * seg / 0.7) * 0.7
sig += 0.15 * lp(rng.standard_normal(len(tt)), 0.3)
save("alarm_klaxon", SFX, norm(env(sig, 0.02, 0.3), 0.75))

# thruster_sputter: engine coughing — interrupted rumble
d = 2.8; tt = t(d)
rumble = sweep(70, 40, d) + 0.5 * sweep(140, 80, d)
gate = (np.sin(tt * 23.0) > -0.2).astype(float) * (np.sin(tt * 7.7) > -0.5).astype(float)
sig = rumble * gate + 0.3 * lp(rng.standard_normal(len(tt)), 0.05) * gate
save("thruster_sputter", SFX, norm(env(sig, 0.05, 0.5)))

# weapon_zap: alien energy discharge
d = 0.9; tt = t(d)
sig = sweep(2600, 240, d) * np.exp(-tt * 6.0)
sig += 0.4 * sweep(5200, 480, d) * np.exp(-tt * 9.0)
sig += 0.25 * lp(rng.standard_normal(len(tt)), 0.5) * np.exp(-tt * 12.0)
save("weapon_zap", SFX, norm(env(sig, 0.002, 0.25)))

# comm_chirp: incoming allied hail
d = 1.1; tt = t(d)
sig = np.zeros(len(tt))
for k, f in [(0.0, 880), (0.15, 1174), (0.3, 1568)]:
    i = int(k * SR); seg = t(0.22)
    sig[i:i + len(seg)] += np.sin(2 * np.pi * f * seg) * np.sin(np.pi * seg / 0.22) * 0.5
save("comm_chirp", SFX, norm(sig, 0.6))

print("Synthesizing ambient beds (loopable) …")
# (bridge_hum intentionally removed — the bridge idles in silence by design)

# warp_drone: coils in harmony
d = 24.0; tt = t(d)
sig = 0.45 * sine(65, d) + 0.35 * sine(97.5, d, 0.5) + 0.18 * sine(130.8, d, 2.1)
sig *= 0.8 + 0.2 * sine(0.23, d)
sig += 0.2 * lp(rng.standard_normal(len(tt)), 0.05) * (0.6 + 0.4 * sine(0.07, d))
sig += 0.06 * sine(1560, d) * (0.5 + 0.5 * sine(0.19, d, 0.9))
save("warp_drone", AMB, norm(loopable(sig), 0.5))

# nebula_wind: airy, faintly choral
d = 24.0; tt = t(d)
wind = (lp(rng.standard_normal(len(tt)), 0.08) - lp(rng.standard_normal(len(tt)), 0.01)) * (0.6 + 0.4 * sine(0.05, d))
choir = 0.10 * sine(216, d) * (0.5 + 0.5 * sine(0.043, d)) + 0.07 * sine(324, d, 1.1) * (0.5 + 0.5 * sine(0.061, d, 2))
save("nebula_wind", AMB, norm(loopable(wind * 0.7 + choir), 0.45))

print("\nAll SFX and ambient beds generated.")
