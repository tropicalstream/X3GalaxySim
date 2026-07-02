#!/usr/bin/env bash
# One-time developer script: downloads the planetary textures and star catalogs.
# The app runs fine WITHOUT these (procedural fallbacks kick in per gotcha #9),
# but the real data makes it sing. Run from the project root:
#     bash app/tools/fetch_assets.sh
set -e
ASSETS="$(cd "$(dirname "$0")/../src/main/assets" && pwd)"
export ASSETS
TEX="$ASSETS/textures"
mkdir -p "$TEX"

echo "== Star catalogs =="
# HYG database (magnitude-filter to keep the APK lean)
curl -L --fail -o /tmp/hygdata_full.csv.gz \
  "https://raw.githubusercontent.com/EnguerranVidal/HYG-STAR-MAP/main/hygdata_v3.csv.gz"
gunzip -c /tmp/hygdata_full.csv.gz > /tmp/hygdata_full.csv
python3 - <<'EOF'
import csv
rows = list(csv.reader(open('/tmp/hygdata_full.csv')))
head = rows[0]
mi = head.index('mag')
keep = [head] + [r for r in rows[1:] if r[mi] and float(r[mi]) <= 6.5]
import os
out = os.path.join(os.environ.get('ASSETS', '.'), 'hygdata.csv')
csv.writer(open(out, 'w', newline='')).writerows(keep)
print(f"hygdata.csv: {len(keep)-1} stars (mag <= 6.5)")
EOF

# OpenNGC
curl -L -o "$ASSETS/openngc.csv" \
  "https://raw.githubusercontent.com/mattiaverga/OpenNGC/master/database_files/NGC.csv"

echo "== Planetary textures (solarsystemscope.com, CC BY 4.0) =="
# 2k equirectangular diffuse maps; converted to WebP (far smaller than PNG/JPG)
while read -r name file; do
  [ -n "$name" ] || continue
  curl -L --fail -o "/tmp/${name}.jpg" "https://www.solarsystemscope.com/textures/download/${file}"
  cp "/tmp/${name}.jpg" "$TEX/${name}.jpg"
  echo "  + textures/${name}.jpg"
done <<'EOF'
sun 2k_sun.jpg
earth 2k_earth_daymap.jpg
moon 2k_moon.jpg
venus 2k_venus_atmosphere.jpg
mars 2k_mars.jpg
jupiter 2k_jupiter.jpg
saturn 2k_saturn.jpg
uranus 2k_uranus.jpg
neptune 2k_neptune.jpg
EOF
# Optional: Earth night lights / cloudless from shadedrelief.com/natural3 —
# grab manually if desired and save as textures/earth_night.webp.
echo "Done. Textures + catalogs are in place."
