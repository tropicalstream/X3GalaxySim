#!/usr/bin/env python3
"""
Pre-renders EVERY line of timeline.json to Ogg/Opus with Fish Audio S2.1 Pro.
One-time developer script — the app itself is fully offline.

    python3 tools/generate_dialogue.py           # render missing clips
    python3 tools/generate_dialogue.py --force   # re-render everything
    python3 tools/generate_dialogue.py --only sgra_periapsis

Notes (hard-won):
  * Model header fallback chain: s2.1-pro -> s2.1-pro-free -> s2-pro.
    (Old builds: bare "s2.1-pro" 400'd before the S2.1 launch; the free tier
    string is s2.1-pro-free and is valid through July 2026.)
  * S2 models take free-form [bracket] acting tags — they're natural language,
    not a fixed set, and layer: "[whisper] [awe] Periapsis." works.
  * Fish's current formats are wav/pcm/mp3/opus — no ogg-vorbis. We request
    "opus" (48 kHz, Ogg container) and save as .ogg; Android plays it natively.
  * The key lives in gitignored fish.config. Never hardcode it in the app.
"""
import argparse, json, pathlib, sys, time
import requests

HERE = pathlib.Path(__file__).resolve().parent
APP = HERE.parent
SCRIPT = APP / "src/main/assets/timeline.json"
VOICE = APP / "src/main/assets/voice"
CFG = dict(l.split("=", 1) for l in (HERE / "fish.config").read_text().splitlines()
           if "=" in l and not l.strip().startswith("#"))

FOLDER = {"NAVIGATION": "navigation", "SCIENCE": "science", "ENGINEERING": "engineering"}
VOICE_ID = {"NAVIGATION": CFG.get("NAV_VOICE", ""),
            "SCIENCE": CFG.get("SCI_VOICE", ""),
            "ENGINEERING": CFG.get("ENG_VOICE", "")}

MODEL_CHAIN = []
for m in (CFG.get("MODEL", "s2.1-pro"), "s2.1-pro", "s2.1-pro-free", "s2-pro"):
    if m and m not in MODEL_CHAIN:
        MODEL_CHAIN.append(m)
ACTIVE_MODEL = [MODEL_CHAIN[0]]

def derive_prosody(text):
    """Per-line delivery from the acting direction itself: urgent lines run hot,
    tender/awed lines slow down and breathe. S2.1 reads the [bracket] direction
    for tone; prosody.speed shapes the pacing around it."""
    t = text.lower()
    speed = 1.0
    if any(w in t for w in ("urgent", "barking", "yelling", "shouting", "adrenaline", "faster")):
        speed = 1.07
    elif any(w in t for w in ("whisper", "lullaby", "tender", "reveren", "awe", "each word placed",
                              "letting it land", "slower cadence", "savoring")):
        speed = 0.93
    elif any(w in t for w in ("thrilled", "tumbling", "excited")):
        speed = 1.04
    return speed

def synth(cue, vid, out, retries=3):
    text = cue["text"]
    body = {"text": text, "format": "opus", "opus_bitrate": 48000,
            # expressive: high temperature lets S2.1 act; per-line overrides win
            "temperature": float(cue.get("temperature", CFG.get("TEMPERATURE", 0.9))),
            "top_p": 0.85, "normalize": True, "latency": "normal",
            "chunk_length": 300, "condition_on_previous_chunks": True,
            "prosody": {"speed": float(cue.get("speed", derive_prosody(text))),
                        "volume": 0, "normalize_loudness": True}}
    if vid:
        body["reference_id"] = vid
    for attempt in range(retries):
        model = ACTIVE_MODEL[0]
        try:
            r = requests.post("https://api.fish.audio/v1/tts",
                headers={"Authorization": f"Bearer {CFG['API_KEY']}",
                         "Content-Type": "application/json", "model": model},
                json=body, timeout=300)
        except requests.RequestException as e:
            print(f"  ! {out.name} network error ({e}); retry {attempt + 1}")
            time.sleep(2 ** attempt)
            continue
        if r.status_code == 200 and r.content[:4] == b"OggS":
            out.parent.mkdir(parents=True, exist_ok=True)
            out.write_bytes(r.content)
            print(f"  + {out.name}  [{model}, {len(r.content) // 1024} KB]")
            return model
        if r.status_code in (400, 402, 404) and len(MODEL_CHAIN) > MODEL_CHAIN.index(model) + 1:
            nxt = MODEL_CHAIN[MODEL_CHAIN.index(model) + 1]
            print(f"  ! model '{model}' rejected ({r.status_code}) -> falling back to '{nxt}'")
            ACTIVE_MODEL[0] = nxt
            continue
        if r.status_code == 429:
            print(f"  ! rate limited; sleeping {4 * (attempt + 1)}s")
            time.sleep(4 * (attempt + 1))
            continue
        print(f"  ! {out.name} {r.status_code} {r.text[:160]}")
        time.sleep(1 + attempt)
    return None

def write_manifest(items, rendered):
    manifest = {
        "format": "ProjectPaleBlueVoiceManifest/v1",
        "generated_at_unix": int(time.time()),
        "model_chain": MODEL_CHAIN,
        "voice_ids": {
            "NAVIGATION": VOICE_ID["NAVIGATION"],
            "SCIENCE": VOICE_ID["SCIENCE"],
            "ENGINEERING": VOICE_ID["ENGINEERING"],
        },
        "clips": []
    }
    for c in items:
        out = VOICE / FOLDER[c["role"]] / f"{c['clip']}.ogg"
        manifest["clips"].append({
            "role": c["role"],
            "clip": c["clip"],
            "voice_id": VOICE_ID[c["role"]],
            "model": rendered.get(c["clip"], ACTIVE_MODEL[0]),
            "asset": str(out.relative_to(APP / "src/main/assets")),
            "bytes": out.stat().st_size if out.exists() else 0,
        })
    (VOICE / "manifest.json").write_text(json.dumps(manifest, indent=2) + "\n")

def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--force", action="store_true")
    ap.add_argument("--only", default=None)
    args = ap.parse_args()
    if not CFG.get("API_KEY"):
        sys.exit("API_KEY missing from fish.config")
    if not all(VOICE_ID.values()):
        sys.exit("Voice ids missing — run design_voices.py first (or paste ids into fish.config).")
    data = json.loads(SCRIPT.read_text())
    items = [c for c in data["cues"] if c.get("role") and c.get("clip") and c.get("text")]
    items += [f for f in data.get("fillers", []) if f.get("role") and f.get("clip") and f.get("text")]
    if data.get("boot"):
        items.append(data["boot"])
    ev = data.get("events", {})
    for key in ("near_miss", "engine_trouble", "debris_strike", "alien"):
        for variant in ev.get(key, []):
            items += [l for l in variant if l.get("role") and l.get("clip") and l.get("text")]
    for ally in ev.get("ally", []):
        items += [l for l in ally.get("lines", []) if l.get("role") and l.get("clip") and l.get("text")]
    items += [a for a in data.get("arrivals", {}).values()
              if a.get("role") and a.get("clip") and a.get("text")]
    items += [c for c in data.get("chronicle", [])
              if c.get("role") and c.get("clip") and c.get("text")]
    if args.only:
        items = [c for c in items if c["clip"] == args.only]
    todo, skipped = [], 0
    for c in items:
        out = VOICE / FOLDER[c["role"]] / f"{c['clip']}.ogg"
        if out.exists() and not args.force:
            skipped += 1
            continue
        todo.append((c, out))
    print(f"{len(items)} lines total, {skipped} already rendered, {len(todo)} to synthesize "
          f"(model chain: {' -> '.join(MODEL_CHAIN)})\n")
    ok = 0
    rendered = {}
    t0 = time.time()
    for i, (c, out) in enumerate(todo, 1):
        print(f"[{i}/{len(todo)}] {c['role']:11s} {c['clip']}")
        used_model = synth(c, VOICE_ID[c["role"]], out)
        if used_model:
            ok += 1
            rendered[c["clip"]] = used_model
    write_manifest(items, rendered)
    print(f"\nDone: {ok}/{len(todo)} rendered in {time.time() - t0:.0f}s. "
          f"{'ALL CLIPS PRESENT.' if ok == len(todo) else 'RE-RUN to retry failures.'}")
    print(f"Voice manifest: {VOICE / 'manifest.json'}")

if __name__ == "__main__":
    main()
