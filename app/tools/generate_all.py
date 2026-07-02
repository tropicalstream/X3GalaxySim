#!/usr/bin/env python3
"""One command to rule them all: design crew voices (if needed), then render
every dialogue line. Requires: pip install requests

    python3 tools/generate_all.py
"""
import pathlib, subprocess, sys

HERE = pathlib.Path(__file__).resolve().parent
cfg = dict(l.split("=", 1) for l in (HERE / "fish.config").read_text().splitlines()
           if "=" in l and not l.strip().startswith("#"))

if not (cfg.get("NAV_VOICE") and cfg.get("SCI_VOICE") and cfg.get("ENG_VOICE")):
    print("=== Step 1/2: designing crew voice personalities (Fish voice-design-1) ===")
    subprocess.check_call([sys.executable, str(HERE / "design_voices.py")])
else:
    print("=== Step 1/2: crew voices already configured — skipping design ===")

print("\n=== Step 2/2: rendering all dialogue (Fish S2.1 Pro) ===")
subprocess.check_call([sys.executable, str(HERE / "generate_dialogue.py")])
print("\nAll voice assets are in app/src/main/assets/voice/. Build the APK and fly.")
