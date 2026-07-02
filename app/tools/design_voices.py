#!/usr/bin/env python3
"""
Designs the three crew voice PERSONALITIES with Fish Audio's voice-design-1
model, then registers each winning candidate as a private voice model so
generate_dialogue.py can reference it by id. Run once, locally:

    python3 tools/design_voices.py            # design + auto-pick candidate 0
    python3 tools/design_voices.py --listen   # design, save previews, exit (pick manually)
    python3 tools/design_voices.py --pick NAV=2 SCI=0 ENG=1

Previews land in tools/voice_design_out/<ROLE>_<n>.wav — audition them, then
either re-run with --pick or paste model ids into fish.config yourself.
"""
import argparse, base64, json, pathlib, sys, wave
import requests

HERE = pathlib.Path(__file__).resolve().parent
OUT = HERE / "voice_design_out"
CFG_PATH = HERE / "fish.config"
API = "https://api.fish.audio"

PERSONALITIES = {
    "NAV": {
        "title": "Pale Blue — Captain Vale (Navigation)",
        "instruction": (
            "A starship captain in her mid-40s: warm, commanding female voice with a rich, "
            "low alto timbre and crisp, unhurried diction. Effortless authority softened by "
            "genuine warmth — she smiles while giving orders. Bold and reassuring, equal parts "
            "Uhura's poise and Kirk's daring. Neutral mid-Atlantic English accent. Intimate "
            "bridge-microphone presence, cinematic and close."),
        "reference_text": "Welcome aboard the Pale Blue, traveler. This bridge is yours for the next hour. Departure window opens in ninety seconds.",
    },
    "SCI": {
        "title": "Pale Blue — Dr. Onara (Science)",
        "instruction": (
            "A brilliant astrophysicist in her late 30s: luminous, precise female voice full of "
            "contagious wonder. Measured, logical pacing that blooms into delight when a fact is "
            "beautiful — Spock's discipline fused with Neil deGrasse Tyson's infectious enthusiasm. "
            "Gentle Indian-English accent, velvety mid register, the tone of a planetarium narrator "
            "who believes every equation is a gift."),
        "reference_text": "The iron in your blood was forged in the heart of a dying star. You are not watching the universe. You are visiting relatives.",
    },
    "ENG": {
        "title": "Pale Blue — Chief MacRae (Engineering)",
        "instruction": (
            "A gruff, endlessly competent chief engineer in her 50s: weathered Scottish burr, warm "
            "gravel in the low end, quick dry wit. Grumbles affectionately about her ship the way "
            "other people talk about their children. Working-class Glasgow accent, believable under "
            "stress, a chuckle always one breath away. Scotty's spiritual heir."),
        "reference_text": "Matter and antimatter balanced to one part in a trillion, Captain. She purrs like a cat that knows it owns the house.",
    },
}

def read_cfg():
    return dict(l.split("=", 1) for l in CFG_PATH.read_text().splitlines()
                if "=" in l and not l.strip().startswith("#"))

def write_cfg_id(role_key, model_id):
    lines = CFG_PATH.read_text().splitlines()
    key = f"{role_key}_VOICE"
    out = [f"{key}={model_id}" if l.startswith(key + "=") else l for l in lines]
    CFG_PATH.write_text("\n".join(out) + "\n")

def design(cfg, role, spec, n=3):
    print(f"[{role}] designing {n} candidates …")
    r = requests.post(f"{API}/v1/voice-design",
        headers={"Authorization": f"Bearer {cfg['API_KEY']}",
                 "Content-Type": "application/json", "model": "voice-design-1"},
        json={"instruction": spec["instruction"], "reference_text": spec["reference_text"],
              "language": "en", "n": n, "num_step": 48, "guidance_scale": 2, "seed": 7},
        timeout=300)
    if r.status_code != 200:
        sys.exit(f"[{role}] voice-design failed: {r.status_code} {r.text[:300]}")
    cands = r.json()["candidates"]
    OUT.mkdir(exist_ok=True)
    paths = []
    for c in cands:
        pcm = base64.b64decode(c["audio_base64"])
        p = OUT / f"{role}_{c['index']}.wav"
        if pcm[:4] == b"RIFF":
            p.write_bytes(pcm)
        else:  # raw 16-bit mono PCM
            with wave.open(str(p), "wb") as w:
                w.setnchannels(1); w.setsampwidth(2); w.setframerate(c["sample_rate"])
                w.writeframes(pcm)
        paths.append(p)
        print(f"  saved {p.name} ({c['duration_ms']} ms)")
    return paths

def create_model(cfg, role, spec, wav_path):
    print(f"[{role}] registering voice model from {wav_path.name} …")
    r = requests.post(f"{API}/model",
        headers={"Authorization": f"Bearer {cfg['API_KEY']}"},
        data={"type": "tts", "title": spec["title"], "train_mode": "fast",
              "visibility": "private", "texts": spec["reference_text"],
              "enhance_audio_quality": "true"},
        files={"voices": (wav_path.name, wav_path.read_bytes(), "audio/wav")},
        timeout=300)
    if r.status_code not in (200, 201):
        sys.exit(f"[{role}] create-model failed: {r.status_code} {r.text[:300]}")
    mid = r.json()["_id"]
    print(f"  -> model id {mid}")
    return mid

def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--listen", action="store_true", help="save previews and exit")
    ap.add_argument("--pick", nargs="*", default=[], help="ROLE=index picks, e.g. NAV=2")
    args = ap.parse_args()
    picks = dict(p.split("=") for p in args.pick)
    cfg = read_cfg()
    if not cfg.get("API_KEY"):
        sys.exit("API_KEY missing from fish.config")
    for role, spec in PERSONALITIES.items():
        existing = cfg.get(f"{role}_VOICE", "")
        if existing and role not in picks:
            print(f"[{role}] already configured: {existing} (skipping)")
            continue
        paths = design(cfg, role, spec)
        if args.listen:
            continue
        idx = int(picks.get(role, 0))
        mid = create_model(cfg, role, spec, paths[idx])
        write_cfg_id(role, mid)
    if args.listen:
        print("\nAudition the WAVs in tools/voice_design_out/, then re-run with --pick.")
    else:
        print("\nAll crew voices configured. Next: python3 tools/generate_dialogue.py")

if __name__ == "__main__":
    main()
