#!/usr/bin/env python3
"""
Generate X3 Constellation crew-voice clips with Fish Audio (S2).

Runs on your Mac (needs network + `pip install requests`). It writes WAV clips
into ../src/main/assets/voice/<role>/<key>.wav, which the app plays for planet
approaches and view changes. If a clip is missing at runtime, the app falls back
to on-device TextToSpeech, so this step is optional but gives the good voices.

Usage:
    cp fish_audio.config.example fish_audio.config   # then edit it
    python3 generate_fish_audio.py

Config (fish_audio.config, gitignored) holds your API key and one reference
voice id per department so the three crew members sound like different people.
Get voice ids from https://fish.audio (or leave blank to use the model default).
API docs: https://docs.fish.audio/api-reference/introduction
"""
import os
import sys
import json
import pathlib

try:
    import requests
except ImportError:
    sys.exit("Please run: pip install requests")

HERE = pathlib.Path(__file__).resolve().parent
ASSETS = HERE.parent / "src" / "main" / "assets" / "voice"
CONFIG = HERE / "fish_audio.config"

API_URL = "https://api.fish.audio/v1/tts"
# Backbone model. The docs list the current model ids; set MODEL in the config
# to whatever your account calls "S2" if this default is not it.
DEFAULT_MODEL = "s1"


def load_config():
    if not CONFIG.exists():
        sys.exit(f"Missing {CONFIG.name}. Copy fish_audio.config.example and fill it in.")
    cfg = {}
    for line in CONFIG.read_text().splitlines():
        line = line.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        k, v = line.split("=", 1)
        cfg[k.strip()] = v.strip()
    if not cfg.get("API_KEY"):
        sys.exit("Set API_KEY in fish_audio.config")
    return cfg


PLANETS = ["moon", "mercury", "venus", "mars", "jupiter",
           "saturn", "uranus", "neptune", "pluto"]

APPROACH = {p: f"Approaching {p.capitalize()}. Helm adjusting course, all stop on my mark."
            for p in PLANETS}

FACTS = {
    "moon": "Science: Luna, Earth's only natural satellite, tidally locked, roughly three hundred eighty four thousand kilometers out.",
    "mercury": "Science: Mercury, the innermost world. No atmosphere to speak of, surface swinging over six hundred degrees between day and night.",
    "venus": "Science: Venus, a runaway greenhouse. Surface pressure ninety times Earth's, hot enough to melt lead.",
    "mars": "Science: Mars, the red planet. Home to Olympus Mons, the tallest volcano in the solar system.",
    "jupiter": "Science: Jupiter, a gas giant with a storm, the Great Red Spot, wider than Earth itself.",
    "saturn": "Science: Saturn, ringed in ice and rock, and light enough to float in water.",
    "uranus": "Science: Uranus, an ice giant tipped on its side, orbiting the sun nearly ninety eight degrees off axis.",
    "neptune": "Science: Neptune, the windiest world, with gales exceeding two thousand kilometers per hour.",
    "pluto": "Science: Pluto, a dwarf planet of the Kuiper Belt, with a heart shaped nitrogen glacier.",
}

# role -> list of (clip_key, text)
def build_jobs():
    jobs = {"navigation": [], "science": [], "engineering": []}
    jobs["navigation"].append(("depart_earth",
        "All decks, this is the bridge. Departing Earth orbit, course laid in."))
    for p in PLANETS:
        jobs["navigation"].append((f"approach_{p}",
            f"Navigation. Approaching {p.capitalize()}, adjusting heading for orbital insertion."))
        jobs["science"].append((f"fact_{p}", FACTS[p]))
        jobs["engineering"].append((f"core_{p}",
            f"Engineering. Warp core steady as we swing past {p.capitalize()}."))
    jobs["science"].append(("lounge",
        "Observation lounge. Long range sensors are quiet. Take a breath and enjoy the view."))
    return jobs


def synth(cfg, text, voice_id, out_path):
    headers = {"Authorization": f"Bearer {cfg['API_KEY']}",
               "Content-Type": "application/json"}
    model = cfg.get("MODEL", DEFAULT_MODEL)
    if model:
        headers["model"] = model
    body = {"text": text, "format": "wav"}
    if voice_id:
        body["reference_id"] = voice_id
    r = requests.post(API_URL, headers=headers, data=json.dumps(body), timeout=120)
    if r.status_code != 200:
        print(f"  ! {out_path.name}: HTTP {r.status_code} {r.text[:180]}")
        return False
    out_path.parent.mkdir(parents=True, exist_ok=True)
    out_path.write_bytes(r.content)
    print(f"  + {out_path.relative_to(ASSETS.parent)} ({len(r.content)} bytes)")
    return True


def main():
    cfg = load_config()
    voice_ids = {
        "navigation": cfg.get("NAVIGATION_VOICE_ID", ""),
        "science": cfg.get("SCIENCE_VOICE_ID", ""),
        "engineering": cfg.get("ENGINEERING_VOICE_ID", ""),
    }
    ok = 0
    total = 0
    for role, items in build_jobs().items():
        print(f"[{role}]")
        for key, text in items:
            total += 1
            if synth(cfg, text, voice_ids[role], ASSETS / role / f"{key}.wav"):
                ok += 1
    print(f"\nDone: {ok}/{total} clips written to {ASSETS}")


if __name__ == "__main__":
    main()
